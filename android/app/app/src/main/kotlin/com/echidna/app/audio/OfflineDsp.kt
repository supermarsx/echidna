package com.echidna.app.audio

import java.util.Arrays

/**
 * Runs a whole captured/generated mono buffer through a [DspProcessor] in fixed
 * chunks — the same block-at-a-time contract the realtime path uses, so a stateful
 * effect (pitch/formant) behaves identically. Backs "process + A/B" and "test tone".
 */
object OfflineDsp {

    /**
     * Processes [input] through [processor] in [chunkFrames] blocks, returning the wet
     * result (same length as input) or null when the processor is unavailable or fails.
     *
     * [chunkFrames] must not exceed the maxFrames the engine was created with.
     */
    fun process(
        processor: DspProcessor,
        input: FloatArray,
        chunkFrames: Int = LabAudioFormat.PROCESS_CHUNK_FRAMES
    ): FloatArray? {
        if (!processor.available || input.isEmpty() || chunkFrames <= 0) return null
        val out = FloatArray(input.size)
        val inBuf = FloatArray(chunkFrames)
        val outBuf = FloatArray(chunkFrames)
        var offset = 0
        while (offset < input.size) {
            val n = minOf(chunkFrames, input.size - offset)
            System.arraycopy(input, offset, inBuf, 0, n)
            if (n < chunkFrames) Arrays.fill(inBuf, n, chunkFrames, 0f)
            if (!processor.process(inBuf, outBuf, n)) return null
            System.arraycopy(outBuf, 0, out, offset, n)
            offset += n
        }
        return out
    }
}
