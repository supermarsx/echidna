package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link EchidnaControls} is the facade the LSPosed UI calls, so it reaches the process singleton
 * and through it the native bridge. On a host with neither the Xposed runtime nor the JNI library
 * every one of these calls must answer with the fail-closed value rather than propagate the
 * missing-runtime failure into the caller.
 *
 * <p>Before diagnostics were routed through {@link ShimLog} this whole class was unreachable off
 * device: singleton construction threw {@code NoClassDefFoundError: de/robv/android/xposed/
 * XposedBridge} out of the library-load handler.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class EchidnaControlsHostFallbackTest {

    @Test
    public void anAbsentNativeEngineIsReportedRatherThanThrown() {
        assertFalse(NativeBridge.isNativeAvailable());
        assertFalse(NativeBridge.initialize());
        assertFalse(NativeBridge.isEngineReady());
        assertEquals(EchidnaStatus.DISABLED, NativeBridge.getStatus());

        // setBypass/setProfile are void and gated on initialize(); they must stay silent no-ops.
        NativeBridge.setBypass(true);
        NativeBridge.setProfile("{}");
    }

    @Test
    public void theFacadeAnswersWithFailClosedValuesInsteadOfPropagating() {
        assertEquals(EchidnaStatus.DISABLED, EchidnaControls.getNativeStatus());
        assertEquals("", EchidnaControls.getActiveProfile());
        // No process has attached, so there is nothing to refresh and nothing to authorise.
        assertFalse(EchidnaControls.refreshConfiguration());
    }

    @Test
    public void clearingTheUserFacingBypassToggleCannotWidenAnUnresolvedPolicy() {
        EchidnaControls.setBypassEnabled(true);
        assertTrue(EchidnaControls.isBypassEnabled());

        // The toggle only clears the *override*. With no valid snapshot the resolved config still
        // requests bypass, so the facade must keep reporting bypass rather than report the toggle
        // back to the user as if audio were now being processed.
        EchidnaControls.setBypassEnabled(false);
        assertTrue(EchidnaControls.isBypassEnabled());
    }
}
