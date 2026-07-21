package de.robv.android.xposed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime stand-in for the Xposed API type of the same name. TEST RUNTIME ONLY.
 *
 * <p>Doubles as a recording dispatcher: hook installation is captured rather than performed, so a
 * test can retrieve the {@link XC_MethodHook} the shim registered and invoke its callbacks. That
 * is the only way to reach the read and lifecycle callbacks without a device.
 */
public final class XposedBridge {

    /** Diagnostics the shim emitted through the Xposed-preferred log path. */
    public static final List<String> RECORDED_LOGS =
            Collections.synchronizedList(new ArrayList<>());

    /** One entry per installed lifecycle hook: {declaringClass, methodName, callback}. */
    public static final List<Object[]> RECORDED_HOOKS =
            Collections.synchronizedList(new ArrayList<>());

    private XposedBridge() {
    }

    public static synchronized void log(String text) {
        RECORDED_LOGS.add(text);
    }

    public static synchronized void log(Throwable throwable) {
        RECORDED_LOGS.add(String.valueOf(throwable));
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        RECORDED_HOOKS.add(new Object[] {hookClass, "<init>", callback});
        return new LinkedHashSet<>();
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        RECORDED_HOOKS.add(new Object[] {hookClass, methodName, callback});
        return new LinkedHashSet<>();
    }

    /** Clears recorded state between tests. Not part of the published API. */
    public static void resetRecordingForTests() {
        RECORDED_LOGS.clear();
        RECORDED_HOOKS.clear();
    }

    /** Returns the callback installed for {@code methodName}, or null. Not published API. */
    public static XC_MethodHook recordedHook(String methodName) {
        synchronized (RECORDED_HOOKS) {
            for (Object[] entry : RECORDED_HOOKS) {
                if (methodName.equals(entry[1])) {
                    return (XC_MethodHook) entry[2];
                }
            }
        }
        return null;
    }
}
