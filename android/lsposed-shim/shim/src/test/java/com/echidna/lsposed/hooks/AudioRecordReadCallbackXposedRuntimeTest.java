package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.echidna.lsposed.core.ModuleState;
import com.echidna.lsposed.core.TestModuleStates;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.ScheduledFuture;

import de.robv.android.xposed.XC_MethodHook;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The AudioRecord read callbacks — the shim's hot path and the only place it touches an app's
 * audio buffer. Runs on the Xposed-enabled unit-test runtime; on the default runtime these classes
 * cannot be linked at all.
 *
 * <p>The native engine is genuinely unavailable off-device, so {@code NativeBridge} always declines.
 * That is exactly the interesting half: the contract under test is that a declined, refused, or
 * faulting transform NEVER alters the caller's buffer, NEVER alters AudioRecord's return value, and
 * disables the module only for a genuine fault. What a device would additionally verify is the
 * accepted path — that a successful native call commits the right frame count.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class AudioRecordReadCallbackXposedRuntimeTest {

    private static final String PACKAGE_NAME = "com.example.recorder";
    private static final String PROCESS_NAME = "com.example.recorder:remote";

    private TestModuleStates.RecordingController controller;
    private ModuleState state;
    private AudioRecord record;

    @Before
    public void setUp() throws Exception {
        AudioRecordHookStatics.reset();
        TestModuleStates.resetStore();
        controller = new TestModuleStates.RecordingController();
        state = TestModuleStates.withController(controller);
        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);
        record = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                8192);
    }

    @After
    public void tearDown() throws Exception {
        record.release();
        TestModuleStates.resetStore();
        AudioRecordHookStatics.reset();
    }

    private void authorize() throws Exception {
        TestModuleStates.publishPolicy(policyPayload(1L));
        assertNotEquals(
                "policy fixture failed to authorize; the rest of this test would be vacuous",
                ModuleState.INVALID_AUDIO_PROCESSING_PERMIT,
                state.beginAudioProcessing());
        controller.engineReadyChecks.set(0);
    }

    // --- permit gating ------------------------------------------------------

    @Test
    public void thePermitGateDecidesWhetherTheTransformIsEnteredAtAll() throws Exception {
        // A ByteBuffer handed to the byte[] overload makes the transform throw on its cast, so a
        // resulting bypass is positive proof that the transform was entered.
        ByteBuffer buffer = directBuffer(32);
        byte[] before = contentsOf(buffer);
        XC_MethodHook hook = readHook("ByteArrayReadHook");

        // Unauthorized: no policy has been published, so the permit is refused before the native
        // engine is even consulted and the faulting transform is never reached.
        XC_MethodHook.MethodHookParam denied = param(record, 16, buffer, 0, 16);
        invoke(hook, denied);
        assertEquals(0, controller.engineReadyChecks.get());
        assertEquals(16, denied.getResult());
        assertArrayEquals(before, contentsOf(buffer));

        authorize();
        assertFalse(state.isBypassActive());

        invoke(hook, param(record, 16, buffer, 0, 16));

        // Authorized: the transform was entered, it threw, and the module disabled its own native
        // processing rather than risk corrupting audio.
        assertEquals(1, controller.engineReadyChecks.get());
        assertTrue(state.isBypassActive());
    }

    @Test
    public void anUnexpectedFaultInTheReadCallbackDisablesNativeProcessingAndPreservesTheRead()
            throws Exception {
        authorize();
        ByteBuffer buffer = directBuffer(32);
        byte[] before = contentsOf(buffer);

        XC_MethodHook.MethodHookParam param = param(record, 16, buffer, 0, 16);
        invoke(readHook("ByteArrayReadHook"), param);

        assertTrue(state.isBypassActive());
        // Fail-closed does not mean fail-destructive: the app still gets its bytes and its count.
        assertEquals(16, param.getResult());
        assertArrayEquals(before, contentsOf(buffer));
    }

    @Test
    public void nativeDecliningIsNotTreatedAsAFaultAndLeavesTheModuleEnabled() throws Exception {
        authorize();
        byte[] buffer = pattern(64);
        byte[] before = buffer.clone();

        XC_MethodHook.MethodHookParam param = param(record, 16, buffer, 4, 16);
        invoke(readHook("ByteArrayReadHook"), param);

        // The native engine is absent here, so processing declines. Declining is a routine
        // fallback, NOT a fault: disabling the module for it would be a self-inflicted outage.
        assertFalse(state.isBypassActive());
        assertEquals(16, param.getResult());
        assertArrayEquals(before, buffer);
    }

    // --- return-value preservation -----------------------------------------

    @Test
    public void audioRecordsReturnValueSurvivesEveryOverloadIncludingPartialReads()
            throws Exception {
        authorize();

        XC_MethodHook.MethodHookParam bytes = param(record, 12, pattern(64), 0, 12);
        invoke(readHook("ByteArrayReadHook"), bytes);
        assertEquals(12, bytes.getResult());

        XC_MethodHook.MethodHookParam shorts = param(record, 9, new short[64], 0, 9);
        invoke(readHook("ShortArrayReadHook"), shorts);
        assertEquals(9, shorts.getResult());

        XC_MethodHook.MethodHookParam floats = param(record, 7, new float[64], 0, 7);
        invoke(readHook("FloatArrayReadHook"), floats);
        assertEquals(7, floats.getResult());

        XC_MethodHook.MethodHookParam direct =
                param(record, 20, directBuffer(64), 20);
        invoke(readHook("ByteBufferReadHook"), direct);
        // A short read is normal; reporting anything else desynchronises the caller's stream.
        assertEquals(20, direct.getResult());
    }

    @Test
    public void resultsThatAreNotAPositiveIntegerAreIgnoredBeforeAnyPolicyIsConsulted()
            throws Exception {
        authorize();
        XC_MethodHook hook = readHook("ByteArrayReadHook");

        for (Object result : new Object[] {0, -1, null, "not-an-int", 3.5d}) {
            XC_MethodHook.MethodHookParam param = param(record, result, pattern(64), 0, 16);
            invoke(hook, param);
            assertEquals(result, param.getResult());
        }

        // An error return or a non-int result is not a buffer; the permit is never even taken.
        assertEquals(0, controller.engineReadyChecks.get());
        assertFalse(state.isBypassActive());
    }

    // --- buffer validation --------------------------------------------------

    @Test
    public void readDescriptorsThatDoNotDescribeAWritableRegionAreRefusedWithoutMutation()
            throws Exception {
        authorize();
        XC_MethodHook byteHook = readHook("ByteArrayReadHook");

        // Region runs past the end of the caller's array.
        byte[] overrun = pattern(16);
        assertRefused(byteHook, param(record, 16, overrun, 8, 16), overrun);

        // Negative offset.
        byte[] negative = pattern(16);
        assertRefused(byteHook, param(record, 4, negative, -1, 4), negative);

        // Fewer arguments than the hooked overload declares.
        byte[] truncated = pattern(16);
        XC_MethodHook.MethodHookParam shortArgs =
                param(record, 4, new Object[] {truncated, 0});
        invoke(byteHook, shortArgs);
        assertEquals(4, shortArgs.getResult());
        assertArrayEquals(pattern(16), truncated);

        // A heap ByteBuffer can never be handed to native, and a read-only one must not be written.
        ByteBuffer heap = ByteBuffer.allocate(32);
        XC_MethodHook.MethodHookParam heapParam = param(record, 16, heap, 16);
        invoke(readHook("ByteBufferReadHook"), heapParam);
        assertEquals(16, heapParam.getResult());

        ByteBuffer readOnly = directBuffer(32).asReadOnlyBuffer();
        XC_MethodHook.MethodHookParam readOnlyParam = param(record, 16, readOnly, 16);
        invoke(readHook("ByteBufferReadHook"), readOnlyParam);
        assertEquals(16, readOnlyParam.getResult());

        // None of the refusals is a fault, so the module stays enabled.
        assertFalse(state.isBypassActive());
    }

    @Test
    public void aReadReportingMoreThanTheBufferCanHoldIsRefused() throws Exception {
        authorize();

        ByteBuffer buffer = directBuffer(32);
        byte[] before = contentsOf(buffer);
        XC_MethodHook.MethodHookParam param = param(record, 64, buffer, 64);
        invoke(readHook("ByteBufferReadHook"), param);

        assertEquals(64, param.getResult());
        assertArrayEquals(before, contentsOf(buffer));
        assertFalse(state.isBypassActive());
    }

    @Test
    public void aReadFromSomethingThatIsNotAnAudioRecordCarriesNoUsableFormatAndIsRefused()
            throws Exception {
        authorize();
        byte[] buffer = pattern(64);

        // thisObject is not an AudioRecord, so sample rate / channel count / encoding are unknown
        // and the buffer must not be reinterpreted on a guess.
        XC_MethodHook.MethodHookParam param = param(new Object(), 16, buffer, 0, 16);
        invoke(readHook("ByteArrayReadHook"), param);

        assertEquals(16, param.getResult());
        assertArrayEquals(pattern(64), buffer);
        assertFalse(state.isBypassActive());
    }

    // --- nesting and route ownership ---------------------------------------

    @Test
    public void aDelegatedOverloadIsTransformedOnceAtTheOutermostReadOnly() throws Exception {
        authorize();
        XC_MethodHook hook = readHook("ByteArrayReadHook");
        XC_MethodHook.MethodHookParam outer = param(record, 16, pattern(64), 0, 16);
        XC_MethodHook.MethodHookParam inner = param(record, 16, pattern(64), 0, 16);

        // Several AudioRecord.read overloads delegate to one another, so one app-level read can
        // enter the hook twice. Transforming twice would apply the effect chain to the same audio
        // two times over.
        before(hook, outer);
        before(hook, inner);
        after(hook, inner);
        after(hook, outer);

        assertEquals(1, controller.engineReadyChecks.get());

        // Control: two genuinely separate reads are two transforms.
        controller.engineReadyChecks.set(0);
        invoke(hook, param(record, 16, pattern(64), 0, 16));
        invoke(hook, param(record, 16, pattern(64), 0, 16));
        assertEquals(2, controller.engineReadyChecks.get());
    }

    @Test
    public void aReadOnARouteOwnedByTheLegacyPreprocessorSkipsTheDirectPathEntirely()
            throws Exception {
        authorize();
        LegacyPreprocessorSessionManager.RouteLeases leases =
                new LegacyPreprocessorSessionManager.RouteLeases();
        assertTrue(leases.acquire(record, 41, 1L));
        AudioRecordHookStatics.installSessionManager(managerOwning(leases));

        invoke(readHook("ByteArrayReadHook"), param(record, 16, pattern(64), 0, 16));

        // The preprocessor already transformed this stream in the audio HAL. Running the direct
        // JNI path as well would process the same audio twice.
        assertEquals(0, controller.engineReadyChecks.get());
        assertFalse(state.isBypassActive());
    }

    @Test
    public void aReadOnAnUnownedRecordStillTakesTheDirectPath() throws Exception {
        authorize();
        LegacyPreprocessorSessionManager.RouteLeases leases =
                new LegacyPreprocessorSessionManager.RouteLeases();
        assertTrue(leases.acquire(new Object(), 41, 1L));
        AudioRecordHookStatics.installSessionManager(managerOwning(leases));

        invoke(readHook("ByteArrayReadHook"), param(record, 16, pattern(64), 0, 16));

        assertEquals(1, controller.engineReadyChecks.get());
    }

    // --- helpers ------------------------------------------------------------

    private void assertRefused(
            XC_MethodHook hook, XC_MethodHook.MethodHookParam param, byte[] buffer)
            throws Exception {
        Object expectedResult = param.getResult();
        byte[] before = buffer.clone();
        invoke(hook, param);
        assertEquals(expectedResult, param.getResult());
        assertArrayEquals(before, buffer);
    }

    private static LegacyPreprocessorSessionManager managerOwning(
            LegacyPreprocessorSessionManager.RouteLeases leases) {
        return new LegacyPreprocessorSessionManager(
                new LegacyPreprocessorSessionManager.PolicyAccess() {
                    @Override
                    public LegacyPreprocessorSessionManager.Policy current() {
                        return new LegacyPreprocessorSessionManager.Policy(false, 0L);
                    }

                    @Override
                    public void invalidateDirectPermits() {
                    }
                },
                (sessionId, generation, nonce, callback) -> false,
                (sessionId, generation, nonce, snapshot) -> false,
                sessionId -> {
                    throw new IllegalStateException("no effect off-device");
                },
                () -> 0L,
                (code, error) -> { },
                new LegacyPreprocessorSessionManager.Scheduler() {
                    @Override
                    public boolean execute(Runnable task) {
                        return false;
                    }

                    @Override
                    public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
                        return null;
                    }

                    @Override
                    public void shutdown() {
                    }
                },
                new SecureRandom(),
                leases);
    }

    private XC_MethodHook readHook(String simpleName) throws Exception {
        Class<?> type = Class.forName("com.echidna.lsposed.hooks.AudioRecordHook$" + simpleName);
        Constructor<?> constructor = type.getDeclaredConstructor(ModuleState.class);
        constructor.setAccessible(true);
        return (XC_MethodHook) constructor.newInstance(state);
    }

    private static XC_MethodHook.MethodHookParam param(
            Object thisObject, Object result, Object... args) throws Exception {
        Constructor<?> constructor =
                XC_MethodHook.MethodHookParam.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        XC_MethodHook.MethodHookParam param =
                (XC_MethodHook.MethodHookParam) constructor.newInstance();
        param.thisObject = thisObject;
        param.args = args;
        param.setResult(result);
        return param;
    }

    private static void invoke(XC_MethodHook hook, XC_MethodHook.MethodHookParam param)
            throws Exception {
        before(hook, param);
        after(hook, param);
    }

    private static void before(XC_MethodHook hook, XC_MethodHook.MethodHookParam param)
            throws Exception {
        callback("beforeHookedMethod").invoke(hook, param);
    }

    private static void after(XC_MethodHook hook, XC_MethodHook.MethodHookParam param)
            throws Exception {
        callback("afterHookedMethod").invoke(hook, param);
    }

    private static Method callback(String name) throws Exception {
        Method method = XC_MethodHook.class.getDeclaredMethod(
                name, XC_MethodHook.MethodHookParam.class);
        method.setAccessible(true);
        return method;
    }

    private static byte[] pattern(int length) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) {
            value[i] = (byte) (i + 1);
        }
        return value;
    }

    private static ByteBuffer directBuffer(int capacity) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        for (int i = 0; i < capacity; i++) {
            buffer.put(i, (byte) (i + 1));
        }
        return buffer;
    }

    private static byte[] contentsOf(ByteBuffer buffer) {
        ByteBuffer inspection = buffer.duplicate();
        inspection.clear();
        byte[] copy = new byte[inspection.capacity()];
        inspection.get(copy);
        return copy;
    }

    private static String policyPayload(long generation) throws Exception {
        JSONObject preset = new JSONObject()
                .put("engine", new JSONObject().put("latencyMode", "LL"))
                .put("modules", new JSONArray());
        return new JSONObject()
                .put("schemaVersion", 2)
                .put("generation", generation)
                .put("profiles", new JSONObject().put("default", preset))
                .put("defaultProfileId", "default")
                .put("appBindings", new JSONObject())
                .put("whitelist", new JSONObject().put(PROCESS_NAME, true))
                .put("captureOwners", new JSONObject().put(PROCESS_NAME, "lsposed"))
                .put(
                        "control",
                        new JSONObject()
                                .put("masterEnabled", true)
                                .put("bypass", false)
                                .put("panicUntilEpochMs", 0L)
                                .put("sidetoneEnabled", false)
                                .put("sidetoneGainDb", 0.0)
                                .put("engineMode", "compatibility"))
                .toString();
    }
}
