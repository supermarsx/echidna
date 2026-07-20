package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Covers the diagnostic classification added to {@link AppConfig}: every distinct way the shim can
 * be loaded-but-inert must name itself, and none of that classification may change what is allowed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class AppConfigInertReasonTest {

    private static final String PACKAGE_NAME = "com.example.recorder";
    private static final String PROCESS_NAME = "com.example.recorder:remote";
    private static final String PRESET_ID = "voice-preset";

    @Test
    public void allowedPolicyIsActiveWithNoReason() throws Exception {
        AppConfig config = resolve(snapshot(builder()));

        assertTrue(config.shouldHook(PACKAGE_NAME, PROCESS_NAME));
        assertFalse(config.bypassRequested());
        assertEquals(AppConfig.InertReason.NONE, config.inertReason());
        assertEquals("lsposed", config.observedCaptureOwner());
    }

    @Test
    public void missingSnapshotReportsPolicyBindingNeverEstablished() {
        assertDenied(AppConfig.fromSnapshot(null, PACKAGE_NAME, PROCESS_NAME),
                AppConfig.InertReason.POLICY_UNAVAILABLE);
        assertDenied(AppConfig.disabled(), AppConfig.InertReason.POLICY_UNAVAILABLE);
        assertDenied(resolve(ProfileSnapshot.empty()), AppConfig.InertReason.POLICY_UNAVAILABLE);
        assertDenied(resolve(ProfileSnapshot.parse("{")), AppConfig.InertReason.POLICY_UNAVAILABLE);
    }

    @Test
    public void masterDisabledBypassAndPanicAllReportEngineDisabled() throws Exception {
        assertDenied(
                resolve(snapshot(builder().masterEnabled(false))),
                AppConfig.InertReason.ENGINE_DISABLED);
        assertDenied(
                resolve(snapshot(builder().bypass(true))),
                AppConfig.InertReason.ENGINE_DISABLED);
        assertDenied(
                resolve(snapshot(
                        builder().panicUntilEpochMs(System.currentTimeMillis() + 600_000L))),
                AppConfig.InertReason.ENGINE_DISABLED);
    }

    @Test
    public void disabledOrAbsentWhitelistEntryReportsNotWhitelisted() throws Exception {
        assertDenied(
                resolve(snapshot(builder().packageAllowed(false))),
                AppConfig.InertReason.NOT_WHITELISTED);
        assertDenied(
                resolve(snapshot(builder().processAllowed(Boolean.FALSE))),
                AppConfig.InertReason.NOT_WHITELISTED);
        assertDenied(
                resolve(snapshot(builder().includeWhitelistEntry(false))),
                AppConfig.InertReason.NOT_WHITELISTED);
    }

    @Test
    public void zygiskOwnershipReportsNotCaptureOwnerAndNamesTheEngineModeToChange()
            throws Exception {
        AppConfig config = resolve(snapshot(builder().owner("zygisk")));

        assertDenied(config, AppConfig.InertReason.NOT_CAPTURE_OWNER);
        assertEquals("zygisk", config.observedCaptureOwner());
        assertTrue(config.inertDescription().contains("observed owner: zygisk"));
        assertTrue(config.inertDescription().contains("Compatibility"));
    }

    @Test
    public void absentOwnerEntryReportsNotCaptureOwnerWithNoOwner() throws Exception {
        AppConfig config = resolve(snapshot(builder().includeOwnerEntry(false)));

        assertDenied(config, AppConfig.InertReason.NOT_CAPTURE_OWNER);
        assertEquals("", config.observedCaptureOwner());
        assertTrue(config.inertDescription().contains("observed owner: none"));
    }

    @Test
    public void whitelistedOwnedProcessWithoutAResolvableProfileReportsNoProfile() throws Exception {
        // Process-level whitelist and owner entries admit the process, but the package name the
        // runtime handed us is unusable, so no preset payload resolves for it.
        AppConfig config = AppConfig.fromSnapshot(
                snapshot(builder().includeWhitelistEntry(false).includeOwnerEntry(false)
                        .processEntriesOnly(true)),
                "not a package!",
                PROCESS_NAME);

        assertFalse(config.shouldHook("not a package!", PROCESS_NAME));
        assertTrue(config.bypassRequested());
        assertEquals("", config.profile());
        assertEquals(AppConfig.InertReason.NO_PROFILE, config.inertReason());
    }

    @Test
    public void reasonNeverWidensWhatIsAllowed() throws Exception {
        // Every denial keeps hooking off, bypass on, and the profile cleared - unchanged behaviour.
        for (ProfileSnapshot snapshot : new ProfileSnapshot[] {
                ProfileSnapshot.empty(),
                snapshot(builder().masterEnabled(false)),
                snapshot(builder().bypass(true)),
                snapshot(builder().packageAllowed(false)),
                snapshot(builder().owner("zygisk")),
        }) {
            AppConfig config = resolve(snapshot);
            assertFalse(config.shouldHook(PACKAGE_NAME, PROCESS_NAME));
            assertTrue(config.bypassRequested());
            assertEquals("", config.profile());
        }
    }

    private static void assertDenied(AppConfig config, AppConfig.InertReason expected) {
        assertFalse(config.shouldHook(PACKAGE_NAME, PROCESS_NAME));
        assertTrue(config.bypassRequested());
        assertEquals("", config.profile());
        assertEquals(expected, config.inertReason());
        assertFalse(config.inertDescription().isEmpty());
    }

    private static AppConfig resolve(ProfileSnapshot snapshot) {
        return AppConfig.fromSnapshot(snapshot, PACKAGE_NAME, PROCESS_NAME);
    }

    private static Builder builder() {
        return new Builder();
    }

    /** Mutable description of one v2 envelope; defaults describe a fully-admitted LSPosed target. */
    private static final class Builder {
        private boolean packageAllowed = true;
        private Boolean processAllowed;
        private boolean includeWhitelistEntry = true;
        private boolean includeOwnerEntry = true;
        private String owner = "lsposed";
        private boolean masterEnabled = true;
        private boolean bypass;
        private long panicUntilEpochMs;
        private boolean processEntriesOnly;

        Builder packageAllowed(boolean value) {
            packageAllowed = value;
            return this;
        }

        Builder processAllowed(Boolean value) {
            processAllowed = value;
            return this;
        }

        Builder includeWhitelistEntry(boolean value) {
            includeWhitelistEntry = value;
            return this;
        }

        Builder includeOwnerEntry(boolean value) {
            includeOwnerEntry = value;
            return this;
        }

        Builder owner(String value) {
            owner = value;
            return this;
        }

        Builder masterEnabled(boolean value) {
            masterEnabled = value;
            return this;
        }

        Builder bypass(boolean value) {
            bypass = value;
            return this;
        }

        Builder panicUntilEpochMs(long value) {
            panicUntilEpochMs = value;
            return this;
        }

        /** Keys the whitelist and owner maps by process name only, with no package-level entry. */
        Builder processEntriesOnly(boolean value) {
            processEntriesOnly = value;
            return this;
        }
    }

    private static ProfileSnapshot snapshot(Builder builder) throws Exception {
        JSONObject whitelist = new JSONObject();
        if (builder.includeWhitelistEntry) {
            whitelist.put(PACKAGE_NAME, builder.packageAllowed);
            if (builder.processAllowed != null) {
                whitelist.put(PROCESS_NAME, builder.processAllowed.booleanValue());
            }
        }
        JSONObject owners = new JSONObject();
        if (builder.includeOwnerEntry) {
            owners.put(PACKAGE_NAME, builder.owner);
        }
        if (builder.processEntriesOnly) {
            whitelist.put(PROCESS_NAME, true);
            owners.put(PROCESS_NAME, "lsposed");
        }
        JSONObject profiles = new JSONObject().put(
                PRESET_ID,
                new JSONObject()
                        .put("id", PRESET_ID)
                        .put("engine", new JSONObject().put("latencyMode", "LL"))
                        .put("modules", new JSONArray()));
        JSONObject bindings = new JSONObject().put(PACKAGE_NAME, PRESET_ID);
        JSONObject root = new JSONObject()
                .put("schemaVersion", 2)
                .put("generation", 1L)
                .put("profiles", profiles)
                .put("defaultProfileId", PRESET_ID)
                .put("whitelist", whitelist)
                .put("captureOwners", owners)
                .put("appBindings", bindings)
                .put(
                        "control",
                        new JSONObject()
                                .put("masterEnabled", builder.masterEnabled)
                                .put("bypass", builder.bypass)
                                .put("panicUntilEpochMs", builder.panicUntilEpochMs)
                                .put("sidetoneEnabled", false)
                                .put("sidetoneGainDb", 0.0)
                                .put("engineMode", "compatibility"));
        return ProfileSnapshot.parse(root.toString());
    }
}
