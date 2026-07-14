package com.echidna.control.bridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object EchidnaNative {
    private const val TAG = "EchidnaNative"
    private const val RESULT_NOT_AVAILABLE = -7
    private const val RESULT_INVALID_ARGUMENT = -2
    private const val STATUS_ERROR = 3
    private const val DEFAULT_API_VERSION: Long = (1L shl 16) or (1L shl 8)

    private val available = AtomicBoolean(true)

    init {
        try {
            System.loadLibrary("echidna_control_jni")
        } catch (error: UnsatisfiedLinkError) {
            markUnavailable("load library", error)
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
        if (profile.isBlank()) {
            return RESULT_INVALID_ARGUMENT
        }
        val result = callNative("set profile", RESULT_NOT_AVAILABLE) {
            nativeSetProfile(profile)
        }
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
        if (frames <= 0 || sampleRate <= 0 || channelCount <= 0) {
            return RESULT_INVALID_ARGUMENT
        }
        val requiredSamples = frames.toLong() * channelCount.toLong()
        if (requiredSamples <= 0L || requiredSamples > input.size.toLong()) {
            return RESULT_INVALID_ARGUMENT
        }
        if (output != null && requiredSamples > output.size.toLong()) {
            return RESULT_INVALID_ARGUMENT
        }
        val result = callNative("process block", RESULT_NOT_AVAILABLE) {
            nativeProcessBlock(input, output, frames, sampleRate, channelCount)
        }
        if (result == RESULT_NOT_AVAILABLE) {
            available.set(false)
        }
        return result
    }

    fun getStatus(): Int {
        if (!available.get()) {
            return STATUS_ERROR
        }
        return callNative("get status", STATUS_ERROR) {
            nativeGetStatus()
        }
    }

    fun getApiVersion(): Long {
        return if (available.get()) {
            callNative("get API version", DEFAULT_API_VERSION) {
                nativeGetApiVersion()
            }
        } else {
            DEFAULT_API_VERSION
        }
    }

    private inline fun <T> callNative(operation: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (error: UnsatisfiedLinkError) {
            markUnavailable(operation, error)
            fallback
        } catch (error: NoSuchMethodError) {
            markUnavailable(operation, error)
            fallback
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Native $operation failed; using fallback", exception)
            fallback
        }
    }

    private fun markUnavailable(operation: String, throwable: Throwable) {
        available.set(false)
        Log.w(TAG, "Native $operation unavailable; disabling JNI bridge", throwable)
    }
}
