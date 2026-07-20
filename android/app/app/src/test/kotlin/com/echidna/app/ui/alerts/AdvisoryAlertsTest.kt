package com.echidna.app.ui.alerts

import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.SettingsState
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.WhitelistBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the advisory builder tags each actionable alert with the destination that resolves it,
 * so the Alerts screen routes the action button correctly (module → installer, Zygisk → Magisk,
 * hook-scope → whitelist, incompatible → compatibility wizard), and leaves guidance-only notices
 * without a dead button.
 */
class AdvisoryAlertsTest {

    @Test
    fun `module not installed routes to the installer`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(),
            engineStatus = healthyEngine(),
            moduleStatus = healthyModule().copy(magiskModuleInstalled = false),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        val alert = alerts.first { it.title == "Magisk module not detected" }
        assertEquals(AlertActionTarget.INSTALLER, alert.action)
    }

    @Test
    fun `zygisk disabled routes to open magisk`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(),
            engineStatus = healthyEngine(),
            moduleStatus = healthyModule().copy(zygiskEnabled = false),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        val alert = alerts.first { it.title == "Zygisk is disabled or not visible" }
        assertEquals(AlertActionTarget.OPEN_MAGISK, alert.action)
    }

    @Test
    fun `no whitelisted apps routes to the whitelist`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(),
            engineStatus = healthyEngine(),
            moduleStatus = healthyModule(),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = WhitelistBindings(emptyMap(), emptyMap())
        )
        val alert = alerts.first { it.title == "No target apps are whitelisted" }
        assertEquals(AlertActionTarget.WHITELIST, alert.action)
    }

    @Test
    fun `unsupported hardware routes to the compatibility wizard`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(),
            engineStatus = healthyEngine(),
            moduleStatus = healthyModule().copy(
                audioStack = healthyStack().copy(aaudioSupported = false)
            ),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        val alert = alerts.first { it.title == "AAudio low-latency path unavailable" }
        assertEquals(AlertActionTarget.COMPAT_WIZARD, alert.action)
    }

    @Test
    fun `guidance-only alert has no action button`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(),
            engineStatus = healthyEngine().copy(lastError = "boom"),
            moduleStatus = healthyModule(),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        val alert = alerts.first { it.title == "Engine status reports an error" }
        assertEquals(AlertActionTarget.NONE, alert.action)
        assertNull(alert.action.label())
    }

    @Test
    fun `action labels are non-null for actionable targets`() {
        assertEquals("Install engine", AlertActionTarget.INSTALLER.label())
        assertEquals("Open Magisk", AlertActionTarget.OPEN_MAGISK.label())
        assertEquals("Open Whitelist", AlertActionTarget.WHITELIST.label())
        assertEquals("Run Wizard", AlertActionTarget.COMPAT_WIZARD.label())
        assertEquals("Open engine mode", AlertActionTarget.ENGINE_MODE.label())
        assertNull(AlertActionTarget.NONE.label())
    }

    @Test
    fun `no native route in native-first mode routes to engine mode and names the setting`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(engineMode = DspEngineMode.NATIVE_FIRST),
            engineStatus = healthyEngine(),
            moduleStatus = noNativeRoute(),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        val alert = alerts.first { it.title == "LSPosed compatibility mode recommended" }

        assertEquals(AlertActionTarget.ENGINE_MODE, alert.action)
        assertEquals("Open engine mode", alert.action.label())
        assertTrue(alert.detail.contains("DSP engine mode -> Compatibility"))
        assertTrue(alert.detail.contains("android.media.AudioRecord"))
    }

    @Test
    fun `no native route already in compatibility mode routes to the whitelist instead`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(engineMode = DspEngineMode.COMPATIBILITY),
            engineStatus = healthyEngine(),
            moduleStatus = noNativeRoute(),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        val alert = alerts.first { it.title == "LSPosed compatibility mode recommended" }

        assertEquals(AlertActionTarget.WHITELIST, alert.action)
        assertTrue(alert.detail.contains("already Compatibility"))
        assertTrue(alert.detail.contains("AAudio"))
    }

    @Test
    fun `advisory is absent while a native route is verified`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(remindCompatibilityProbe = false),
            engineStatus = healthyEngine(),
            moduleStatus = healthyModule(),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        assertTrue(alerts.none { it.title == "LSPosed compatibility mode recommended" })
    }

    /** The state issue #18 describes: nothing native is verified, so the shim is the only route. */
    private fun noNativeRoute() = healthyModule().copy(
        zygiskEnabled = false,
        nativeRouteVerified = false,
        javaFallbackRecommended = true,
    )

    @Test
    fun `healthy state raises no install or magisk alerts`() {
        val alerts = buildAdvisoryAlerts(
            settings = SettingsState(remindCompatibilityProbe = false),
            engineStatus = healthyEngine(),
            moduleStatus = healthyModule(),
            compatibility = null,
            telemetry = emptyTelemetry(),
            whitelistBindings = whitelisted()
        )
        assertTrue(
            alerts.none {
                it.action == AlertActionTarget.INSTALLER || it.action == AlertActionTarget.OPEN_MAGISK
            }
        )
    }

    private fun healthyEngine() = EngineStatus(
        nativeInstalled = true,
        active = false,
        selinuxMode = "enforcing",
    )

    private fun healthyModule() = ModuleStatus(
        magiskModuleInstalled = true,
        zygiskEnabled = true,
        selinuxState = "enforcing",
        selinuxStatus = "ok",
        policyToolAvailable = true,
        policyAppliedVerified = true,
        nativeRouteVerified = true,
        javaFallbackRecommended = false,
        cpu = healthyCpu(),
        audioStack = healthyStack(),
        notes = null,
        lastError = null,
    )

    private fun healthyCpu() = CpuArchInfo(
        primaryAbi = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a"),
        cpuFamily = "arm64",
        is64Bit = true,
        zygiskAbi = "arm64-v8a",
        moduleSupported = true,
        nativeHooksSupported = true,
        supportLevel = "full",
        message = "",
    )

    private fun healthyStack() = AudioStackInfo(
        hal = "qcom",
        manufacturer = "Qualcomm",
        boardPlatform = "sm8550",
        vendorFamily = "Qualcomm",
        aaudioSupported = true,
        openSlEsAvailable = true,
        audioFlingerClientAvailable = true,
        tinyAlsaAvailable = true,
        lowLatency = true,
        proAudio = true,
        sampleRate = 48000,
        framesPerBuffer = 192,
    )

    private fun whitelisted() = WhitelistBindings(mapOf("com.example.app" to true), emptyMap())

    private fun emptyTelemetry() = TelemetrySnapshot(
        totalCallbacks = 0,
        averageLatencyMs = 0f,
        averageCpuPercent = 0f,
        inputRms = -120f,
        outputRms = -120f,
        inputPeak = -120f,
        outputPeak = -120f,
        detectedPitchHz = 0f,
        targetPitchHz = 0f,
        formantShiftCents = 0f,
        formantWidth = 0f,
        xruns = 0,
        warnings = emptyList(),
        samples = emptyList(),
        hooks = emptyList(),
    )
}
