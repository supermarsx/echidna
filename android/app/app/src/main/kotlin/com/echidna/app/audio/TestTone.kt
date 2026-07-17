package com.echidna.app.audio

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Deterministic known-signal generators for the Lab's "test tone" path. Running a
 * generated tone through a preset gives a repeatable before/after that verifies
 * the direction of pitch/gain changes without needing a real microphone — handy
 * on emulators whose mic input is silent or synthetic.
 */
object TestTone {

    enum class Kind(val label: String) {
        SINE_440("Sine 440 Hz (A4)"),
        SINE_220("Sine 220 Hz (A3)"),
        SWEEP("Sweep 200 Hz -> 2 kHz")
    }

    /**
     * Generates [durationSeconds] of mono float audio at [sampleRate] for [kind],
     * scaled to [amplitude] (0..1). A short raised-cosine fade in/out avoids clicks.
     */
    fun generate(
        kind: Kind,
        sampleRate: Int,
        durationSeconds: Float = 2.0f,
        amplitude: Float = 0.6f
    ): FloatArray {
        val total = max(1, (sampleRate * durationSeconds).toInt())
        val amp = amplitude.coerceIn(0f, 1f)
        val out = FloatArray(total)
        when (kind) {
            Kind.SINE_440 -> fillSine(out, sampleRate, 440.0, amp)
            Kind.SINE_220 -> fillSine(out, sampleRate, 220.0, amp)
            Kind.SWEEP -> fillSweep(out, sampleRate, 200.0, 2000.0, amp)
        }
        applyFade(out, sampleRate)
        return out
    }

    private fun fillSine(out: FloatArray, sampleRate: Int, freq: Double, amp: Float) {
        val step = 2.0 * PI * freq / sampleRate
        var phase = 0.0
        for (i in out.indices) {
            out[i] = (amp * sin(phase)).toFloat()
            phase += step
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
    }

    // Linear frequency sweep via instantaneous-phase integration.
    private fun fillSweep(out: FloatArray, sampleRate: Int, startHz: Double, endHz: Double, amp: Float) {
        val n = out.size
        var phase = 0.0
        for (i in out.indices) {
            val t = if (n > 1) i.toDouble() / (n - 1) else 0.0
            val freq = startHz + (endHz - startHz) * t
            phase += 2.0 * PI * freq / sampleRate
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (amp * sin(phase)).toFloat()
        }
    }

    // 5 ms raised-cosine fade in and out to prevent boundary clicks.
    private fun applyFade(out: FloatArray, sampleRate: Int) {
        val fade = min(out.size / 2, (sampleRate * 0.005).toInt())
        if (fade <= 0) return
        for (i in 0 until fade) {
            val g = (0.5 - 0.5 * kotlin.math.cos(PI * i / fade)).toFloat()
            out[i] *= g
            out[out.size - 1 - i] *= g
        }
    }
}
