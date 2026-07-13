package com.echidna.control.bridge

import java.util.concurrent.atomic.AtomicBoolean

internal object EchidnaNative {
    private const val RESULT_NOT_AVAILABLE = -7
    private const val STATUS_ERROR = 3
    private const val DEFAULT_API_VERSION: Long = (1L shl 16) or (1L shl 8)

    private val available = AtomicBoolean(true)

    init {
        try {
            System.loadLibrary("echidna_control_jni")
        } catch (_: UnsatisfiedLinkError) {
            available.set(false)
        }
    }

    external fun nativeSetProfile(profile: String): Int
    external fun nativeProcessBlock(
        input: FloatArray,
        output: FloatArray?,
        frames: Int,
        sampleRate: Int,
        channelCount: Int
    ): Int

    external fun nativeGetStatus(): Int
    external fun nativeGetApiVersion(): Long

    fun setProfile(profile: String): Int {
        if (!available.get()) {
            return RESULT_NOT_AVAILABLE
        }
        val result = nativeSetProfile(profile)
        if (result == RESULT_NOT_AVAILABLE) {
            available.set(false)
        }
        return result
    }

    fun processBlock(
        input: FloatArray,
        output: FloatArray?,
        frames: Int,
        sampleRate: Int,
        channelCount: Int
    ): Int {
        if (!available.get()) {
            return RESULT_NOT_AVAILABLE
        }
        val result = nativeProcessBlock(input, output, frames, sampleRate, channelCount)
        if (result == RESULT_NOT_AVAILABLE) {
            available.set(false)
        }
        return result
    }

    fun getStatus(): Int {
        if (!available.get()) {
            return STATUS_ERROR
        }
        return nativeGetStatus()
    }

    fun getApiVersion(): Long {
        return if (available.get()) {
            nativeGetApiVersion()
        } else {
            DEFAULT_API_VERSION
        }
    }
}
