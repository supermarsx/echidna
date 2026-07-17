package com.echidna.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestToneTest {

    @Test
    fun sine_hasExpectedLengthAndIsAudible() {
        val sr = 48_000
        val tone = TestTone.generate(TestTone.Kind.SINE_440, sr, durationSeconds = 1.0f, amplitude = 0.6f)
        assertEquals(sr, tone.size)
        // Non-silent: peak near the requested amplitude (fade only trims the very edges).
        assertTrue("tone should be audible", AudioAnalysis.peak(tone) > 0.5f)
        assertTrue("tone should not clip", AudioAnalysis.peak(tone) <= 1.0f)
    }

    @Test
    fun fadeEndsStartAndEndNearSilence() {
        val tone = TestTone.generate(TestTone.Kind.SINE_220, 48_000, durationSeconds = 0.5f)
        assertTrue("faded in", kotlin.math.abs(tone.first()) < 0.05f)
        assertTrue("faded out", kotlin.math.abs(tone.last()) < 0.05f)
    }

    @Test
    fun generatorIsDeterministic() {
        val a = TestTone.generate(TestTone.Kind.SWEEP, 48_000, durationSeconds = 0.25f)
        val b = TestTone.generate(TestTone.Kind.SWEEP, 48_000, durationSeconds = 0.25f)
        assertTrue(a.contentEquals(b))
    }
}
