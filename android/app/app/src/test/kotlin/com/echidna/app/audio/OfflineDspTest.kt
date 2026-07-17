package com.echidna.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the offline chunked processing PIPELINE with a deterministic in-process
 * [DspProcessor]. The real native engine's transform is proven by the native DSP tests
 * (native/dsp/tests/engine_test.cpp) and verified on device/emulator via the test-tone A/B;
 * here we prove the Kotlin side feeds blocks correctly and that a transform changes the audio.
 */
class OfflineDspTest {

    /** A trivial "obvious effect": doubles amplitude (with clamp) so wet clearly differs from dry. */
    private class GainProcessor(private val gain: Float) : DspProcessor {
        override val available = true
        override fun process(input: FloatArray, output: FloatArray, frames: Int): Boolean {
            for (i in 0 until frames) output[i] = (input[i] * gain).coerceIn(-1f, 1f)
            return true
        }
        override fun close() {}
    }

    @Test
    fun processedOutputDiffersFromInputForAnObviousEffect() {
        val input = TestTone.generate(TestTone.Kind.SINE_440, 48_000, durationSeconds = 0.5f, amplitude = 0.3f)
        val wet = OfflineDsp.process(GainProcessor(2.0f), input, chunkFrames = 1_024)
        assertNotNull(wet)
        wet!!
        assertFalse("wet must differ from dry", wet.contentEquals(input))
        // Doubling amplitude raises RMS by ~6 dB.
        assertTrue(AudioAnalysis.rmsDbfs(wet) > AudioAnalysis.rmsDbfs(input) + 3f)
    }

    @Test
    fun chunkBoundariesCoverEntireBuffer() {
        val input = FloatArray(2_500) { 0.4f }
        val wet = OfflineDsp.process(GainProcessor(0.5f), input, chunkFrames = 1_024)
        assertNotNull(wet)
        wet!!
        assertTrue(wet.all { kotlin.math.abs(it - 0.2f) < 1e-6f })
    }

    @Test
    fun unavailableProcessorReturnsNull() {
        val unavailable = object : DspProcessor {
            override val available = false
            override fun process(input: FloatArray, output: FloatArray, frames: Int) = false
            override fun close() {}
        }
        assertNull(OfflineDsp.process(unavailable, FloatArray(100) { 0.1f }))
    }
}
