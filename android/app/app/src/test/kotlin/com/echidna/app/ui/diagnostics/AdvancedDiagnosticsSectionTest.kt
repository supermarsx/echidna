package com.echidna.app.ui.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.echidna.app.model.CaptureOwner
import com.echidna.app.model.CaptureOwnerReason
import com.echidna.app.model.CaptureOwnerStatus
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.TelemetrySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for the Advanced diagnostics section under Robolectric + Compose.
 *
 * The section's whole purpose is to be *honest*: every telemetry-backed row must render an
 * "unavailable" dash (and an "(unavailable)"-suffixed description) when the engine is not running,
 * rather than a fabricated zero, and the environment probe must say the control service is not
 * bound rather than invent a device profile. These tests pin exactly those branches, plus the
 * expand/collapse behaviour, per-hook status derivation, and the capture-ownership notes that only
 * appear for the matching owner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdvancedDiagnosticsSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ownedByZygisk =
        CaptureOwnerStatus(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)

    private fun setContent(
        moduleStatus: ModuleStatus? = null,
        telemetry: TelemetrySnapshot = telemetrySnapshot(),
        metrics: DspMetrics = dspMetrics(),
        latencyMode: LatencyMode = LatencyMode.LOW_LATENCY,
        masterEnabled: Boolean = true,
        bypass: Boolean = false,
        captureOwnerStatus: CaptureOwnerStatus = ownedByZygisk,
    ) {
        composeRule.setContent {
            MaterialTheme {
                AdvancedDiagnosticsSection(
                    moduleStatus = moduleStatus,
                    telemetry = telemetry,
                    metrics = metrics,
                    latencyMode = latencyMode,
                    masterEnabled = masterEnabled,
                    bypass = bypass,
                    captureOwnerStatus = captureOwnerStatus,
                )
            }
        }
    }

    /**
     * Invokes the OnClick semantics of a merged, clickable row. The expanded section is far taller
     * than the test viewport, so a real gesture would need a scroll container the section does not
     * own; the semantics action exercises the same onClick wiring. Real-gesture coverage of the
     * screen lives in the instrumentation tests.
     */
    private fun clickRow(text: String) =
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    private fun expand() {
        clickRow("Advanced diagnostics")
        composeRule.waitForIdle()
    }

    /**
     * Asserts [count] nodes render [text]. The count is explicit (rather than "at least one") so a
     * row that silently stops rendering is still caught when a sibling row happens to share the
     * same short value — e.g. two independent probes both reading "supported".
     */
    private fun assertShown(text: String, substring: Boolean = false, count: Int = 1) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(count)

    private fun assertAbsent(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(0)

    // -----------------------------------------------------------------------
    // Expand / collapse
    // -----------------------------------------------------------------------

    @Test
    fun `collapsed section shows only its header`() {
        setContent()
        assertShown("Advanced diagnostics")
        // Nothing behind the disclosure is composed until it is opened.
        assertAbsent("Capture ownership")
        assertAbsent("Environment")
        assertAbsent("Raw status")
    }

    @Test
    fun `expanding reveals every diagnostic group and collapsing hides them again`() {
        setContent()
        expand()
        assertShown("Capture ownership")
        assertShown("Hook status")
        assertShown("Hook attach")
        assertShown("Audio pipeline")
        assertShown("Performance")
        assertShown("Environment")
        assertShown("Raw status")

        clickRow("Advanced diagnostics")
        composeRule.waitForIdle()
        assertAbsent("Environment")
        assertAbsent("Raw status")
    }

    // -----------------------------------------------------------------------
    // Fail-closed: no engine means "unavailable", not zeros
    // -----------------------------------------------------------------------

    @Test
    fun `an inactive engine reports unavailable metrics instead of fabricated zeros`() {
        setContent(telemetry = telemetrySnapshot(runtimeVerified = false))
        expand()

        assertShown("Engine not active. Live pipeline and performance metrics are", substring = true)
        // Each telemetry-backed row marks its own description "(unavailable)".
        assertShown(
            "Whether at least one audio route currently reports an installed hook.  (unavailable)",
        )
        assertShown("Total audio blocks the engine has processed since start.  (unavailable)")
        assertShown("Blocks admitted but intentionally left untransformed (bypass / policy).  (unavailable)")
        assertShown("Dropped or late audio buffers detected in the callback.  (unavailable)")
        assertShown("Mean fraction of the callback deadline spent in DSP.  (unavailable)")
        assertShown("Per-callback records currently in the telemetry ring.  (unavailable)")
    }

    @Test
    fun `verified runtime telemetry renders the real counters and drops the not-active banner`() {
        setContent(
            telemetry = telemetrySnapshot(
                runtimeVerified = true,
                processing = true,
                totalCallbacks = 4321L,
                totalBypasses = 77L,
                xruns = 19L.toInt(),
                totalInstallEvents = 6L,
                totalInstallFailures = 2L,
                anyRouteInstalled = true,
                samples = listOf(telemetrySample(durationUs = 812, cpuUs = 407)),
            ),
            metrics = dspMetrics(cpuLoadPercent = 12.5f, endToEndLatencyMs = 3.25f),
        )
        expand()

        assertAbsent("Engine not active. Live pipeline and performance metrics are", substring = true)
        assertShown("4321")
        assertShown("77")
        assertShown("19")
        assertShown("6")
        assertShown("2")
        assertShown("yes")
        assertShown("12.5%")
        assertShown("3.25 ms")
        assertShown("812 µs wall / 407 µs CPU")
        // The same descriptions from the unavailable case must now be free of the suffix.
        assertShown("Total audio blocks the engine has processed since start.")
        assertAbsent("Total audio blocks the engine has processed since start.  (unavailable)")
    }

    @Test
    fun `the last-callback row is unavailable when no sample has been recorded`() {
        setContent(telemetry = telemetrySnapshot(runtimeVerified = true, processing = true))
        expand()
        assertShown("Timing of the most recent processed block.  (unavailable)")
    }

    // -----------------------------------------------------------------------
    // Hook status
    // -----------------------------------------------------------------------

    @Test
    fun `hook status distinguishes an inactive engine from a live engine with no attempts`() {
        setContent(telemetry = telemetrySnapshot(runtimeVerified = false, hooks = emptyList()))
        expand()
        assertShown("Engine not active — no hook data.")
        assertAbsent("No hook attempts reported.")
    }

    @Test
    fun `a live engine with no hook attempts says so rather than blaming the engine`() {
        setContent(
            telemetry = telemetrySnapshot(
                runtimeVerified = true,
                processing = true,
                hooks = emptyList(),
            ),
        )
        expand()
        assertShown("No hook attempts reported.")
        assertAbsent("Engine not active — no hook data.")
    }

    @Test
    fun `each reported hook renders its friendly label, derived status, and counters`() {
        setContent(
            telemetry = telemetrySnapshot(
                runtimeVerified = true,
                hooks = listOf(
                    hookTelemetry("aaudio_stream_read", attempts = 3, successes = 3),
                    hookTelemetry(
                        "AudioFlinger::RecordThread",
                        attempts = 5,
                        successes = 4,
                        failures = 1,
                    ),
                    hookTelemetry(
                        "android.media.AudioRecord.read",
                        attempts = 2,
                        successes = 0,
                        failures = 2,
                        library = "libaudioclient.so",
                        symbol = "_ZN7android11AudioRecord4readEPvjb",
                        reason = "symbol not found",
                    ),
                    hookTelemetry("tinyalsa_pcm_read"),
                ),
            ),
        )
        expand()

        // Raw engine hook names are mapped to the documented friendly labels.
        assertShown("AAudio")
        assertShown("AudioFlinger")
        assertShown("AudioRecord")
        assertShown("tinyALSA")

        // Status is derived from successes/failures/attempts, not from a flag the engine sends.
        assertShown("Installed")
        assertShown("Installed (with failures)")
        assertShown("Not installed")
        assertShown("Pending")

        assertShown("attempts 3 • ok 3 • failed 0")
        assertShown("attempts 5 • ok 4 • failed 1")
        assertShown(
            "lib=libaudioclient.so • sym=_ZN7android11AudioRecord4readEPvjb • reason=symbol not found",
        )
    }

    // -----------------------------------------------------------------------
    // Capture ownership
    // -----------------------------------------------------------------------

    @Test
    fun `zygisk ownership names the owner and warns that the shim is inert`() {
        setContent(captureOwnerStatus = ownedByZygisk)
        expand()
        assertShown(CaptureOwner.ZYGISK.label)
        assertShown("The LSPosed shim is inert while Zygisk owns capture.", substring = true)
        assertAbsent("The shim hooks android.media.AudioRecord only.", substring = true)
    }

    @Test
    fun `lsposed ownership warns about the AudioRecord-only coverage instead`() {
        setContent(
            captureOwnerStatus = CaptureOwnerStatus(
                CaptureOwner.LSPOSED,
                CaptureOwnerReason.ACTIVE,
            ),
        )
        expand()
        assertShown(CaptureOwner.LSPOSED.label)
        assertShown("The shim hooks android.media.AudioRecord only.", substring = true)
        assertAbsent("The LSPosed shim is inert while Zygisk owns capture.", substring = true)
    }

    @Test
    fun `an unowned capture path explains why and shows no owner label`() {
        setContent(
            captureOwnerStatus = CaptureOwnerStatus(
                CaptureOwner.NONE,
                CaptureOwnerReason.NO_WHITELISTED_APPS,
            ),
        )
        expand()
        // With no owner the row's value is the unavailable dash and the reason becomes its
        // description, which MetricRow suffixes with "(unavailable)".
        assertShown(CaptureOwnerReason.NO_WHITELISTED_APPS.summary, substring = true)
        assertAbsent(CaptureOwner.ZYGISK.label)
        assertAbsent(CaptureOwner.LSPOSED.label)
        // No owner means neither owner-specific note may be shown.
        assertAbsent("The LSPosed shim is inert while Zygisk owns capture.", substring = true)
        assertAbsent("The shim hooks android.media.AudioRecord only.", substring = true)
    }

    // -----------------------------------------------------------------------
    // Audio pipeline group
    // -----------------------------------------------------------------------

    @Test
    fun `the pipeline group shows the device stack figures and the configured latency target`() {
        setContent(
            moduleStatus = moduleStatus(stack = audioStack(sampleRate = 48000, framesPerBuffer = 192)),
            latencyMode = LatencyMode.BALANCED,
        )
        expand()
        assertShown("48000 Hz")
        assertShown("192")
        assertShown("Balanced (~20 ms)")
        assertShown("Ring/queue depth is not exported by the engine, so it is not shown.")
    }

    @Test
    fun `an unreported sample rate and block size stay unavailable`() {
        setContent(
            moduleStatus = moduleStatus(stack = audioStack(sampleRate = 0, framesPerBuffer = 0)),
        )
        expand()
        assertShown("Device output sample rate (AudioManager).  (unavailable)")
        assertShown("Native audio block size the low-latency path uses.  (unavailable)")
    }

    // -----------------------------------------------------------------------
    // Environment group
    // -----------------------------------------------------------------------

    @Test
    fun `an unbound control service reports no environment probe at all`() {
        setContent(moduleStatus = null)
        expand()
        assertShown("Control service not bound — environment probe unavailable.")
        // None of the probe rows may render a guessed value.
        assertAbsent("Kernel enforcement state and Echidna policy posture.")
        assertAbsent("Vendor hardware-abstraction-layer / audio board.")
    }

    @Test
    fun `a bound control service renders the real probe values`() {
        setContent(
            moduleStatus = moduleStatus(
                magiskModuleInstalled = true,
                zygiskEnabled = true,
                selinuxState = "policy applied",
                selinuxStatus = "Enforcing",
                nativeRouteVerified = true,
                javaFallbackRecommended = false,
                cpu = cpuArch(cpuFamily = "ARM64", primaryAbi = "arm64-v8a", zygiskAbi = "arm64"),
                stack = audioStack(hal = "qcom-audio", vendorFamily = "Qualcomm"),
                notes = "Probe completed from the privileged service.",
                lastError = "policy reload skipped",
            ),
        )
        expand()
        assertAbsent("Control service not bound — environment probe unavailable.")
        assertShown("Enforcing (policy applied)")
        assertShown("ARM64 (arm64-v8a)")
        assertShown("arm64")
        // "supported" is the value of both the native-hook-ABI row and the AAudio row.
        assertShown("supported", count = 2)
        assertShown("installed")
        assertShown("enabled")
        assertShown("not recommended")
        assertShown("verified")
        assertShown("qcom-audio")
        assertShown("Qualcomm")
        assertShown("arm64-v8a, armeabi-v7a")
        assertShown("Probe completed from the privileged service.")
        assertShown("Last error: policy reload skipped")
    }

    @Test
    fun `a degraded environment reports the negative side of each probe`() {
        setContent(
            moduleStatus = moduleStatus(
                magiskModuleInstalled = false,
                zygiskEnabled = false,
                nativeRouteVerified = false,
                javaFallbackRecommended = true,
                cpu = cpuArch(
                    supportedAbis = emptyList(),
                    nativeHooksSupported = false,
                    zygiskAbi = "",
                    message = "32-bit only device",
                ),
                stack = audioStack(
                    hal = "",
                    vendorFamily = "",
                    aaudioSupported = false,
                    openSlEsAvailable = false,
                    audioFlingerClientAvailable = false,
                    tinyAlsaAvailable = false,
                    lowLatency = false,
                    proAudio = false,
                ),
            ),
        )
        expand()
        assertShown("not installed")
        assertShown("disabled")
        assertShown("recommended")
        assertShown("unverified")
        assertShown("limited")
        assertShown("32-bit only device")
        assertShown("unknown")
        assertShown("not reported")
        // Both the low-latency and pro-audio device features read "absent".
        assertShown("absent", count = 2)
        // Three library-presence probes all read "not found".
        assertShown("not found", count = 3)
        // A blank HAL string must fall through to the unavailable dash, not render as empty.
        assertShown("Vendor hardware-abstraction-layer / audio board.  (unavailable)")
        assertShown("Best-effort SoC/HAL family classification from build properties.  (unavailable)")
        // An empty ABI list omits the row entirely rather than showing a blank value.
        assertAbsent("ABI order reported by Build.SUPPORTED_ABIS.")
    }

    // -----------------------------------------------------------------------
    // Raw status
    // -----------------------------------------------------------------------

    @Test
    fun `raw status stays collapsed until opened and then dumps the parsed state`() {
        setContent(
            moduleStatus = moduleStatus(selinuxStatus = "Enforcing"),
            telemetry = telemetrySnapshot(runtimeVerified = true, totalCallbacks = 55L),
            masterEnabled = true,
            bypass = false,
        )
        expand()
        assertAbsent("\"masterEnabled\"", substring = true)

        clickRow("Raw status")
        composeRule.waitForIdle()
        assertShown("\"masterEnabled\": true", substring = true)
        assertShown("\"bypass\": false", substring = true)
        assertShown("\"totalCallbacks\": 55", substring = true)
        assertShown("\"selinuxStatus\": \"Enforcing\"", substring = true)
    }

    @Test
    fun `raw status records a null module status rather than an empty object`() {
        setContent(moduleStatus = null, masterEnabled = false, bypass = true)
        expand()
        clickRow("Raw status")
        composeRule.waitForIdle()
        assertShown("\"moduleStatus\": null", substring = true)
        assertShown("\"masterEnabled\": false", substring = true)
        assertShown("\"bypass\": true", substring = true)
    }

    @Test
    fun `raw status serialises every reported hook`() {
        val hooks: List<HookTelemetry> = listOf(
            hookTelemetry("aaudio_stream_read", attempts = 3, successes = 3, library = "libaaudio.so"),
        )
        setContent(telemetry = telemetrySnapshot(hooks = hooks))
        expand()
        clickRow("Raw status")
        composeRule.waitForIdle()
        assertShown("\"name\": \"aaudio_stream_read\"", substring = true)
        assertShown("\"library\": \"libaaudio.so\"", substring = true)
    }
}
