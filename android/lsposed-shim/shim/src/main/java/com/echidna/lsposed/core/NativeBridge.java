package com.echidna.lsposed.core;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XposedBridge;

/**
 * Java facade for JNI bindings that forward captured audio blocks into the native engine.
 */
public final class NativeBridge {

    private static final String TAG = "EchidnaNativeBridge";
    private static final int MAX_PROCESS_BUFFER_BYTES = 8 * 1024 * 1024;

    private static final AtomicBoolean LIBRARY_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);
    private static final AtomicBoolean NATIVE_AVAILABLE = new AtomicBoolean(true);
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);

    private NativeBridge() {
    }

    public static boolean initialize() {
        if (!ensureLibraryLoaded()) {
            return false;
        }
        if (INITIALISED.compareAndSet(false, true)) {
            if (!safeNativeBoolean("initialise", NativeBridge::nativeInitialise)) {
                INITIALISED.set(false);
                return false;
            }
        }
        return INITIALISED.get();
    }

    public static boolean isEngineReady() {
        return initialize() && safeNativeBoolean("engine ready", NativeBridge::nativeIsEngineReady);
    }

    public static void setBypass(boolean bypass) {
        if (initialize()) {
            try {
                nativeSetBypass(bypass);
            } catch (Throwable throwable) {
                handleNativeFailure("set bypass", throwable);
            }
        }
    }

    public static void setProfile(String profile) {
        if (profile != null && initialize()) {
            try {
                nativeSetProfile(profile);
            } catch (Throwable throwable) {
                handleNativeFailure("set profile", throwable);
            }
        }
    }

    public static EchidnaStatus getStatus() {
        if (!initialize()) {
            return EchidnaStatus.DISABLED;
        }
        try {
            return EchidnaStatus.fromNativeCode(nativeGetStatus());
        } catch (Throwable throwable) {
            handleNativeFailure("get status", throwable);
            return EchidnaStatus.DISABLED;
        }
    }

    public static boolean isNativeAvailable() {
        return ensureLibraryLoaded();
    }

    public static boolean processByteArray(
            byte[] buffer, int offset, int length, int encoding, int sampleRate, int channelCount) {
        if (buffer == null
                || !isValidAudioRequest(byteCount(length), sampleRate, channelCount)
                || !isValidRange(buffer.length, offset, length)
                || !initialize()) {
            return false;
        }
        return safeNativeBoolean(
                "process byte[]",
                () -> nativeProcessByteArray(buffer, offset, length, encoding, sampleRate, channelCount));
    }

    public static boolean processShortArray(
            short[] buffer, int offset, int length, int sampleRate, int channelCount) {
        if (buffer == null
                || !isValidAudioRequest(byteCount(length, Short.BYTES), sampleRate, channelCount)
                || !isValidRange(buffer.length, offset, length)
                || !initialize()) {
            return false;
        }
        return safeNativeBoolean(
                "process short[]",
                () -> nativeProcessShortArray(buffer, offset, length, sampleRate, channelCount));
    }

    public static boolean processFloatArray(
            float[] buffer, int offset, int length, int sampleRate, int channelCount) {
        if (buffer == null
                || !isValidAudioRequest(byteCount(length, Float.BYTES), sampleRate, channelCount)
                || !isValidRange(buffer.length, offset, length)
                || !initialize()) {
            return false;
        }
        return safeNativeBoolean(
                "process float[]",
                () -> nativeProcessFloatArray(buffer, offset, length, sampleRate, channelCount));
    }

    public static boolean processByteBuffer(
            ByteBuffer buffer, int position, int length, int encoding, int sampleRate, int channelCount) {
        if (buffer == null
                || !ByteBufferProcessor.isWritableRange(buffer, position, length)
                || !isValidAudioRequest(byteCount(length), sampleRate, channelCount)
                || !initialize()) {
            return false;
        }
        if (!buffer.isDirect()) {
            return ByteBufferProcessor.processHeapRegion(
                    buffer,
                    position,
                    length,
                    scratch -> safeNativeBoolean(
                            "process heap ByteBuffer",
                            () -> nativeProcessByteArray(
                                    scratch, 0, length, encoding, sampleRate, channelCount)));
        }
        return safeNativeBoolean(
                "process direct ByteBuffer",
                () -> nativeProcessByteBuffer(buffer, position, length, encoding, sampleRate, channelCount));
    }

    /**
     * Processes bytes returned by AudioRecord's direct-ByteBuffer overload. Android writes from
     * index zero and leaves the caller's position/limit unchanged, so native receives a duplicate
     * capacity-bounded view without mutating the app-owned cursor state.
     */
    public static boolean processAudioRecordByteBuffer(
            ByteBuffer buffer, int length, int encoding, int sampleRate, int channelCount) {
        ByteBuffer view = ByteBufferProcessor.audioRecordView(buffer, length);
        return view != null
                && processByteBuffer(view, 0, length, encoding, sampleRate, channelCount);
    }

    private static boolean ensureLibraryLoaded() {
        if (!NATIVE_AVAILABLE.get()) {
            return false;
        }
        if (LIBRARY_LOADED.get()) {
            return true;
        }
        try {
            System.loadLibrary("echidna_shim_jni");
            LIBRARY_LOADED.set(true);
        } catch (Throwable throwable) {
            NATIVE_AVAILABLE.set(false);
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                XposedBridge.log(
                        TAG + ": failed to load native bridge: " + Log.getStackTraceString(throwable));
            }
            LIBRARY_LOADED.set(false);
        }
        return LIBRARY_LOADED.get();
    }

    private static boolean isValidAudioRequest(long byteCount, int sampleRate, int channelCount) {
        return byteCount > 0
                && byteCount <= MAX_PROCESS_BUFFER_BYTES
                && sampleRate > 0
                && channelCount > 0
                && channelCount <= 8;
    }

    private static boolean isValidRange(int containerLength, int offset, int length) {
        return containerLength >= 0
                && offset >= 0
                && length > 0
                && (long) offset + (long) length <= (long) containerLength;
    }

    private static long byteCount(int units) {
        return byteCount(units, 1);
    }

    private static long byteCount(int units, int bytesPerUnit) {
        if (units <= 0 || bytesPerUnit <= 0) {
            return -1L;
        }
        return (long) units * (long) bytesPerUnit;
    }

    private static boolean safeNativeBoolean(String operation, BooleanSupplier call) {
        try {
            return call.getAsBoolean();
        } catch (Throwable throwable) {
            handleNativeFailure(operation, throwable);
            return false;
        }
    }

    private static void handleNativeFailure(String operation, Throwable throwable) {
        if (throwable instanceof LinkageError) {
            NATIVE_AVAILABLE.set(false);
            LIBRARY_LOADED.set(false);
            INITIALISED.set(false);
        }
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log(
                    TAG + ": native " + operation + " failed; disabling bridge: "
                            + Log.getStackTraceString(throwable));
        }
    }

    private static native boolean nativeInitialise();

    private static native boolean nativeIsEngineReady();

    private static native void nativeSetBypass(boolean bypass);

    private static native void nativeSetProfile(String profile);

    private static native int nativeGetStatus();

    private static native boolean nativeProcessByteArray(
            byte[] buffer, int offset, int length, int encoding, int sampleRate, int channelCount);

    private static native boolean nativeProcessShortArray(
            short[] buffer, int offset, int length, int sampleRate, int channelCount);

    private static native boolean nativeProcessFloatArray(
            float[] buffer, int offset, int length, int sampleRate, int channelCount);

    private static native boolean nativeProcessByteBuffer(
            ByteBuffer buffer, int position, int length, int encoding, int sampleRate, int channelCount);
}
