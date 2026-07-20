package com.echidna.app.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math contract for the tuner readout: pitch -> note name, nearest equal-tempered target,
 * and the cents deviation the Diagnostics tuner needle is drawn from. No Android surface is
 * touched, so this runs on the plain JVM runner.
 */
class NoteUtilsTest {

    @Test
    fun `A440 is the anchor of the equal-tempered grid`() {
        assertEquals("A", NoteUtils.frequencyToNoteName(440f))
        assertEquals(440f, NoteUtils.midiTargetFrequency(440f), 0.01f)
        assertEquals(0f, NoteUtils.centsOff(440f, 440f), 0.01f)
    }

    @Test
    fun `note names follow the chromatic scale across octaves`() {
        // Same pitch class one and two octaves down must resolve to the same name.
        assertEquals("A", NoteUtils.frequencyToNoteName(220f))
        assertEquals("A", NoteUtils.frequencyToNoteName(110f))
        assertEquals("C", NoteUtils.frequencyToNoteName(261.626f))
        assertEquals("E", NoteUtils.frequencyToNoteName(329.628f))
        assertEquals("G♯", NoteUtils.frequencyToNoteName(415.305f))
    }

    @Test
    fun `pitch classes below the A440 reference index without wrapping negative`() {
        // 27.5 Hz is A0 — the modulo arithmetic must not fall off the array for sub-A440 MIDI
        // numbers, and 16.35 Hz (C0) sits below the reference by more than three octaves.
        assertEquals("A", NoteUtils.frequencyToNoteName(27.5f))
        assertEquals("C", NoteUtils.frequencyToNoteName(16.352f))
    }

    @Test
    fun `non-positive frequency reports no note rather than a fabricated one`() {
        assertEquals("—", NoteUtils.frequencyToNoteName(0f))
        assertEquals("—", NoteUtils.frequencyToNoteName(-100f))
    }

    @Test
    fun `centsOff is logarithmic and signed`() {
        // An octave up is exactly +1200 cents, an octave down exactly -1200.
        assertEquals(1200f, NoteUtils.centsOff(880f, 440f), 0.01f)
        assertEquals(-1200f, NoteUtils.centsOff(220f, 440f), 0.01f)
        // A semitone is 100 cents.
        assertEquals(100f, NoteUtils.centsOff(466.164f, 440f), 0.5f)
        // Sharp of target reads positive, flat reads negative.
        assertTrue(NoteUtils.centsOff(445f, 440f) > 0f)
        assertTrue(NoteUtils.centsOff(435f, 440f) < 0f)
    }

    @Test
    fun `centsOff returns zero for unusable inputs instead of infinity`() {
        assertEquals(0f, NoteUtils.centsOff(0f, 440f), 0f)
        assertEquals(0f, NoteUtils.centsOff(440f, 0f), 0f)
        assertEquals(0f, NoteUtils.centsOff(-1f, -1f), 0f)
    }

    @Test
    fun `midiTargetFrequency snaps to the nearest semitone`() {
        // Slightly sharp of A440 still targets A440; a full semitone up targets A#4.
        assertEquals(440f, NoteUtils.midiTargetFrequency(445f), 0.01f)
        assertEquals(440f, NoteUtils.midiTargetFrequency(435f), 0.01f)
        assertEquals(466.164f, NoteUtils.midiTargetFrequency(466f), 0.05f)
        assertEquals(261.626f, NoteUtils.midiTargetFrequency(262f), 0.05f)
    }

    @Test
    fun `snapped target is always within half a semitone of the input`() {
        // The tuner needle is |centsOff(freq, target)| — it must never exceed 50 cents.
        var hz = 80f
        while (hz < 1000f) {
            val cents = NoteUtils.centsOff(hz, NoteUtils.midiTargetFrequency(hz))
            assertTrue("$hz Hz deviated $cents cents from its snapped target", kotlin.math.abs(cents) <= 50.5f)
            hz += 3.7f
        }
    }
}
