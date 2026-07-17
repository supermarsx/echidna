package com.echidna.app.audio

import com.echidna.app.data.PresetSerializer
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * An in-process DSP transform over mono float audio. Abstracted behind an
 * interface so the Lab ViewModel can be unit-tested with a fake, while the
 * production path ([LabDspEngine]) drives the REAL native engine.
 */
interface DspProcessor {
    /** True while the underlying engine is live and can process. */
    val available: Boolean

    /**
     * Processes [frames] mono samples from [input] into [output] (distinct arrays,
     * each length >= [frames]). Returns true on success.
     */
    fun process(input: FloatArray, output: FloatArray, frames: Int): Boolean

    /** Releases native resources. Safe to call more than once. */
    fun close()
}

/** Factory for a [DspProcessor] given the audio format and a preset. Injectable for tests. */
fun interface DspProcessorFactory {
    fun create(sampleRate: Int, channels: Int, maxFrames: Int, preset: Preset): DspProcessor
}

/**
 * Production [DspProcessor] backed by one independent native `ech_dsp_engine`.
 *
 * The preset is serialized with the SAME [PresetSerializer.toJson] the rest of the
 * app uses to configure the engine — the native preset loader reads that exact
 * schema — so the Lab runs the real effect, not a Kotlin approximation.
 */
class LabDspEngine private constructor(
    private val handle: Long
) : DspProcessor {

    @Volatile
    private var closed = false

    /** Native engine handle for the alloc-free realtime direct path; 0 when unavailable/closed. */
    internal val nativeHandle: Long get() = if (closed) 0L else handle

    override val available: Boolean get() = !closed && handle != 0L

    override fun process(input: FloatArray, output: FloatArray, frames: Int): Boolean {
        if (closed || handle == 0L) return false
        return EchidnaLabDsp.nativeProcess(handle, input, output, frames) == EchidnaLabDsp.STATUS_OK
    }

    override fun close() {
        if (closed) return
        closed = true
        if (handle != 0L) EchidnaLabDsp.nativeDestroy(handle)
    }

    companion object : DspProcessorFactory {
        /** Maps the preset's latency mode to the DSP quality mode enum. */
        private fun qualityMode(mode: LatencyMode): Int = when (mode) {
            LatencyMode.LOW_LATENCY -> 0   // ECH_DSP_QUALITY_LOW_LATENCY
            LatencyMode.BALANCED -> 1      // ECH_DSP_QUALITY_BALANCED
            LatencyMode.HIGH_QUALITY -> 2  // ECH_DSP_QUALITY_HIGH
        }

        /**
         * Builds a live engine, or a closed (unavailable) instance when the native
         * engine is missing or rejects the preset — callers check [available].
         */
        override fun create(
            sampleRate: Int,
            channels: Int,
            maxFrames: Int,
            preset: Preset
        ): DspProcessor {
            if (!EchidnaLabDsp.engineAvailable()) return LabDspEngine(0L)
            val json = PresetSerializer.toJson(preset)
            val handle = runCatching {
                EchidnaLabDsp.nativeCreate(sampleRate, channels, qualityMode(preset.latencyMode), maxFrames, json)
            }.getOrDefault(0L)
            return LabDspEngine(handle)
        }
    }
}

/** A pair of matching direct float buffers for the alloc-free realtime path. */
class DirectFloatBuffers(maxFrames: Int) {
    val inputBytes: ByteBuffer =
        ByteBuffer.allocateDirect(maxFrames * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    val outputBytes: ByteBuffer =
        ByteBuffer.allocateDirect(maxFrames * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    val input: FloatBuffer = inputBytes.asFloatBuffer()
    val output: FloatBuffer = outputBytes.asFloatBuffer()
}
