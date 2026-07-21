package com.echidna.lsposed.hooks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the recording state of the Xposed runtime stand-in (src/testXposedRuntime/java).
 *
 * <p>Reflective because these members do not exist on the published de.robv.android.xposed:api:82
 * artifact, which is what the tests compile against. Usable only from the *XposedRuntimeTest
 * classes, which run on the runtime where the stand-in is present.
 */
final class XposedRuntimeRecordings {

    private XposedRuntimeRecordings() {
    }

    static void reset() throws Exception {
        bridge().getMethod("resetRecordingForTests").invoke(null);
        helpers().getMethod("resetRecordingForTests").invoke(null);
    }

    static void declareMissing(String signature) throws Exception {
        @SuppressWarnings("unchecked")
        java.util.Set<String> missing =
                (java.util.Set<String>) helpers().getField("MISSING_SIGNATURES").get(null);
        missing.add(signature);
    }

    static List<String> lifecycleMethods() throws Exception {
        List<String> methods = new ArrayList<>();
        for (Object[] entry : recordedHooks()) {
            methods.add((String) entry[1]);
        }
        return methods;
    }

    static int readHookCount() throws Exception {
        return recordedMethodHooks().size();
    }

    static Object readHook(String signature) throws Exception {
        return helpers().getMethod("recordedMethodHook", String.class).invoke(null, signature);
    }

    static Object lifecycleHook(String methodName) throws Exception {
        return bridge().getMethod("recordedHook", String.class).invoke(null, methodName);
    }

    static String readHookSimpleName(String signature) throws Exception {
        Object hook = readHook(signature);
        if (hook == null) {
            return null;
        }
        String name = hook.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    static boolean loggedContaining(String fragment) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> logs = (List<String>) bridge().getField("RECORDED_LOGS").get(null);
        synchronized (logs) {
            for (String line : logs) {
                if (line != null && line.contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> recordedHooks() throws Exception {
        return new ArrayList<>((List<Object[]>) bridge().getField("RECORDED_HOOKS").get(null));
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> recordedMethodHooks() throws Exception {
        return new ArrayList<>(
                (List<Object[]>) helpers().getField("RECORDED_METHOD_HOOKS").get(null));
    }

    private static Class<?> bridge() throws Exception {
        return Class.forName("de.robv.android.xposed.XposedBridge");
    }

    private static Class<?> helpers() throws Exception {
        return Class.forName("de.robv.android.xposed.XposedHelpers");
    }
}
