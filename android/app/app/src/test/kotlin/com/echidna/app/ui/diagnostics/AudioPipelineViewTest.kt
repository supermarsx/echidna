package com.echidna.app.ui.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.TelemetrySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for the pipeline visualization.
 *
 * The view's contract is that motion and "live" wording are gated on REAL, verified telemetry: a
 * device with no engine must read "Not active", a device whose hook installed but has produced no
 * verified mutation must read "Hook installed — awaiting audio blocks", and only a verified
 * processing route may read "Live". The seven interception slots are always listed in orchestrator
 * priority order, with exactly one marked active — the highest-priority slot that actually
 * installed — and the rest carrying their honest per-slot status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPipelineViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        telemetry: TelemetrySnapshot = telemetrySnapshot(),
        moduleStatus: ModuleStatus? = null,
        bypass: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                AudioPipelineView(
                    telemetry = telemetry,
                    moduleStatus = moduleStatus,
                    bypass = bypass,
                )
            }
        }
    }

    private fun assertShown(text: String, substring: Boolean = false, count: Int = 1) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(count)

    private fun assertAbsent(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(0)

    /**
     * The live pipeline runs infinite flow/pulse animations, which never let the test clock go
     * idle. Turning off auto-advance lets assertions run against the composed first frame — the
     * standard Compose-test handling for an intentionally endless animation.
     */
    private fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    // -----------------------------------------------------------------------
    // The three top-level states
    // -----------------------------------------------------------------------

    @Test
    fun `no installed hook renders the not-active state and explains why nothing flows`() {
        setContent(telemetry = telemetrySnapshot())

        assertShown("Not active — no live capture on this device")
        assertAbsent("Live — capturing and processing audio")
        assertAbsent("Hook installed — awaiting audio blocks")
        assertShown("The engine is not hooking audio on this device", substring = true)
        // Every stage is still listed, so the user can see the path that would be used.
        assertShown("Capture source")
        assertShown("Capture hook layer")
        assertShown("DSP engine")
        assertShown("Processed PCM")
        assertShown("App / System consumer")
    }

    @Test
    fun `an installed hook without verified processing refuses to claim it is live`() {
        setContent(
            telemetry = telemetrySnapshot(
                hooks = listOf(hookTelemetry("aaudio_stream_read", attempts = 1, successes = 1)),
                runtimeVerified = true,
                processing = false,
                totalCallbacks = 900L,
            ),
        )

        assertShown("Hook installed — awaiting audio blocks")
        assertAbsent("Live — capturing and processing audio")
        assertAbsent("Not active — no live capture on this device")
        // The explanatory paragraph is only for the no-hook case.
        assertAbsent("The engine is not hooking audio on this device", substring = true)
        // Unverified callback totals must not surface as a block count on the DSP node.
        assertAbsent("900 blocks")
    }

    @Test
    fun `verified processing renders the live state with the real block count`() {
        freezeClock()
        setContent(
            telemetry = telemetrySnapshot(
                hooks = listOf(hookTelemetry("aaudio_stream_read", attempts = 1, successes = 1)),
                runtimeVerified = true,
                processing = true,
                totalCallbacks = 1500L,
            ),
        )

        assertShown("Live — capturing and processing audio")
        assertAbsent("Hook installed — awaiting audio blocks")
        assertShown("1500 blocks")
    }

    // -----------------------------------------------------------------------
    // Hook slots
    // -----------------------------------------------------------------------

    @Test
    fun `all seven orchestrator slots are listed even when nothing is hooked`() {
        setContent()
        assertShown("AAudio")
        assertShown("OpenSL ES")
        assertShown("AudioFlinger")
        assertShown("AudioRecord")
        assertShown("libc read()")
        assertShown("tinyALSA")
        assertShown("Audio HAL")
        // With no telemetry at all, every slot's status is the honest dash.
        assertShown("—", count = 7)
        assertAbsent("active")
    }

    @Test
    fun `the highest-priority installed hook wins and later slots report not reached`() {
        setContent(
            telemetry = telemetrySnapshot(
                runtimeVerified = true,
                hooks = listOf(
                    hookTelemetry("AudioFlinger::RecordThread", attempts = 1, successes = 1),
                    hookTelemetry("aaudio_stream_read", attempts = 2, successes = 2, failures = 0),
                ),
            ),
        )

        // AAudio is slot 0 and installed, so it is the single active capture path...
        assertShown("active")
        // ...while the also-installed lower-priority AudioFlinger slot reads "installed".
        assertShown("installed")
        // Every other slot without telemetry sits after the winning slot 0, so the orchestrator
        // never reached it — five of the seven, the sixth being the installed AudioFlinger.
        assertShown("not reached", count = 5)
        assertAbsent("—")
        // The active hook's counters are surfaced next to the layer title.
        assertShown("ok 2 · failed 0")
    }

    @Test
    fun `a hook that only ever failed is reported as failed, not pending`() {
        setContent(
            telemetry = telemetrySnapshot(
                runtimeVerified = true,
                hooks = listOf(
                    hookTelemetry("aaudio_stream_read", attempts = 3, successes = 0, failures = 3),
                    hookTelemetry("tinyalsa_pcm_read", attempts = 0),
                ),
            ),
        )

        assertShown("failed")
        assertShown("pending")
        // Nothing installed, so the layer has no active slot and no counters line.
        assertAbsent("active")
        assertAbsent("not reached")
        assertShown("Not active — no live capture on this device")
    }

    @Test
    fun `an unrecognised hook name is ignored rather than mapped to a wrong slot`() {
        setContent(
            telemetry = telemetrySnapshot(
                runtimeVerified = true,
                hooks = listOf(hookTelemetry("some_unknown_probe", attempts = 4, successes = 4)),
            ),
        )
        // It classifies to no slot, so no slot may claim to be active because of it.
        assertAbsent("active")
        assertShown("—", count = 7)
        assertShown("Not active — no live capture on this device")
    }

    // -----------------------------------------------------------------------
    // Bypass and device stack
    // -----------------------------------------------------------------------

    @Test
    fun `bypass is called out on the DSP node`() {
        setContent(bypass = true)
        assertShown("echidna_process_block — bypassed")
        assertAbsent("echidna_process_block · libech_dsp.so")
    }

    @Test
    fun `the DSP node names the real engine library when not bypassed`() {
        setContent(bypass = false)
        assertShown("echidna_process_block · libech_dsp.so")
        assertAbsent("echidna_process_block — bypassed")
    }

    @Test
    fun `the capture source shows the device sample rate only when the service reports one`() {
        setContent(moduleStatus = moduleStatus(stack = audioStack(sampleRate = 44100)))
        assertShown("44100 Hz")
    }

    @Test
    fun `an unreported sample rate leaves the capture source metric blank`() {
        setContent(moduleStatus = moduleStatus(stack = audioStack(sampleRate = 0)))
        assertAbsent("Hz", substring = true)
        assertShown("Microphone / audio input")
    }
}
