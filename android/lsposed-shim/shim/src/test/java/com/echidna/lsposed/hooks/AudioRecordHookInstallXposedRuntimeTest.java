package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.echidna.lsposed.core.ModuleState;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Hook installation, which is the shim's only opportunity to attach to a cold process.
 *
 * <p>Runs on the second unit-test runtime (see {@code testDebugUnitTestXposed} in build.gradle):
 * {@code AudioRecordHook} cannot be LINKED without the Xposed API, so on the default Xposed-free
 * runtime none of this is reachable. The stand-in on that runtime records hook registrations
 * instead of performing them, so the assertions here are about WHAT the shim asks to hook and how
 * it behaves when the platform refuses — not about Xposed's own dispatch, which stays device-only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class AudioRecordHookInstallXposedRuntimeTest {

    @Before
    @After
    public void resetInstallerAndRecordings() throws Exception {
        XposedRuntimeRecordings.reset();
        AudioRecordHookStatics.reset();
    }

    @Test
    public void installRegistersEveryLifecycleAndReadOverloadTheCaptureContractDependsOn()
            throws Exception {
        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());

        // Every lifecycle transition the session manager needs in order to own and release a route.
        assertEquals(
                java.util.Arrays.asList("<init>", "startRecording", "stop", "release"),
                XposedRuntimeRecordings.lifecycleMethods());

        // Every AudioRecord read overload. A dropped overload is silent data loss: that capture
        // path would simply never be post-processed, with no error anywhere.
        assertEquals("AudioRecordHook$ByteArrayReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(byte[],int,int)"));
        assertEquals("AudioRecordHook$ByteArrayReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(byte[],int,int,int)"));
        assertEquals("AudioRecordHook$ShortArrayReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(short[],int,int)"));
        assertEquals("AudioRecordHook$ShortArrayReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(short[],int,int,int)"));
        assertEquals("AudioRecordHook$FloatArrayReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(float[],int,int,int)"));
        assertEquals("AudioRecordHook$ByteBufferReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(ByteBuffer,int)"));
        assertEquals("AudioRecordHook$ByteBufferReadHook",
                XposedRuntimeRecordings.readHookSimpleName("read(ByteBuffer,int,int)"));
        assertEquals(7, XposedRuntimeRecordings.readHookCount());
    }

    @Test
    public void repeatedPackageLoadsInstallTheHooksExactlyOnce() throws Exception {
        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());
        int lifecycle = XposedRuntimeRecordings.lifecycleMethods().size();
        int reads = XposedRuntimeRecordings.readHookCount();

        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());

        // Double installation would stack two callbacks on every read and process each buffer twice.
        assertEquals(lifecycle, XposedRuntimeRecordings.lifecycleMethods().size());
        assertEquals(reads, XposedRuntimeRecordings.readHookCount());
    }

    @Test
    public void overloadsAbsentOnThisApiLevelAreSkippedWithoutAbandoningTheRest() throws Exception {
        // read(byte[],int,int,int) and the ByteBuffer overloads only exist from API 23 / 24, so a
        // NoSuchMethodError here is a routine platform difference, not a failure.
        XposedRuntimeRecordings.declareMissing("read(byte[],int,int,int)");
        XposedRuntimeRecordings.declareMissing("read(float[],int,int,int)");
        XposedRuntimeRecordings.declareMissing("read(ByteBuffer,int,int)");

        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());

        assertEquals(4, XposedRuntimeRecordings.readHookCount());
        assertNotNull(XposedRuntimeRecordings.readHook("read(byte[],int,int)"));
        assertNotNull(XposedRuntimeRecordings.readHook("read(short[],int,int)"));
        assertNotNull(XposedRuntimeRecordings.readHook("read(ByteBuffer,int)"));
        // The install as a whole still succeeded, so the process stays hooked.
        assertTrue(AudioRecordHookStatics.installed());
        assertNotNull(AudioRecordHookStatics.sessionManager());
    }

    @Test
    public void unresolvableAudioRecordClassFailsClosedAndLeavesTheProcessRetryable()
            throws Exception {
        // A classloader that cannot see android.media.AudioRecord stands in for a stripped or
        // hostile host. The installer must not latch itself shut on the way out.
        ClassLoader stripped = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        AudioRecordHook.install(stripped, ModuleState.getInstance());

        assertFalse(AudioRecordHookStatics.installed());
        assertNull(AudioRecordHookStatics.sessionManager());
        assertEquals(0, XposedRuntimeRecordings.readHookCount());
        assertTrue(XposedRuntimeRecordings.loggedContaining("unable to resolve AudioRecord class"));

        // Retryable means retryable: a later attempt with a working loader must actually install.
        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());
        assertTrue(AudioRecordHookStatics.installed());
        assertEquals(7, XposedRuntimeRecordings.readHookCount());
    }

    @Test
    public void captureRevocationArrivingBeforeInstallIsHonouredOnceTheHooksExist()
            throws Exception {
        // A revocation can reach the shim before the target process has finished loading. Losing
        // it would leave the control service waiting forever for an acknowledgement.
        RecordingDrain drained = new RecordingDrain();
        AudioRecordHook.requestCaptureRouteDrain(11L, 31L, drained);
        drained.assertNotDrained();
        assertNotNull(AudioRecordHookStatics.pendingDrain());

        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());

        drained.assertDrained(11L, 31L);
        // The buffer is emptied, so a later install cannot replay a stale revocation.
        assertNull(AudioRecordHookStatics.pendingDrain());
    }

    @Test
    public void drainRequestedAfterInstallGoesStraightThroughWithoutBuffering() throws Exception {
        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());
        RecordingDrain drained = new RecordingDrain();

        AudioRecordHook.requestCaptureRouteDrain(12L, 32L, drained);

        drained.assertDrained(12L, 32L);
        assertNull(AudioRecordHookStatics.pendingDrain());
    }

    @Test
    public void unusableDrainArgumentsAreRefusedAndNeverBuffered() throws Exception {
        RecordingDrain drained = new RecordingDrain();

        AudioRecordHook.requestCaptureRouteDrain(0L, 5L, drained);
        AudioRecordHook.requestCaptureRouteDrain(-1L, 5L, drained);
        // An acknowledgeable drain must carry a token; without one there is nothing to acknowledge.
        AudioRecordHook.requestCaptureRouteDrain(4L, 0L, drained);

        assertNull(AudioRecordHookStatics.pendingDrain());
        drained.assertNotDrained();

        // The unconditional fail-closed drain has no callback and legitimately carries no token.
        AudioRecordHook.requestCaptureRouteDrain(4L, 0L, null);
        assertNotNull(AudioRecordHookStatics.pendingDrain());
    }

    @Test
    public void bufferedDrainsKeepTheAcknowledgeableRequestAndTheNewestGeneration()
            throws Exception {
        RecordingDrain superseded = new RecordingDrain();
        RecordingDrain current = new RecordingDrain();

        AudioRecordHook.requestCaptureRouteDrain(3L, 0L, null);
        AudioRecordHook.requestCaptureRouteDrain(6L, 41L, superseded);
        AudioRecordHook.requestCaptureRouteDrain(8L, 42L, current);
        // A later callback-less revocation must not evict the pending acknowledgement.
        AudioRecordHook.requestCaptureRouteDrain(99L, 0L, null);

        AudioRecordHook.install(getClass().getClassLoader(), ModuleState.getInstance());

        current.assertDrained(8L, 42L);
        superseded.assertNotDrained();
    }

    @Test
    public void bufferedTokenlessDrainsCollapseToTheNewestGenerationWithoutRollingBackwards()
            throws Exception {
        AudioRecordHook.requestCaptureRouteDrain(5L, 0L, null);
        AudioRecordHook.requestCaptureRouteDrain(12L, 0L, null);
        // An out-of-order stale revocation must not roll the buffered generation backwards.
        AudioRecordHook.requestCaptureRouteDrain(7L, 0L, null);

        assertEquals(12L, AudioRecordHookStatics.pendingDrainGeneration());
        assertNull(AudioRecordHookStatics.pendingDrainCallback());
    }

    /**
     * The installed session manager drains on its own scheduler thread, so delivery is awaited
     * rather than assumed. {@link #assertNotDrained} uses a short bounded wait for the same reason.
     */
    private static final class RecordingDrain implements AudioRecordHook.CaptureDrainCallback {
        private final java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);
        volatile long generation;
        volatile long handoffToken;

        @Override
        public void onDrained(long drainedGeneration, long drainedToken) {
            generation = drainedGeneration;
            handoffToken = drainedToken;
            delivered.countDown();
        }

        void assertDrained(long expectedGeneration, long expectedToken) throws Exception {
            assertTrue(
                    "drain was never acknowledged",
                    delivered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(expectedGeneration, generation);
            assertEquals(expectedToken, handoffToken);
        }

        void assertNotDrained() throws Exception {
            assertFalse(delivered.await(250, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
    }
}
