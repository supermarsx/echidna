package com.echidna.app.audio

import android.util.Log
import java.nio.ByteBuffer

/**
 * JNI surface for the in-process Lab DSP bridge (libechidna_lab_jni.so).
 *
 * The bridge dlopens the self-contained DSP engine (libech_dsp.so) and runs the
 * app's OWN audio (recorded mic / generated test tone) through an independent
 * [ech_dsp_engine]. This is NOT the Zygisk interception path: there is no root,
 * no hook, and no other app's audio. It only demonstrates that the DSP transform
 * itself works.
 *
 * All entry points are null/absence tolerant: on a "lite" build produced without
 * the native artifacts, [bridgeLoaded]/[engineAvailable] report false and callers
 * fall back to honest "engine unavailable" states rather than crashing.
 */
internal object EchidnaLabDsp {
    private const val TAG = "EchidnaLabDsp"

    /** ech_dsp_status_t.ECH_DSP_STATUS_OK */
    const val STATUS_OK = 0

    private val bridgeLoadedInternal: Boolean = try {
        System.loadLibrary("echidna_lab_jni")
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.w(TAG, "echidna_lab_jni bridge not available", error)
        false
    }

    /** True when the JNI bridge library itself loaded. */
    val bridgeLoaded: Boolean get() = bridgeLoadedInternal

    /** True when the bridge loaded AND libech_dsp.so resolved its engine symbols. */
    fun engineAvailable(): Boolean =
        bridgeLoadedInternal && runCatching { nativeAvailable() }.getOrDefault(false)

    /** Packed DSP ABI version, or 0 when the engine is unavailable. */
    fun apiVersion(): Long =
        if (!bridgeLoadedInternal) 0L else runCatching { nativeApiVersion() }.getOrDefault(0L)

    external fun nativeAvailable(): Boolean
    external fun nativeApiVersion(): Long

    /** Lifecycle op (may allocate). Returns an opaque handle (>0) or 0 on failure. */
    external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        qualityMode: Int,
        maxFrames: Int,
        presetJson: String?
    ): Long

    /** Offline processing. input/output are distinct arrays of length >= frames. */
    external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray, frames: Int): Int

    /** Realtime processing over preallocated DIRECT byte buffers (zero-copy, alloc-free). */
    external fun nativeProcessDirect(
        handle: Long,
        input: ByteBuffer,
        output: ByteBuffer,
        frames: Int
    ): Int

    external fun nativeDestroy(handle: Long)
}
