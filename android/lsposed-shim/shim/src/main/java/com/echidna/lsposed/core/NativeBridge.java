package com.echidna.lsposed.core;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * Java facade for JNI bindings that forward captured audio blocks into the native engine.
 */
public final class NativeBridge {

    private static final String TAG = "EchidnaNativeBridge";

    private static final AtomicBoolean LIBRARY_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);

    private NativeBridge() {
    }

    public static boolean initialize() {
        if (!ensureLibraryLoaded()) {
            return false;
        }
        if (INITIALISED.compareAndSet(false, true)) {
            if (!nativeInitialise()) {
                INITIALISED.set(false);
                return false;
            }
        }
        return INITIALISED.get();
    }

    public static boolean isEngineReady() {
        return initialize() && nativeIsEngineReady();
    }

    public static void setBypass(boolean bypass) {
        if (initialize()) {
            nativeSetBypass(bypass);
        }
    }

    public static void setProfile(String profile) {
        if (initialize()) {
            nativeSetProfile(profile);
        }
    }

    public static EchidnaStatus getStatus() {
        if (!initialize()) {
            return EchidnaStatus.DISABLED;
        }
        return EchidnaStatus.fromNativeCode(nativeGetStatus());
    }

    public static boolean isNativeAvailable() {
        return ensureLibraryLoaded();
    }

    public static boolean processByteArray(
            byte[] buffer, int offset, int length, int encoding, int sampleRate, int channelCount) {
        if (!initialize()) {
            return false;
        }
        return nativeProcessByteArray(buffer, offset, length, encoding, sampleRate, channelCount);
    }

    public static boolean processShortArray(
            short[] buffer, int offset, int length, int sampleRate, int channelCount) {
        if (!initialize()) {
            return false;
        }
        return nativeProcessShortArray(buffer, offset, length, sampleRate, channelCount);
    }

    public static boolean processFloatArray(
            float[] buffer, int offset, int length, int sampleRate, int channelCount) {
        if (!initialize()) {
            return false;
        }
        return nativeProcessFloatArray(buffer, offset, length, sampleRate, channelCount);
    }

    public static boolean processByteBuffer(
            ByteBuffer buffer, int position, int length, int encoding, int sampleRate, int channelCount) {
        if (!initialize()) {
            return false;
        }
        if (!buffer.isDirect()) {
            byte[] scratch = new byte[length];
            int originalPosition = buffer.position();
            buffer.position(position);
            buffer.get(scratch, 0, length);
            buffer.position(originalPosition);
            return nativeProcessByteArray(scratch, 0, length, encoding, sampleRate, channelCount);
        }
        return nativeProcessByteBuffer(buffer, position, length, encoding, sampleRate, channelCount);
    }

    private static boolean ensureLibraryLoaded() {
        if (LIBRARY_LOADED.get()) {
            return true;
        }
        try {
            System.loadLibrary("echidna");
            LIBRARY_LOADED.set(true);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": failed to load native bridge: " + Log.getStackTraceString(throwable));
            LIBRARY_LOADED.set(false);
        }
        return LIBRARY_LOADED.get();
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
