package com.echidna.app.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure, allocation-light signal measurements shared by the Lab meter, the waveform
 * views, and the A/B comparison. Kept framework-free so they are JVM unit-testable.
 */
object AudioAnalysis {

    /** Silence floor in dBFS reported when a buffer is empty or all-zero. */
    const val SILENCE_DBFS = -120.0f

    /** Peak absolute sample over the first [count] samples (defaults to all). */
    fun peak(samples: FloatArray, count: Int = samples.size): Float {
        var peak = 0f
        val n = count.coerceIn(0, samples.size)
        for (i in 0 until n) {
            val a = abs(samples[i])
            if (a > peak) peak = a
        }
        return peak
    }

    /** Root-mean-square amplitude over the first [count] samples. */
    fun rms(samples: FloatArray, count: Int = samples.size): Float {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = samples[i]
            sum += s.toDouble() * s.toDouble()
        }
        return sqrt(sum / n).toFloat()
    }

    /** Converts a linear amplitude (0..1) to dBFS, floored at [SILENCE_DBFS]. */
    fun toDbfs(amplitude: Float): Float {
        if (amplitude <= 0f) return SILENCE_DBFS
        return max(SILENCE_DBFS, (20.0 * log10(amplitude.toDouble())).toFloat())
    }

    /** RMS level of the first [count] samples in dBFS. */
    fun rmsDbfs(samples: FloatArray, count: Int = samples.size): Float = toDbfs(rms(samples, count))

    /**
     * Downsamples [samples] to at most [buckets] peak-magnitude points for a compact
     * waveform. Each output point is the max absolute value of its slice, so transients
     * remain visible. Returns an empty array for empty input.
     */
    fun waveform(samples: FloatArray, buckets: Int): FloatArray {
        if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
        if (samples.size <= buckets) return FloatArray(samples.size) { abs(samples[it]) }
        val out = FloatArray(buckets)
        val per = samples.size.toDouble() / buckets
        for (b in 0 until buckets) {
            val start = (b * per).toInt()
            val end = minOf(samples.size, ((b + 1) * per).toInt().coerceAtLeast(start + 1))
            var peak = 0f
            for (i in start until end) {
                val a = abs(samples[i])
                if (a > peak) peak = a
            }
            out[b] = peak
        }
        return out
    }
}
