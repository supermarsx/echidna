package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The lifecycle callbacks the installer registers on AudioRecord's constructor, startRecording,
 * stop and release. Runs on the Xposed-enabled unit-test runtime.
 *
 * <p>These callbacks are thin adapters, but their guards are load-bearing: they decide whether a
 * legacy-preprocessor session is opened at all. Opening one for a recorder that never actually
 * started would attach an effect to a session the app is not using.
 *
 * <p>{@code hookLifecycle} is driven directly rather than through {@code install()} so the manager
 * under observation has an inline scheduler — the production installer wires a real background
 * executor, which would make every assertion here a race.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class AudioRecordLifecycleHookXposedRuntimeTest {

    private RecordingPolicy policy;
    private RecordingDiagnostics diagnostics;
    private LegacyPreprocessorSessionManager manager;
    private ScriptedRecord record;

    @Before
    public void setUp() throws Exception {
        XposedRuntimeRecordings.reset();
        AudioRecordHookStatics.reset();
        policy = new RecordingPolicy();
        diagnostics = new RecordingDiagnostics();
        manager = inlineManager();
        record = new ScriptedRecord();
        hookLifecycle(AudioRecord.class, manager);
    }

    @After
    public void tearDown() throws Exception {
        record.release();
        AudioRecordHookStatics.reset();
        XposedRuntimeRecordings.reset();
    }

    @Test
    public void allFourLifecycleTransitionsAreObserved() throws Exception {
        assertNotNull(XposedRuntimeRecordings.lifecycleHook("<init>"));
        assertNotNull(XposedRuntimeRecordings.lifecycleHook("startRecording"));
        assertNotNull(XposedRuntimeRecordings.lifecycleHook("stop"));
        assertNotNull(XposedRuntimeRecordings.lifecycleHook("release"));
    }

    @Test
    public void aRecorderThatActuallyStartedOpensASession() throws Exception {
        record.recordingState = AudioRecord.RECORDSTATE_RECORDING;

        after("<init>", param(record));
        after("startRecording", param(record));

        // Reaching the policy read is the proof that the session manager was asked to start.
        assertTrue(policy.reads.get() > 0);
        assertTrue(diagnostics.codes.contains("effect_create_failed"));
    }

    @Test
    public void aStartRecordingThatThrewNeverOpensASession() throws Exception {
        record.recordingState = AudioRecord.RECORDSTATE_RECORDING;
        after("<init>", param(record));

        XC_MethodHook.MethodHookParam failed = param(record);
        failed.setThrowable(new IllegalStateException("AudioFlinger refused the start"));
        after("startRecording", failed);

        // The app's recorder is not running, so attaching a preprocessor to its session would
        // bind an effect to a stream that will never produce audio.
        assertEquals(0, policy.reads.get());
        assertTrue(diagnostics.codes.isEmpty());
    }

    @Test
    public void aRecorderStillInStoppedStateNeverOpensASession() throws Exception {
        // startRecording() returned without throwing but the recorder is not in RECORDING state.
        record.recordingState = AudioRecord.RECORDSTATE_STOPPED;

        after("<init>", param(record));
        after("startRecording", param(record));

        assertEquals(0, policy.reads.get());
        assertTrue(diagnostics.codes.isEmpty());
    }

    @Test
    public void lifecycleCallbacksOnSomethingThatIsNotAnAudioRecordAreIgnored() throws Exception {
        // Xposed hooks by class, and a subclass or an unexpected receiver must not be coerced.
        after("<init>", param(new Object()));
        after("startRecording", param(new Object()));

        assertEquals(0, policy.reads.get());
        assertTrue(diagnostics.codes.isEmpty());
    }

    @Test
    public void stopAndReleaseTearTheRouteDownRatherThanLeavingItOwned() throws Exception {
        record.recordingState = AudioRecord.RECORDSTATE_RECORDING;
        after("<init>", param(record));
        after("startRecording", param(record));

        before("stop", param(record));
        assertFalse(manager.ownsRoute(record));

        before("release", param(record));
        // A released AudioRecord must not leave a lease behind for a later record to inherit.
        assertFalse(manager.ownsRoute(record));
    }

    @Test
    public void frameworkMetadataQueriesThatThrowAreTreatedAsUnusableRatherThanPropagating()
            throws Exception {
        // A released or wedged AudioRecord can throw from its own getters. That must degrade to
        // "no session" inside the hook, never surface as an exception on the app's audio thread.
        record.recordingState = AudioRecord.RECORDSTATE_RECORDING;
        record.faultOnQueries = true;

        after("<init>", param(record));
        after("startRecording", param(record));

        assertEquals(0, policy.reads.get());
        assertTrue(diagnostics.codes.isEmpty());
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Robolectric's AudioRecord reports session id 0 and never enters RECORDING, both of which the
     * session manager correctly refuses — which would make every case here indistinguishable.
     */
    private static final class ScriptedRecord extends AudioRecord {
        volatile int sessionId = 41;
        volatile int state = AudioRecord.STATE_INITIALIZED;
        volatile int recordingState = AudioRecord.RECORDSTATE_STOPPED;
        volatile boolean faultOnQueries;

        ScriptedRecord() {
            super(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    8192);
        }

        @Override
        public int getAudioSessionId() {
            if (faultOnQueries) {
                throw new IllegalStateException("AudioRecord released");
            }
            return sessionId;
        }

        @Override
        public int getState() {
            if (faultOnQueries) {
                throw new IllegalStateException("AudioRecord released");
            }
            return state;
        }

        @Override
        public int getRecordingState() {
            if (faultOnQueries) {
                throw new IllegalStateException("AudioRecord released");
            }
            return recordingState;
        }
    }

    private LegacyPreprocessorSessionManager inlineManager() {
        return new LegacyPreprocessorSessionManager(
                policy,
                (sessionId, generation, nonce, callback) -> false,
                (sessionId, generation, nonce, snapshot) -> false,
                sessionId -> {
                    throw new IllegalStateException("no audio effect off-device");
                },
                () -> 0L,
                diagnostics,
                new InlineScheduler(),
                new SecureRandom(),
                new LegacyPreprocessorSessionManager.RouteLeases());
    }

    private static void hookLifecycle(
            Class<?> audioRecordClass, LegacyPreprocessorSessionManager manager) throws Exception {
        Method hookLifecycle = AudioRecordHook.class.getDeclaredMethod(
                "hookLifecycle", Class.class, LegacyPreprocessorSessionManager.class);
        hookLifecycle.setAccessible(true);
        hookLifecycle.invoke(null, audioRecordClass, manager);
    }

    private static XC_MethodHook.MethodHookParam param(Object thisObject) throws Exception {
        java.lang.reflect.Constructor<?> constructor =
                XC_MethodHook.MethodHookParam.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        XC_MethodHook.MethodHookParam param =
                (XC_MethodHook.MethodHookParam) constructor.newInstance();
        param.thisObject = thisObject;
        param.args = new Object[0];
        return param;
    }

    private static void after(String methodName, XC_MethodHook.MethodHookParam param)
            throws Exception {
        callback("afterHookedMethod").invoke(
                XposedRuntimeRecordings.lifecycleHook(methodName), param);
    }

    private static void before(String methodName, XC_MethodHook.MethodHookParam param)
            throws Exception {
        callback("beforeHookedMethod").invoke(
                XposedRuntimeRecordings.lifecycleHook(methodName), param);
    }

    private static Method callback(String name) throws Exception {
        Method method = XC_MethodHook.class.getDeclaredMethod(
                name, XC_MethodHook.MethodHookParam.class);
        method.setAccessible(true);
        return method;
    }

    private static final class RecordingPolicy
            implements LegacyPreprocessorSessionManager.PolicyAccess {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public LegacyPreprocessorSessionManager.Policy current() {
            reads.incrementAndGet();
            return new LegacyPreprocessorSessionManager.Policy(true, 1L);
        }

        @Override
        public void invalidateDirectPermits() {
        }
    }

    private static final class RecordingDiagnostics
            implements LegacyPreprocessorSessionManager.Diagnostics {
        final List<String> codes = new ArrayList<>();

        @Override
        public void report(String code, Throwable error) {
            codes.add(code);
        }
    }

    private static final class InlineScheduler
            implements LegacyPreprocessorSessionManager.Scheduler {
        @Override
        public boolean execute(Runnable task) {
            task.run();
            return true;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }
}
