package com.echidna.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnalysisTest {

    @Test
    fun peakAndRmsOfSilenceAreZeroAndFloored() {
        val silence = FloatArray(1_000)
        assertEquals(0f, AudioAnalysis.peak(silence), 0f)
        assertEquals(0f, AudioAnalysis.rms(silence), 0f)
        assertEquals(AudioAnalysis.SILENCE_DBFS, AudioAnalysis.rmsDbfs(silence), 0f)
    }

    @Test
    fun fullScaleIsZeroDbfs() {
        val full = FloatArray(100) { if (it % 2 == 0) 1f else -1f }
        assertEquals(1f, AudioAnalysis.peak(full), 1e-6f)
        assertEquals(0f, AudioAnalysis.rmsDbfs(full), 0.01f)
    }

    @Test
    fun halfAmplitudeIsAboutMinusSixDb() {
        val half = FloatArray(100) { if (it % 2 == 0) 0.5f else -0.5f }
        assertEquals(-6.02f, AudioAnalysis.toDbfs(AudioAnalysis.rms(half)), 0.1f)
    }

    @Test
    fun waveformDownsamplesToRequestedBucketsAndKeepsPeaks() {
        val samples = FloatArray(10_000) { (it % 100) / 100f }
        val wave = AudioAnalysis.waveform(samples, 200)
        assertEquals(200, wave.size)
        assertTrue("keeps transient peaks", wave.max() > 0.9f)
    }

    @Test
    fun waveformShorterThanBucketsReturnsPerSample() {
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f)
        val wave = AudioAnalysis.waveform(samples, 200)
        assertEquals(3, wave.size)
        assertEquals(0.2f, wave[1], 1e-6f)
    }
}
