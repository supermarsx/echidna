package com.echidna.app.ui.alerts

import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.AudioStackProbe
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.SettingsState
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.WhitelistBindings
import com.echidna.app.ui.components.AlertSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Condition coverage for [buildAdvisoryAlerts]: which advisories fire, which stay silent, and which
 * user-facing category gate suppresses them.
 *
 * [AdvisoryAlertsTest] pins the action-target routing; this file pins the *conditions*. The builder
 * is the app's only fail-closed diagnosis surface — an advisory that stops firing when its condition
 * recurs is a silent regression, so each test asserts both the positive and the suppressed case.
 */
class AdvisoryAlertsConditionTest {

    // --- Install category ---------------------------------------------------------------------

    @Test
    fun `absent module status raises the unavailable-status advisory, not a false all-clear`() {
        val titles = titlesOf(moduleStatus = null)

        assertTrue(titles.contains("Control service status unavailable"))
        // With no status at all, the builder must not claim the module is specifically missing.
        assertFalse(titles.contains("Magisk module not detected"))
    }

    @Test
    fun `install alerts gate suppresses every install advisory`() {
        val titles = titlesOf(
            settings = settings(showInstallAlerts = false),
            moduleStatus = null,
            engineStatus = healthyEngine().copy(nativeInstalled = false),
        )

        assertFalse(titles.contains("Control service status unavailable"))
        assertFalse(titles.contains("Native engine is not installed"))
    }

    @Test
    fun `missing native engine is only flagged while processing is actually armed`() {
        val armed = titlesOf(engineStatus = healthyEngine().copy(nativeInstalled = false))
        assertTrue(armed.contains("Native engine is not installed"))

        // Master off, or bypass engaged: nothing is being processed, so the advisory is noise.
        assertFalse(
            titlesOf(
                settings = settings(masterEnabled = false),
                engineStatus = healthyEngine().copy(nativeInstalled = false),
            ).contains("Native engine is not installed")
        )
        assertFalse(
            titlesOf(
                settings = settings(bypass = true),
                engineStatus = healthyEngine().copy(nativeInstalled = false),
            ).contains("Native engine is not installed")
        )
    }

    // --- Bridge category ----------------------------------------------------------------------

    @Test
    fun `empty whitelist is flagged only while the engine is armed`() {
        val none = WhitelistBindings(emptyMap(), emptyMap())
        assertTrue(titlesOf(whitelistBindings = none).contains("No target apps are whitelisted"))

        // A whitelist entry that exists but is disabled still counts as zero enabled apps.
        assertTrue(
            titlesOf(whitelistBindings = WhitelistBindings(mapOf("com.example.app" to false), emptyMap()))
                .contains("No target apps are whitelisted")
        )
        assertFalse(
            titlesOf(settings = settings(bypass = true), whitelistBindings = none)
                .contains("No target apps are whitelisted")
        )
    }

    @Test
    fun `selinux advisory fires from either the state or the status field`() {
        assertTrue(
            titlesOf(moduleStatus = healthyModule().copy(selinuxState = "permissive - policy denied"))
                .contains("SELinux or policy probe needs attention")
        )
        assertTrue(
            titlesOf(moduleStatus = healthyModule().copy(selinuxStatus = "profile load failed"))
                .contains("SELinux or policy probe needs attention")
        )
        // Neither field carries an advisory word: stay quiet.
        assertFalse(
            titlesOf(moduleStatus = healthyModule().copy(selinuxState = "enforcing", selinuxStatus = "ok"))
                .contains("SELinux or policy probe needs attention")
        )
    }

    @Test
    fun `module notes are surfaced only when they carry an advisory word`() {
        assertTrue(
            titlesOf(moduleStatus = healthyModule().copy(notes = "hook partial on this image"))
                .contains("Module status includes a warning note")
        )
        assertFalse(
            titlesOf(moduleStatus = healthyModule().copy(notes = "all probes nominal"))
                .contains("Module status includes a warning note")
        )
    }

    @Test
    fun `an identical error is reported once, a differing bridge error is reported separately`() {
        val shared = "policy tool refused the profile"
        val deduped = alertsOf(
            engineStatus = healthyEngine().copy(lastError = shared),
            moduleStatus = healthyModule().copy(lastError = shared),
        )
        assertEquals(
            "the same error text must not be shown twice",
            1,
            deduped.count { it.category == "Incomplete bridge" },
        )

        val distinct = titlesOf(
            engineStatus = healthyEngine().copy(lastError = "engine side"),
            moduleStatus = healthyModule().copy(lastError = "bridge side"),
        )
        assertTrue(distinct.contains("Engine status reports an error"))
        assertTrue(distinct.contains("Control bridge reported an error"))
    }

    @Test
    fun `the compatibility-probe reminder honours its user setting and a completed probe`() {
        assertTrue(
            titlesOf(settings = settings(remindCompatibilityProbe = true), compatibility = null)
                .contains("Compatibility probe has not run")
        )
        assertFalse(
            titlesOf(settings = settings(remindCompatibilityProbe = false), compatibility = null)
                .contains("Compatibility probe has not run")
        )
        assertFalse(
            titlesOf(
                settings = settings(remindCompatibilityProbe = true),
                compatibility = CompatibilityResult("enforcing", emptyList(), emptyList()),
            ).contains("Compatibility probe has not run")
        )
    }

    @Test
    fun `compatibility notes are filtered to advisory ones and capped at three`() {
        val alerts = alertsOf(
            compatibility = CompatibilityResult(
                selinuxStatus = "enforcing",
                audioStack = emptyList(),
                notes = listOf(
                    "everything nominal",
                    "probe one failed",
                    "probe two missing",
                    "probe three unavailable",
                    "probe four denied",
                ),
            ),
        )
        val notes = alerts.filter { it.title == "Compatibility probe note needs review" }

        assertEquals("the note list must be capped so Alerts stays readable", 3, notes.size)
        assertTrue(notes.none { it.detail.contains("everything nominal") })
        assertTrue(notes.first().detail.contains("probe one failed"))
    }

    @Test
    fun `bridge alerts gate suppresses every bridge advisory`() {
        val titles = titlesOf(
            settings = settings(showBridgeAlerts = false, remindCompatibilityProbe = true),
            engineStatus = healthyEngine().copy(lastError = "boom"),
            moduleStatus = healthyModule().copy(zygiskEnabled = false, notes = "probe failed"),
            whitelistBindings = WhitelistBindings(emptyMap(), emptyMap()),
        )

        assertTrue(
            titles.none {
                it in setOf(
                    "No target apps are whitelisted",
                    "Engine status reports an error",
                    "Zygisk is disabled or not visible",
                    "Module status includes a warning note",
                    "Compatibility probe has not run",
                )
            }
        )
    }

    // --- Hardware category --------------------------------------------------------------------

    @Test
    fun `an unpackaged CPU ABI wins over the limited-hook-support advisory`() {
        val unpackaged = alertsOf(
            moduleStatus = healthyModule().copy(
                cpu = healthyCpu().copy(moduleSupported = false, nativeHooksSupported = false, message = ""),
            ),
        )
        val alert = unpackaged.single { it.category == "Hardware compatibility" && it.title.startsWith("CPU ABI") }

        assertEquals("CPU ABI is not packaged by Echidna", alert.title)
        // A blank probe message must still name the ABI rather than render an empty detail.
        assertTrue(alert.detail.contains("arm64-v8a"))
    }

    @Test
    fun `a packaged ABI without native hooks reports limited support and names the zygisk ABI`() {
        val alerts = alertsOf(
            moduleStatus = healthyModule().copy(
                cpu = healthyCpu().copy(
                    moduleSupported = true,
                    nativeHooksSupported = false,
                    zygiskAbi = "armeabi-v7a",
                    message = "",
                ),
            ),
        )
        val alert = alerts.single { it.title == "CPU ABI has limited native hook support" }

        assertTrue(alert.detail.contains("armeabi-v7a"))
        assertTrue(alerts.none { it.title == "CPU ABI is not packaged by Echidna" })
    }

    @Test
    fun `a supplied CPU probe message is preferred over the synthesised fallback`() {
        val alerts = alertsOf(
            moduleStatus = healthyModule().copy(
                cpu = healthyCpu().copy(moduleSupported = false, message = "riscv64 is not built"),
            ),
        )

        assertEquals(
            "riscv64 is not built",
            alerts.single { it.title == "CPU ABI is not packaged by Echidna" }.detail,
        )
    }

    @Test
    fun `an unclassified vendor family is flagged by either spelling`() {
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(vendorFamily = "unknown")))
                .contains("Vendor audio family is not classified")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(vendorFamily = "Unclassified vendor")))
                .contains("Vendor audio family is not classified")
        )
        assertFalse(
            titlesOf(moduleStatus = withStack(healthyStack().copy(vendorFamily = "MediaTek")))
                .contains("Vendor audio family is not classified")
        )
    }

    @Test
    fun `each missing audio library raises its own advisory`() {
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(openSlEsAvailable = false)))
                .contains("OpenSL ES library not found")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(audioFlingerClientAvailable = false)))
                .contains("AudioFlinger client library not found")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(tinyAlsaAvailable = false)))
                .contains("tinyalsa library not found")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(lowLatency = false)))
                .contains("Low-latency audio feature absent")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(proAudio = false)))
                .contains("Pro audio feature absent")
        )
    }

    @Test
    fun `an unidentifiable HAL is flagged for both blank and unknown labels`() {
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(hal = "  ")))
                .contains("Audio HAL could not be identified")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(hal = "Unknown")))
                .contains("Audio HAL could not be identified")
        )
        assertFalse(
            titlesOf(moduleStatus = withStack(healthyStack().copy(hal = "qcom")))
                .contains("Audio HAL could not be identified")
        )
    }

    @Test
    fun `a missing sample rate or buffer size reports an incomplete probe`() {
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(sampleRate = 0)))
                .contains("Incomplete audio stack probe")
        )
        assertTrue(
            titlesOf(moduleStatus = withStack(healthyStack().copy(framesPerBuffer = 0)))
                .contains("Incomplete audio stack probe")
        )
        assertFalse(titlesOf().contains("Incomplete audio stack probe"))
    }

    @Test
    fun `latency and xrun advisories follow the configured thresholds`() {
        val overLatency = alertsOf(
            settings = settings(alertLatencyThresholdMs = 40),
            telemetry = emptyTelemetry().copy(averageLatencyMs = 41.6f),
        )
        val alert = overLatency.single { it.title == "High processing latency" }
        assertTrue("the detail must quote the measurement", alert.detail.contains("42 ms"))
        assertTrue(alert.detail.contains("40 ms alert threshold"))

        // Exactly at the threshold is not "above" it.
        assertFalse(
            titlesOf(
                settings = settings(alertLatencyThresholdMs = 40),
                telemetry = emptyTelemetry().copy(averageLatencyMs = 40f),
            ).contains("High processing latency")
        )
        // Raising the threshold silences a previously-firing advisory.
        assertFalse(
            titlesOf(
                settings = settings(alertLatencyThresholdMs = 100),
                telemetry = emptyTelemetry().copy(averageLatencyMs = 41.6f),
            ).contains("High processing latency")
        )

        // XRuns alert at-or-above the threshold.
        assertTrue(
            titlesOf(
                settings = settings(alertXrunThreshold = 3),
                telemetry = emptyTelemetry().copy(xruns = 3),
            ).contains("Audio XRuns detected")
        )
        assertFalse(
            titlesOf(
                settings = settings(alertXrunThreshold = 3),
                telemetry = emptyTelemetry().copy(xruns = 2),
            ).contains("Audio XRuns detected")
        )
    }

    @Test
    fun `probes already covered by module status are not duplicated from the wizard result`() {
        val compatibility = CompatibilityResult(
            selinuxStatus = "enforcing",
            audioStack = listOf(
                probe("AAudio (native low-latency)"),
                probe("Low-latency audio"),
                probe("Pro audio"),
                probe("tinyalsa library"),
            ),
            notes = emptyList(),
        )

        // With module status present, the three device-feature probes are already reported by the
        // hardware block above; only the remaining probe should be echoed from the wizard.
        val withStatus = alertsOf(compatibility = compatibility)
            .filter { it.title.endsWith("probe is unsupported") }
        assertEquals(listOf("tinyalsa library probe is unsupported"), withStatus.map { it.title })

        // Without module status there is nothing else reporting them, so all are surfaced (capped).
        val withoutStatus = alertsOf(moduleStatus = null, compatibility = compatibility)
            .filter { it.title.endsWith("probe is unsupported") }
        assertEquals(3, withoutStatus.size)
    }

    @Test
    fun `runtime telemetry warnings are joined into a single advisory`() {
        val alerts = alertsOf(
            telemetry = emptyTelemetry().copy(warnings = listOf("xrun burst", "clock drift")),
        )
        val alert = alerts.single { it.title == "Runtime telemetry has warnings" }

        assertEquals("xrun burst; clock drift", alert.detail)
        assertFalse(titlesOf().contains("Runtime telemetry has warnings"))
    }

    @Test
    fun `hardware alerts gate suppresses hardware and runtime advisories`() {
        val titles = titlesOf(
            settings = settings(showHardwareAlerts = false, alertXrunThreshold = 1),
            moduleStatus = withStack(healthyStack().copy(proAudio = false, lowLatency = false)),
            telemetry = emptyTelemetry().copy(xruns = 99, warnings = listOf("boom")),
        )

        assertTrue(titles.none { it.startsWith("Pro audio") || it.startsWith("Low-latency") })
        assertFalse(titles.contains("Audio XRuns detected"))
        assertFalse(titles.contains("Runtime telemetry has warnings"))
    }

    // --- Install mix-up category --------------------------------------------------------------

    @Test
    fun `an installed module without an active zygisk bridge is called out as a mix-up`() {
        val titles = titlesOf(
            moduleStatus = healthyModule().copy(magiskModuleInstalled = true, zygiskEnabled = false),
        )
        assertTrue(titles.contains("Magisk module present but Zygisk is not active"))
        assertFalse(titlesOf().contains("Magisk module present but Zygisk is not active"))
    }

    @Test
    fun `zygisk availability alone never counts as a verified native route`() {
        val titles = titlesOf(
            moduleStatus = healthyModule().copy(zygiskEnabled = true, javaFallbackRecommended = true),
        )
        assertTrue(titles.contains("Native capture route remains unverified"))
        // Zygisk-up is the discriminator: with it down, the LSPosed advisory fires instead.
        assertFalse(titles.contains("LSPosed compatibility mode recommended"))
    }

    @Test
    fun `a missing module with a recommended fallback points back at the installer`() {
        val titles = titlesOf(
            moduleStatus = healthyModule().copy(
                magiskModuleInstalled = false,
                javaFallbackRecommended = true,
            ),
        )
        assertTrue(titles.contains("Native module missing; fallback is only a recommendation"))
    }

    @Test
    fun `compatibility mode with a native module present is reported as a possible mix-up`() {
        assertTrue(
            titlesOf(settings = settings(engineMode = DspEngineMode.COMPATIBILITY))
                .contains("Compatibility mode selected with native module present")
        )
        // Native module absent: choosing compatibility mode is simply correct, not a mix-up.
        assertFalse(
            titlesOf(
                settings = settings(engineMode = DspEngineMode.COMPATIBILITY),
                engineStatus = healthyEngine().copy(nativeInstalled = false),
            ).contains("Compatibility mode selected with native module present")
        )
    }

    @Test
    fun `install mix-up gate suppresses the whole mix-up family`() {
        val titles = titlesOf(
            settings = settings(
                showInstallMixupAlerts = false,
                engineMode = DspEngineMode.COMPATIBILITY,
            ),
            moduleStatus = healthyModule().copy(
                zygiskEnabled = false,
                javaFallbackRecommended = true,
            ),
        )

        assertTrue(titles.none { it.contains("mix-up", ignoreCase = true) })
        assertFalse(titles.contains("LSPosed compatibility mode recommended"))
        assertFalse(titles.contains("Compatibility mode selected with native module present"))
    }

    // --- Presentation helpers -----------------------------------------------------------------

    @Test
    fun `severity marks the blocking categories as errors and everything else as a warning`() {
        assertEquals(AlertSeverity.ERROR, advisorySeverity("Incomplete install"))
        assertEquals(AlertSeverity.ERROR, advisorySeverity("Incomplete bridge"))
        assertEquals(AlertSeverity.ERROR, advisorySeverity("Bridge risk"))
        assertEquals(AlertSeverity.WARNING, advisorySeverity("Hardware compatibility"))
        assertEquals(AlertSeverity.WARNING, advisorySeverity("Install mix-up"))
    }

    @Test
    fun `the dismiss key is stable across detail changes but distinct per condition`() {
        val alert = AdvisoryAlert("Zygisk is disabled", "detail one", "Incomplete bridge", AlertActionTarget.OPEN_MAGISK)

        assertEquals(
            "the key must survive a detail rewrite so a dismissal is not resurrected",
            advisoryAlertKey(alert),
            advisoryAlertKey(alert.copy(detail = "detail two", action = AlertActionTarget.NONE)),
        )
        assertTrue(advisoryAlertKey(alert).startsWith(ADVISORY_KEY_PREFIX))
        org.junit.Assert.assertNotEquals(
            advisoryAlertKey(alert),
            advisoryAlertKey(alert.copy(category = "Bridge risk")),
        )
    }

    @Test
    fun `long details are truncated with an ellipsis rather than flooding the card`() {
        val long = "e".repeat(400)
        val alert = alertsOf(engineStatus = healthyEngine().copy(lastError = long))
            .single { it.title == "Engine status reports an error" }

        assertEquals(220, alert.detail.length)
        assertTrue(alert.detail.endsWith("..."))

        // A detail already within the budget is passed through untouched.
        assertEquals(
            "short error",
            alertsOf(engineStatus = healthyEngine().copy(lastError = "short error"))
                .single { it.title == "Engine status reports an error" }.detail,
        )
    }

    @Test
    fun `advisory word matching is case-insensitive and substring based`() {
        assertTrue("Policy DENIED by SELinux".containsAdvisoryWord())
        assertTrue("module is not installed".containsAdvisoryWord())
        assertFalse("all probes nominal".containsAdvisoryWord())
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private fun alertsOf(
        settings: SettingsState = settings(),
        engineStatus: EngineStatus = healthyEngine(),
        moduleStatus: ModuleStatus? = healthyModule(),
        compatibility: CompatibilityResult? = CompatibilityResult("enforcing", emptyList(), emptyList()),
        telemetry: TelemetrySnapshot = emptyTelemetry(),
        whitelistBindings: WhitelistBindings = whitelisted(),
    ): List<AdvisoryAlert> = buildAdvisoryAlerts(
        settings = settings,
        engineStatus = engineStatus,
        moduleStatus = moduleStatus,
        compatibility = compatibility,
        telemetry = telemetry,
        whitelistBindings = whitelistBindings,
    )

    private fun titlesOf(
        settings: SettingsState = settings(),
        engineStatus: EngineStatus = healthyEngine(),
        moduleStatus: ModuleStatus? = healthyModule(),
        compatibility: CompatibilityResult? = CompatibilityResult("enforcing", emptyList(), emptyList()),
        telemetry: TelemetrySnapshot = emptyTelemetry(),
        whitelistBindings: WhitelistBindings = whitelisted(),
    ): List<String> = alertsOf(
        settings, engineStatus, moduleStatus, compatibility, telemetry, whitelistBindings,
    ).map(AdvisoryAlert::title)

    /** Defaults chosen so a healthy fixture raises no advisories at all. */
    private fun settings(
        masterEnabled: Boolean = true,
        bypass: Boolean = false,
        engineMode: DspEngineMode = DspEngineMode.NATIVE_FIRST,
        showInstallAlerts: Boolean = true,
        showBridgeAlerts: Boolean = true,
        showHardwareAlerts: Boolean = true,
        showInstallMixupAlerts: Boolean = true,
        remindCompatibilityProbe: Boolean = false,
        alertLatencyThresholdMs: Int = 40,
        alertXrunThreshold: Int = 3,
    ) = SettingsState(
        masterEnabled = masterEnabled,
        bypass = bypass,
        engineMode = engineMode,
        showInstallAlerts = showInstallAlerts,
        showBridgeAlerts = showBridgeAlerts,
        showHardwareAlerts = showHardwareAlerts,
        showInstallMixupAlerts = showInstallMixupAlerts,
        remindCompatibilityProbe = remindCompatibilityProbe,
        alertLatencyThresholdMs = alertLatencyThresholdMs,
        alertXrunThreshold = alertXrunThreshold,
    )

    private fun withStack(stack: AudioStackInfo) = healthyModule().copy(audioStack = stack)

    private fun probe(name: String) = AudioStackProbe(name, supported = false, latencyEstimateMs = null, message = "absent")

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
