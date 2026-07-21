package com.echidna.lsposed.hooks;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reflective access to {@link AudioRecordHook}'s process-wide installer state.
 *
 * <p>The class holds static latches by design — one install per process — so tests must be able to
 * return it to a cold state between cases. Usable only from the *XposedRuntimeTest classes: on the
 * default Xposed-free runtime AudioRecordHook cannot be linked at all.
 */
final class AudioRecordHookStatics {

    private AudioRecordHookStatics() {
    }

    static void reset() throws Exception {
        pendingDrainRef().set(null);
        field("sessionManager").set(null, null);
        flag("INSTALLED").set(false);
        flag("HOOK_FAILURE_LOGGED").set(false);
    }

    static boolean installed() throws Exception {
        return flag("INSTALLED").get();
    }

    static void installSessionManager(Object manager) throws Exception {
        field("sessionManager").set(null, manager);
    }

    static Object sessionManager() throws Exception {
        return field("sessionManager").get(null);
    }

    static Object pendingDrain() throws Exception {
        return pendingDrainRef().get();
    }

    static long pendingDrainGeneration() throws Exception {
        return pendingLong("generation");
    }

    static Object pendingDrainCallback() throws Exception {
        Object pending = pendingDrain();
        Field callback = pending.getClass().getDeclaredField("callback");
        callback.setAccessible(true);
        return callback.get(pending);
    }

    private static long pendingLong(String name) throws Exception {
        Object pending = pendingDrain();
        Field value = pending.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.getLong(pending);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> pendingDrainRef() throws Exception {
        return (AtomicReference<Object>) field("PENDING_DRAIN").get(null);
    }

    private static AtomicBoolean flag(String name) throws Exception {
        return (AtomicBoolean) field(name).get(null);
    }

    private static Field field(String name) throws Exception {
        Field field = AudioRecordHook.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
