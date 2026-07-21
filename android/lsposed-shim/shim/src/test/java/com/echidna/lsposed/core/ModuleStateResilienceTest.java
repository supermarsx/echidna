package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * How {@link ModuleState} behaves when its collaborators misbehave or are simply absent.
 *
 * <p>{@link ModuleStatePolicyTest} covers the policy decision itself; this covers the surrounding
 * guarantees — that a throwing native layer cannot take the module down with it, that permits are
 * refused before any policy exists, and that the boundary calls it forwards stay fail-closed while
 * the policy client is unbound.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ModuleStateResilienceTest {

    private static final String PACKAGE_NAME = "com.example.recorder";
    private static final String PROCESS_NAME = "com.example.recorder:remote";

    private ProfileSnapshotStore store;

    @Before
    public void resetStore() {
        store = ProfileSnapshotStore.getInstance();
        store.resetForTests();
    }

    @After
    public void clearStore() throws Exception {
        shutdownStoreReceiver();
        store.resetForTests();
    }

    @Test
    public void nativeInitialisationThrowingIsContainedByTheConstructor() {
        ThrowingNativeController controller = new ThrowingNativeController();
        controller.initializeThrows = new UnsatisfiedLinkError("libechidna_shim_jni absent");

        // A hooked process must still come up — inert — when the native engine cannot load.
        ModuleState state = new ModuleState(store, controller, false);

        assertEquals(1, controller.initializeCount);
        assertFalse(state.isHookAllowed());
        assertTrue(state.isBypassActive());
    }

    @Test
    public void unattachedProcessRefusesEveryPermitAndReportsNoProfile() {
        ModuleState state = new ModuleState(store, new ThrowingNativeController(), false);

        // No package has been attached, so there is nothing to resolve and nothing to allow.
        assertFalse(state.refreshConfiguration());
        assertFalse(state.isHookAllowed());
        assertFalse(state.shouldProcessAudio());
        assertEquals(
                ModuleState.INVALID_AUDIO_PROCESSING_PERMIT, state.beginAudioProcessing());
        assertEquals("", state.getActiveProfile());
        assertEquals(AppConfig.InertReason.POLICY_UNAVAILABLE, state.inertReason());
    }

    @Test
    public void invalidPermitIsRefusedWithoutConsultingPolicy() throws Exception {
        ThrowingNativeController controller = new ThrowingNativeController();
        ModuleState state = new ModuleState(store, controller, false);
        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);
        store.update(ProfileSnapshot.parse(policyPayload(1L)));
        assertTrue(state.isHookAllowed());

        assertFalse(state.isAudioProcessingPermitCurrent(
                ModuleState.INVALID_AUDIO_PROCESSING_PERMIT));

        long permit = state.beginAudioProcessing();
        assertTrue(state.isAudioProcessingPermitCurrent(permit));
        // Any explicit invalidation retires every permit issued before it.
        state.invalidateAudioProcessingPermits();
        assertFalse(state.isAudioProcessingPermitCurrent(permit));
    }

    @Test
    public void bypassOverrideRetiresOutstandingPermitsAndPushesThroughToNative() throws Exception {
        ThrowingNativeController controller = new ThrowingNativeController();
        ModuleState state = new ModuleState(store, controller, false);
        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);
        store.update(ProfileSnapshot.parse(policyPayload(1L)));

        long permit = state.beginAudioProcessing();
        assertTrue(state.isAudioProcessingPermitCurrent(permit));

        state.setBypassOverride(true);
        assertTrue(state.isBypassActive());
        assertTrue(controller.bypass);
        assertFalse(state.isAudioProcessingPermitCurrent(permit));
        assertEquals(
                ModuleState.INVALID_AUDIO_PROCESSING_PERMIT, state.beginAudioProcessing());

        // Re-asserting the same override must not churn the epoch, but must stay bypassed.
        state.setBypassOverride(true);
        assertTrue(state.isBypassActive());

        state.setBypassOverride(false);
        assertFalse(state.isBypassActive());
        assertFalse(controller.bypass);
        assertTrue(state.beginAudioProcessing() != ModuleState.INVALID_AUDIO_PROCESSING_PERMIT);
    }

    @Test
    public void engineThatIsNotReadyWithholdsPermitsEvenUnderAnAuthorizingPolicy()
            throws Exception {
        ThrowingNativeController controller = new ThrowingNativeController();
        controller.engineReady = false;
        ModuleState state = new ModuleState(store, controller, false);
        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);
        store.update(ProfileSnapshot.parse(policyPayload(1L)));

        assertTrue(state.isHookAllowed());
        assertEquals(
                ModuleState.INVALID_AUDIO_PROCESSING_PERMIT, state.beginAudioProcessing());
        assertFalse(state.shouldProcessAudio());
    }

    @Test
    public void shouldInitializeForAttachesAndAnswersFromTheResolvedPolicy() throws Exception {
        ThrowingNativeController controller = new ThrowingNativeController();
        ModuleState state = new ModuleState(store, controller, false);

        assertFalse(state.shouldInitializeFor(PACKAGE_NAME, PROCESS_NAME));

        store.update(ProfileSnapshot.parse(policyPayload(1L)));
        assertTrue(state.shouldInitializeFor(PACKAGE_NAME, PROCESS_NAME));
        assertTrue(state.refreshConfiguration());
        assertTrue(state.getActiveProfile().contains("latencyMode"));
        assertEquals(AppConfig.InertReason.NONE, state.inertReason());
    }

    @Test
    public void nullPackageAndProcessAreNormalisedRatherThanPropagated() {
        ModuleState state = new ModuleState(store, new ThrowingNativeController(), false);

        state.onProcessAttached(null, null);

        // Normalised to empty, which is treated as "no package attached" and stays fail-closed.
        assertFalse(state.refreshConfiguration());
        assertFalse(state.isHookAllowed());
    }

    @Test
    public void frameCounterIsClampedAtZeroAndReportsTheLastSuccess() {
        ModuleState state = new ModuleState(store, new ThrowingNativeController(), false);

        assertEquals(0, state.lastFramesProcessed());
        state.noteProcessingSuccess(480);
        assertEquals(480, state.lastFramesProcessed());
        state.noteProcessingSuccess(-9);
        assertEquals(0, state.lastFramesProcessed());
    }

    @Test
    public void nativeStatusIsForwardedVerbatim() {
        ThrowingNativeController controller = new ThrowingNativeController();
        controller.status = EchidnaStatus.WAITING_FOR_ATTACH;
        ModuleState state = new ModuleState(store, controller, false);

        assertSame(EchidnaStatus.WAITING_FOR_ATTACH, state.getNativeStatus());
    }

    @Test
    public void capabilityRequestWithoutACallbackIsRefusedBeforeReachingTheStore() {
        ModuleState state = new ModuleState(store, new ThrowingNativeController(), false);

        assertFalse(state.requestLegacyPreprocessorCapability(41, 1L, new byte[16], null));
    }

    @Test
    public void boundaryCallsFailClosedWhileTheProcessHasNoPolicyClient() {
        ModuleState state = new ModuleState(store, new ThrowingNativeController(), false);

        // No receiver has been started, so neither call can reach a provider.
        assertFalse(state.requestLegacyPreprocessorCapability(
                41, 1L, new byte[16], new RecordingCapabilityCallback()));
        assertFalse(state.reportLegacyPreprocessorTelemetry(41, 1L, new byte[16], new byte[48]));
    }

    @Test
    public void attachingWithTheReceiverEnabledStartsOnePolicyClientThatStillFailsClosed()
            throws Exception {
        ModuleState state = new ModuleState(store, new ThrowingNativeController(), true);

        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);
        assertNotNull(storeReceiver());

        // The client exists but has not bound to any provider, so capability requests are denied
        // through the adapter rather than silently dropped.
        RecordingCapabilityCallback callback = new RecordingCapabilityCallback();
        assertTrue(state.requestLegacyPreprocessorCapability(41, 1L, new byte[16], callback));
        assertTrue(callback.completed.await(3, TimeUnit.SECONDS));
        assertEquals("unavailable", callback.failure.get());

        // Telemetry is accepted for dispatch but has no provider to reach.
        assertTrue(state.reportLegacyPreprocessorTelemetry(41, 1L, new byte[16], new byte[48]));
        assertFalse(state.reportLegacyPreprocessorTelemetry(0, 1L, new byte[16], new byte[48]));
    }

    @Test
    public void legacyPreprocessorEligibilityTracksPolicyGenerationAndBypass() throws Exception {
        ThrowingNativeController controller = new ThrowingNativeController();
        ModuleState state = new ModuleState(store, controller, false);
        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);

        ModuleState.LegacyPreprocessorPolicy denied = state.legacyPreprocessorPolicy();
        assertFalse(denied.eligible);
        assertEquals(0L, denied.generation);

        store.update(ProfileSnapshot.parse(policyPayload(4L)));
        ModuleState.LegacyPreprocessorPolicy allowed = state.legacyPreprocessorPolicy();
        assertTrue(allowed.eligible);
        assertEquals(4L, allowed.generation);

        state.setBypassOverride(true);
        ModuleState.LegacyPreprocessorPolicy bypassed = state.legacyPreprocessorPolicy();
        assertFalse(bypassed.eligible);
        // The generation still describes the standing policy even while ineligible.
        assertEquals(4L, bypassed.generation);
    }

    private ProfileSyncReceiver storeReceiver() throws Exception {
        Field field = ProfileSnapshotStore.class.getDeclaredField("receiver");
        field.setAccessible(true);
        return (ProfileSyncReceiver) field.get(store);
    }

    private void shutdownStoreReceiver() throws Exception {
        ProfileSyncReceiver receiver = storeReceiver();
        if (receiver == null) {
            return;
        }
        Field field = ProfileSyncReceiver.class.getDeclaredField("executor");
        field.setAccessible(true);
        ((ScheduledExecutorService) field.get(receiver)).shutdownNow();
    }

    private static final class RecordingCapabilityCallback
            implements ModuleState.LegacyCapabilityCallback {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public void onResult(int status, long generation, byte[] envelope, String diagnostic) {
            completed.countDown();
        }

        @Override
        public void onFailure(String diagnostic) {
            failure.set(diagnostic);
            completed.countDown();
        }
    }

    private static final class ThrowingNativeController implements ModuleState.NativeController {
        int initializeCount;
        boolean bypass;
        boolean engineReady = true;
        String profile = "";
        EchidnaStatus status = EchidnaStatus.HOOKED;
        Error initializeThrows;

        @Override
        public boolean initialize() {
            initializeCount++;
            if (initializeThrows != null) {
                throw initializeThrows;
            }
            return true;
        }

        @Override
        public boolean isEngineReady() {
            return engineReady;
        }

        @Override
        public void setBypass(boolean next) {
            bypass = next;
        }

        @Override
        public void setProfile(String next) {
            profile = next;
        }

        @Override
        public EchidnaStatus getStatus() {
            return status;
        }
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
