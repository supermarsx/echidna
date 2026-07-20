package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ShimLogTest {

    @Before
    public void setUp() {
        ShadowLog.clear();
    }

    @Test
    public void missingXposedRuntimeFallsBackToLogcatInsteadOfPropagating() {
        // de.robv.android.xposed is compileOnly, so this host has no XposedBridge at all -- exactly
        // the non-Xposed-host case the fallback exists for.
        ShimLog.log("policy resolution failed");

        List<ShadowLog.LogItem> logs = ShadowLog.getLogs();
        assertEquals(1, logs.size());
        assertEquals("Echidna", logs.get(0).tag);
        assertEquals("policy resolution failed", logs.get(0).msg);
        assertEquals(Log.WARN, logs.get(0).type);
    }

    @Test
    public void nullMessageIsDroppedRatherThanLoggedOrThrown() {
        ShimLog.log(null);

        assertTrue(ShadowLog.getLogs().isEmpty());
    }

    @Test
    public void everyCallEmitsExactlyOneLineWithoutSuppressingRepeats() {
        ShimLog.log("first");
        ShimLog.log("first");
        ShimLog.log("second");

        List<ShadowLog.LogItem> logs = ShadowLog.getLogs();
        assertEquals(3, logs.size());
        assertEquals("first", logs.get(0).msg);
        assertEquals("first", logs.get(1).msg);
        assertEquals("second", logs.get(2).msg);
    }
}
