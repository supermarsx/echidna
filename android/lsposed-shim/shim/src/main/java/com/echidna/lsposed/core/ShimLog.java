package com.echidna.lsposed.core;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Single logging seam for the shim.
 *
 * <p>The shim's established mechanism is {@link XposedBridge#log(String)}, which LSPosed mirrors
 * into {@code logcat}. That class is provided by the Xposed runtime only, so it is absent on a
 * plain host JVM (unit tests) and on any host that loaded this APK without Xposed. Falling back to
 * {@link Log} keeps a diagnostic reachable in both cases instead of throwing where a caller least
 * expects it.
 */
public final class ShimLog {

    private static final String FALLBACK_TAG = "Echidna";

    private ShimLog() {
    }

    /** Emits one diagnostic line, preferring the Xposed log and falling back to logcat. */
    public static void log(String message) {
        if (message == null) {
            return;
        }
        try {
            XposedBridge.log(message);
            return;
        } catch (Throwable ignored) {
            // Xposed API unavailable (host JVM test / non-Xposed host); fall through to logcat.
        }
        try {
            Log.w(FALLBACK_TAG, message);
        } catch (Throwable ignored) {
            // Logging must never be able to break a caller.
        }
    }
}
