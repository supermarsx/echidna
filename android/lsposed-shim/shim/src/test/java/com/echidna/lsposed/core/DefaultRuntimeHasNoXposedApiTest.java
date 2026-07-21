package com.echidna.lsposed.core;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Guards the property that several other tests in this source set silently depend on: the DEFAULT
 * unit-test runtime has no Xposed API on its classpath.
 *
 * <p>{@link EchidnaControlsHostFallbackTest} and the {@code NoClassDefFoundError} assertion in
 * {@link ProfileSyncReceiverCapabilityTest} exist to pin that the shim's fail-closed handlers no
 * longer detonate when Xposed is absent — a real bug, where the error handler itself called
 * {@code XposedBridge.log}. Those tests can only fail if the linkage error is genuinely reachable.
 * Put the API on this runtime and they would keep passing while guarding nothing at all.
 *
 * <p>The Xposed-requiring tests live on the second runtime and are named {@code *XposedRuntimeTest};
 * see {@code testDebugUnitTestXposed} in build.gradle.
 */
public final class DefaultRuntimeHasNoXposedApiTest {

    @Test
    public void theXposedApiIsNotResolvableOnThisRuntime() {
        for (String type : new String[] {
                "de.robv.android.xposed.XposedBridge",
                "de.robv.android.xposed.XposedHelpers",
                "de.robv.android.xposed.XC_MethodHook",
                "de.robv.android.xposed.IXposedHookLoadPackage"}) {
            try {
                Class.forName(type);
                fail("the default unit-test runtime must not resolve " + type
                        + "; adding it makes this module's Xposed-absent regression guards vacuous");
            } catch (ClassNotFoundException expected) {
                assertTrue(expected.getMessage().contains("robv"));
            }
        }
    }

    @Test
    public void theShimStillLogsWithoutXposedInsteadOfThrowing() {
        // ShimLog prefers XposedBridge and falls back to logcat. On this runtime the preferred path
        // raises NoClassDefFoundError, which is exactly the condition the fallback exists for.
        ShimLog.log("default-runtime diagnostic");
    }
}
