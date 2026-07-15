package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.echidna.lsposed.hooks.AudioReadTransaction;

import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ModuleStatePolicyTest {

    private static final String PACKAGE_NAME = "com.example.recorder";
    private static final String PROCESS_NAME = "com.example.recorder:remote";
    private static final String PRESET_ID = "voice-preset";

    private ProfileSnapshotStore store;
    private RecordingNativeController nativeController;
    private ModuleState state;

    @Before
    public void setUp() {
        store = ProfileSnapshotStore.getInstance();
        store.update(ProfileSnapshot.empty());
        nativeController = new RecordingNativeController();
        state = new ModuleState(store, nativeController, false);
        state.onProcessAttached(PACKAGE_NAME, PROCESS_NAME);
    }

    @Test
    public void coldProcessActivatesWhenSnapshotArrivesAndRevokesWithoutReinstall() throws Exception {
        assertFalse(state.isHookAllowed());
        assertTrue(nativeController.bypass);

        long deniedVersion = store.version();
        store.update(snapshot(true, null, true, true, true, false));

        assertTrue(store.version() > deniedVersion);
        assertTrue(state.isHookAllowed());
        assertFalse(nativeController.bypass);
        assertTrue(state.shouldProcessAudio());
        assertTrue(nativeController.profile.contains(PRESET_ID));

        store.update(snapshot(true, false, true, true, true, false));

        assertFalse(state.isHookAllowed());
        assertFalse(state.shouldProcessAudio());
        assertTrue(nativeController.bypass);
    }

    @Test
    public void processSpecificDecisionOverridesPackageDecision() throws Exception {
        store.update(snapshot(true, false, true, true, true, false));
        assertFalse(state.isHookAllowed());

        store.update(snapshot(false, true, true, true, true, false));
        assertTrue(state.isHookAllowed());
    }

    @Test
    public void danglingOrMissingBindingNeverReusesPreviousNativeProfile() throws Exception {
        store.update(snapshot(true, null, true, true, true, false));
        assertTrue(state.isHookAllowed());
        String previouslyApplied = nativeController.profile;

        store.update(snapshot(true, null, true, false, true, false));
        assertFalse(state.isHookAllowed());
        assertTrue(nativeController.bypass);
        assertEquals(previouslyApplied, nativeController.profile);

        store.update(snapshot(true, null, false, true, true, false));
        assertFalse(state.isHookAllowed());
        assertTrue(nativeController.bypass);
    }

    @Test
    public void globalMasterAndBypassRemainFailClosed() throws Exception {
        store.update(snapshot(true, null, true, true, false, false));
        assertFalse(state.isHookAllowed());
        assertTrue(nativeController.bypass);

        store.update(snapshot(true, null, true, true, true, true));
        assertFalse(state.isHookAllowed());
        assertTrue(nativeController.bypass);
    }

    @Test
    public void deniedPolicyScenariosPreserveReadResultAndBytes() throws Exception {
        store.update(snapshot(false, null, true, true, true, false));
        assertDeniedReadIsUntouched();

        store.update(snapshot(true, false, true, true, true, false));
        assertDeniedReadIsUntouched();

        store.update(snapshot(true, null, false, true, true, false));
        assertDeniedReadIsUntouched();

        store.update(ProfileSnapshot.parse("{"));
        assertDeniedReadIsUntouched();

        store.update(snapshot(true, null, true, true, true, false));
        assertTrue(state.isHookAllowed());
        store.update(ProfileSnapshot.empty());
        assertDeniedReadIsUntouched();
    }

    @Test
    public void snapshotRevocationRacingInFlightReadRestoresOriginalBytes() throws Exception {
        store.update(snapshot(true, null, true, true, true, false));
        byte[] bytes = new byte[] {1, 2, 3, 4};

        int returned = AudioReadTransaction.execute(
                2,
                bytes,
                1,
                null,
                stateGate(),
                (context, actual) -> {
                    bytes[1] = 20;
                    bytes[2] = 30;
                    store.update(ProfileSnapshot.empty());
                    return 1;
                },
                frames -> { },
                throwable -> { });

        assertEquals(2, returned);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes);
        assertFalse(state.isHookAllowed());
    }

    private void assertDeniedReadIsUntouched() {
        byte[] bytes = new byte[] {1, 2, 3, 4};
        AtomicBoolean transformed = new AtomicBoolean(false);
        int returned = AudioReadTransaction.execute(
                2,
                bytes,
                1,
                null,
                stateGate(),
                (context, actual) -> {
                    transformed.set(true);
                    bytes[1] = 20;
                    bytes[2] = 30;
                    return 1;
                },
                frames -> { },
                throwable -> { });

        assertEquals(2, returned);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes);
        assertFalse(transformed.get());
    }

    private AudioReadTransaction.PermitGate stateGate() {
        return new AudioReadTransaction.PermitGate() {
            @Override
            public long begin() {
                return state.beginAudioProcessing();
            }

            @Override
            public boolean isCurrent(long permit) {
                return state.isAudioProcessingPermitCurrent(permit);
            }
        };
    }

    private static ProfileSnapshot snapshot(
            boolean packageAllowed,
            Boolean processAllowed,
            boolean includeBinding,
            boolean includePayload,
            boolean masterEnabled,
            boolean bypass) throws Exception {
        JSONObject whitelist = new JSONObject().put(PACKAGE_NAME, packageAllowed);
        if (processAllowed != null) {
            whitelist.put(PROCESS_NAME, processAllowed);
        }
        JSONObject bindings = new JSONObject();
        if (includeBinding) {
            bindings.put(PACKAGE_NAME, PRESET_ID);
        }
        JSONObject profiles = new JSONObject();
        if (includePayload) {
            profiles.put(
                    PRESET_ID,
                    new JSONObject()
                            .put("id", PRESET_ID)
                            .put("engine", new JSONObject().put("latencyMode", "LL"))
                            .put("modules", new org.json.JSONArray()));
        }
        JSONObject root = new JSONObject()
                .put("profiles", profiles)
                .put("whitelist", whitelist)
                .put("appBindings", bindings)
                .put(
                        "control",
                        new JSONObject()
                                .put("masterEnabled", masterEnabled)
                                .put("bypass", bypass));
        return ProfileSnapshot.parse(root.toString());
    }

    private static final class RecordingNativeController implements ModuleState.NativeController {
        boolean bypass;
        String profile = "";

        @Override
        public boolean initialize() {
            return true;
        }

        @Override
        public boolean isEngineReady() {
            return true;
        }

        @Override
        public void setBypass(boolean bypass) {
            this.bypass = bypass;
        }

        @Override
        public void setProfile(String profile) {
            this.profile = profile;
        }

        @Override
        public EchidnaStatus getStatus() {
            return EchidnaStatus.HOOKED;
        }
    }
}
