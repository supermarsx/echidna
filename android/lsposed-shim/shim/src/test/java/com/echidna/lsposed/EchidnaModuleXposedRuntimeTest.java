package com.echidna.lsposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.echidna.lsposed.core.TestModuleStates;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The {@code assets/xposed_init} entry point. Runs on the Xposed-enabled unit-test runtime, since
 * this class implements {@code IXposedHookLoadPackage} and cannot be linked without the API.
 *
 * <p>The contract that matters is that a cold process gets its hooks installed even though policy
 * has not arrived yet — the hooks are inert until it does — and that the module says out loud why
 * it is inert, because LSPosed reports it as "active" either way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class EchidnaModuleXposedRuntimeTest {

    @Before
    public void reset() throws Exception {
        resetRecordings();
        resetAudioRecordHook();
        TestModuleStates.resetStore();
    }

    @After
    public void cleanUp() throws Exception {
        shutdownStoreReceiver();
        TestModuleStates.resetStore();
        resetAudioRecordHook();
        resetRecordings();
    }

    @Test
    public void aColdProcessIsHookedImmediatelyAndToldWhyItIsInert() throws Throwable {
        new EchidnaModule().handleLoadPackage(
                loadPackageParam("com.example.recorder", "com.example.recorder"));

        // Policy arrives asynchronously over Binder. Waiting for it before hooking would miss the
        // process's only installation opportunity, so the hooks must already be in place.
        assertEquals(7, readHookCount());

        // ...and because they are installed but will not transform anything yet, that has to be
        // stated rather than left looking like a working module.
        assertTrue(loggedContaining("hooks installed but INERT for com.example.recorder"));
        assertTrue(loggedContaining("POLICY_UNAVAILABLE"));
    }

    @Test
    public void aSecondPackageLoadInTheSameProcessDoesNotStackASecondSetOfHooks()
            throws Throwable {
        EchidnaModule module = new EchidnaModule();
        module.handleLoadPackage(
                loadPackageParam("com.example.recorder", "com.example.recorder"));
        int afterFirst = readHookCount();

        // LSPosed can deliver more than one loadPackage to a process. Installing twice would put
        // two callbacks on every read and process each captured buffer twice.
        module.handleLoadPackage(
                loadPackageParam("com.example.recorder", "com.example.recorder:remote"));

        assertEquals(afterFirst, readHookCount());
        assertEquals(7, readHookCount());
    }

    private static XC_LoadPackage.LoadPackageParam loadPackageParam(
            String packageName, String processName) throws Exception {
        Constructor<?> constructor =
                XC_LoadPackage.LoadPackageParam.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        XC_LoadPackage.LoadPackageParam param =
                (XC_LoadPackage.LoadPackageParam) constructor.newInstance();
        param.packageName = packageName;
        param.processName = processName;
        param.classLoader = EchidnaModuleXposedRuntimeTest.class.getClassLoader();
        return param;
    }

    private static int readHookCount() throws Exception {
        Class<?> helpers = Class.forName("de.robv.android.xposed.XposedHelpers");
        return ((List<?>) helpers.getField("RECORDED_METHOD_HOOKS").get(null)).size();
    }

    @SuppressWarnings("unchecked")
    private static boolean loggedContaining(String fragment) throws Exception {
        Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
        List<String> logs = (List<String>) bridge.getField("RECORDED_LOGS").get(null);
        synchronized (logs) {
            for (String line : logs) {
                if (line != null && line.contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void resetRecordings() throws Exception {
        Class.forName("de.robv.android.xposed.XposedBridge")
                .getMethod("resetRecordingForTests").invoke(null);
        Class.forName("de.robv.android.xposed.XposedHelpers")
                .getMethod("resetRecordingForTests").invoke(null);
    }

    private static void resetAudioRecordHook() throws Exception {
        Class<?> hook = Class.forName("com.echidna.lsposed.hooks.AudioRecordHook");
        set(hook, "sessionManager", null);
        ((java.util.concurrent.atomic.AtomicReference<?>) get(hook, "PENDING_DRAIN")).set(null);
        ((java.util.concurrent.atomic.AtomicBoolean) get(hook, "INSTALLED")).set(false);
        ((java.util.concurrent.atomic.AtomicBoolean) get(hook, "HOOK_FAILURE_LOGGED")).set(false);
    }

    private static Object get(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void set(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** handleLoadPackage attaches the real process identity, which starts the policy client. */
    private static void shutdownStoreReceiver() throws Exception {
        Class<?> storeType = Class.forName("com.echidna.lsposed.core.ProfileSnapshotStore");
        Object store = storeType.getMethod("getInstance").invoke(null);
        Field receiverField = storeType.getDeclaredField("receiver");
        receiverField.setAccessible(true);
        Object receiver = receiverField.get(store);
        if (receiver == null) {
            return;
        }
        Field executor = receiver.getClass().getDeclaredField("executor");
        executor.setAccessible(true);
        ((ScheduledExecutorService) executor.get(receiver)).shutdownNow();
    }
}
