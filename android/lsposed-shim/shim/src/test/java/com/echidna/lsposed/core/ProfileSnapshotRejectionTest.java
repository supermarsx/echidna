package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The policy envelope parser. Everything it reads crosses a process boundary from the control
 * service, so the contract that matters is that ANY deviation from the strict v2 shape collapses
 * to the fail-closed sentinel rather than to a partially-trusted document. A snapshot that parses
 * is a snapshot that will enable audio interception, so "nearly valid" must be treated as hostile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ProfileSnapshotRejectionTest {

    @Test
    public void canonicalEnvelopeParsesSoTheRejectionCasesBelowAreAboutOneFieldEach()
            throws Exception {
        ProfileSnapshot snapshot = ProfileSnapshot.parse(valid().toString());

        assertTrue(snapshot.isValid());
        assertEquals(7L, snapshot.generation());
        assertEquals("compatibility", snapshot.engineMode());
        assertTrue(snapshot.isProcessAllowed("com.example.recorder", "com.example.recorder"));
        assertEquals("lsposed", snapshot.captureOwner("com.example.recorder", null));
        assertTrue(snapshot.isGloballyEnabled());
    }

    @Test
    public void nullAndEmptyAndOversizedEnvelopesAreAllRejected() throws Exception {
        assertRejected(ProfileSnapshot.parse(null));
        assertRejected(ProfileSnapshot.parse(""));

        // A preset large enough to blow the envelope budget must be refused before any allocation
        // of the resulting map, not truncated into a smaller "valid" document.
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < ProfileSnapshot.MAX_ENVELOPE_BYTES; i++) {
            padding.append('a');
        }
        JSONObject oversized = valid();
        oversized.getJSONObject("profiles")
                .getJSONObject("default")
                .getJSONObject("engine")
                .put("latencyMode", padding.toString());
        assertRejected(ProfileSnapshot.parse(oversized.toString()));
    }

    @Test
    public void unknownOrMissingRootKeysAreRejectedRatherThanIgnored() throws Exception {
        JSONObject extra = valid().put("shadowPolicy", true);
        assertRejected(ProfileSnapshot.parse(extra.toString()));

        JSONObject missing = valid();
        missing.remove("captureOwners");
        assertRejected(ProfileSnapshot.parse(missing.toString()));
    }

    @Test
    public void nonSchemaTwoEnvelopesAreRejected() throws Exception {
        assertRejected(ProfileSnapshot.parse(valid().put("schemaVersion", 1).toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("schemaVersion", 3).toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("schemaVersion", "2").toString()));
    }

    @Test
    public void nonCanonicalOrNonPositiveGenerationsAreRejected() throws Exception {
        assertRejected(ProfileSnapshot.parse(valid().put("generation", 0).toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("generation", -1).toString()));
        // A float-shaped or otherwise non-canonical integer is not a generation.
        assertRejected(ProfileSnapshot.parse(valid().put("generation", 7.5d).toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("generation", "7").toString()));
    }

    @Test
    public void emptyOrMalformedProfileMapIsRejected() throws Exception {
        assertRejected(ProfileSnapshot.parse(
                valid().put("profiles", new JSONObject()).toString()));

        // A profile id outside the permitted alphabet.
        JSONObject badId = valid();
        JSONObject preset = badId.getJSONObject("profiles").getJSONObject("default");
        badId.put("profiles", new JSONObject().put("bad id!", preset));
        badId.put("defaultProfileId", "bad id!");
        assertRejected(ProfileSnapshot.parse(badId.toString()));

        // A preset missing its engine block.
        JSONObject noEngine = valid();
        noEngine.getJSONObject("profiles").getJSONObject("default").remove("engine");
        assertRejected(ProfileSnapshot.parse(noEngine.toString()));

        // A preset missing its module list.
        JSONObject noModules = valid();
        noModules.getJSONObject("profiles").getJSONObject("default").remove("modules");
        assertRejected(ProfileSnapshot.parse(noModules.toString()));

        // A preset that is not an object at all.
        JSONObject scalarPreset = valid();
        scalarPreset.put("profiles", new JSONObject().put("default", "not-an-object"));
        assertRejected(ProfileSnapshot.parse(scalarPreset.toString()));
    }

    @Test
    public void defaultProfileMustBeAStringThatNamesAnExistingProfile() throws Exception {
        assertRejected(ProfileSnapshot.parse(valid().put("defaultProfileId", 3).toString()));
        assertRejected(ProfileSnapshot.parse(
                valid().put("defaultProfileId", "absent").toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("defaultProfileId", "").toString()));
    }

    @Test
    public void appBindingsPointingAtUnknownPresetsOrInvalidPackagesAreRejected()
            throws Exception {
        assertRejected(ProfileSnapshot.parse(
                valid().put("appBindings", new JSONObject().put("com.a", "absent")).toString()));
        assertRejected(ProfileSnapshot.parse(
                valid().put("appBindings", new JSONObject().put("com.a", 4)).toString()));
        assertRejected(ProfileSnapshot.parse(
                valid().put("appBindings", new JSONObject().put("bad name!", "default"))
                        .toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("appBindings", "nope").toString()));
    }

    @Test
    public void whitelistEntriesMustBeBooleansKeyedByValidProcessNames() throws Exception {
        assertRejected(ProfileSnapshot.parse(
                valid().put("whitelist", new JSONObject().put("com.a", "true")).toString()));
        assertRejected(ProfileSnapshot.parse(
                valid().put("whitelist", new JSONObject().put("bad name!", true)).toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("whitelist", 5).toString()));
    }

    @Test
    public void captureOwnersMustNameAKnownOwnerForAValidProcess() throws Exception {
        // An unknown owner string would otherwise silently hand capture to nobody.
        assertRejected(ProfileSnapshot.parse(
                valid().put("captureOwners", new JSONObject().put("com.a", "magisk"))
                        .toString()));
        assertRejected(ProfileSnapshot.parse(
                valid().put("captureOwners", new JSONObject().put("com.a", true)).toString()));
        assertRejected(ProfileSnapshot.parse(
                valid().put("captureOwners", new JSONObject().put("bad name!", "lsposed"))
                        .toString()));
        assertRejected(ProfileSnapshot.parse(valid().put("captureOwners", 6).toString()));
    }

    @Test
    public void controlBlockMustCarryExactlyItsKeysWithExactlyItsTypes() throws Exception {
        assertRejected(ProfileSnapshot.parse(valid().put("control", "on").toString()));

        JSONObject extraKey = valid();
        extraKey.getJSONObject("control").put("shadow", 1);
        assertRejected(ProfileSnapshot.parse(extraKey.toString()));

        JSONObject missingKey = valid();
        missingKey.getJSONObject("control").remove("bypass");
        assertRejected(ProfileSnapshot.parse(missingKey.toString()));

        assertRejected(ProfileSnapshot.parse(control("masterEnabled", "true")));
        assertRejected(ProfileSnapshot.parse(control("bypass", 0)));
        assertRejected(ProfileSnapshot.parse(control("sidetoneEnabled", "false")));
        assertRejected(ProfileSnapshot.parse(control("sidetoneGainDb", "0.0")));
        assertRejected(ProfileSnapshot.parse(control("engineMode", "turbo")));
        assertRejected(ProfileSnapshot.parse(control("engineMode", 1)));
        assertRejected(ProfileSnapshot.parse(control("panicUntilEpochMs", -1)));
        assertRejected(ProfileSnapshot.parse(control("panicUntilEpochMs", "0")));
    }

    @Test
    public void nonFiniteSidetoneGainIsRejected() throws Exception {
        // JSON has no literal for these, so they arrive as a raw token that must not parse.
        assertRejected(ProfileSnapshot.parse(
                valid().toString().replace("\"sidetoneGainDb\":0", "\"sidetoneGainDb\":NaN")));
        assertRejected(ProfileSnapshot.parse(
                valid().toString()
                        .replace("\"sidetoneGainDb\":0", "\"sidetoneGainDb\":Infinity")));
    }

    @Test
    public void activePanicHoldSuppressesGlobalEnablementWithoutInvalidatingTheSnapshot()
            throws Exception {
        JSONObject held = valid();
        held.getJSONObject("control")
                .put("panicUntilEpochMs", System.currentTimeMillis() + 600_000L);
        ProfileSnapshot snapshot = ProfileSnapshot.parse(held.toString());

        assertTrue(snapshot.isValid());
        assertFalse(snapshot.isGloballyEnabled());
    }

    @Test
    public void masterDisabledOrBypassedSnapshotsParseButAreNotGloballyEnabled()
            throws Exception {
        JSONObject disabled = valid();
        disabled.getJSONObject("control").put("masterEnabled", false);
        assertTrue(ProfileSnapshot.parse(disabled.toString()).isValid());
        assertFalse(ProfileSnapshot.parse(disabled.toString()).isGloballyEnabled());

        JSONObject bypassed = valid();
        bypassed.getJSONObject("control").put("bypass", true);
        assertFalse(ProfileSnapshot.parse(bypassed.toString()).isGloballyEnabled());
    }

    @Test
    public void exactProcessEntryOverridesTheBasePackageForBothPolicyAndOwner() throws Exception {
        JSONObject scoped = valid();
        scoped.put("whitelist", new JSONObject()
                .put("com.example.recorder", true)
                .put("com.example.recorder:audio", false));
        scoped.put("captureOwners", new JSONObject()
                .put("com.example.recorder", "lsposed")
                .put("com.example.recorder:audio", "zygisk"));
        ProfileSnapshot snapshot = ProfileSnapshot.parse(scoped.toString());

        assertTrue(snapshot.isValid());
        assertTrue(snapshot.isProcessAllowed("com.example.recorder", "com.example.recorder"));
        // The exact process entry wins even though the base package is admitted.
        assertFalse(snapshot.isProcessAllowed(
                "com.example.recorder", "com.example.recorder:audio"));
        assertEquals("zygisk", snapshot.captureOwner(
                "com.example.recorder", "com.example.recorder:audio"));
        assertEquals("lsposed", snapshot.captureOwner("com.example.recorder", "com.other"));
    }

    @Test
    public void unknownProcessesAreDeniedAndOwnerlessRatherThanDefaulted() throws Exception {
        ProfileSnapshot snapshot = ProfileSnapshot.parse(valid().toString());

        assertFalse(snapshot.isProcessAllowed("com.unknown", "com.unknown"));
        assertFalse(snapshot.isProcessAllowed(null, null));
        assertEquals("", snapshot.captureOwner("com.unknown", null));
        assertEquals("", snapshot.captureOwner(null, null));
    }

    @Test
    public void profileResolutionUsesTheBindingThenTheDefaultAndRefusesInvalidPackages()
            throws Exception {
        JSONObject bound = valid();
        bound.put("profiles", new JSONObject()
                .put("default", preset("LL"))
                .put("studio", preset("HQ")));
        bound.put("appBindings", new JSONObject().put("com.example.recorder", "studio"));
        ProfileSnapshot snapshot = ProfileSnapshot.parse(bound.toString());

        assertTrue(snapshot.isValid());
        assertTrue(snapshot.resolveProfile("com.example.recorder").contains("HQ"));
        assertTrue(snapshot.resolveProfile("com.unbound").contains("LL"));
        assertEquals("", snapshot.resolveProfile("bad name!"));
        assertEquals("", snapshot.resolveProfile(null));
        assertEquals("", snapshot.resolveProfile(""));
    }

    @Test
    public void theFailClosedSentinelGrantsNothing() {
        ProfileSnapshot empty = ProfileSnapshot.empty();

        assertFalse(empty.isValid());
        assertEquals(0L, empty.generation());
        assertFalse(empty.isGloballyEnabled());
        assertFalse(empty.isProcessAllowed("com.example.recorder", "com.example.recorder"));
        assertEquals("", empty.captureOwner("com.example.recorder", "com.example.recorder"));
        assertEquals("", empty.resolveProfile("com.example.recorder"));
    }

    private static void assertRejected(ProfileSnapshot snapshot) {
        assertFalse(snapshot.isValid());
        assertEquals(0L, snapshot.generation());
        assertFalse(snapshot.isGloballyEnabled());
    }

    private static String control(String key, Object value) throws Exception {
        JSONObject envelope = valid();
        envelope.getJSONObject("control").put(key, value);
        return envelope.toString();
    }

    private static JSONObject preset(String latencyMode) throws Exception {
        return new JSONObject()
                .put("engine", new JSONObject().put("latencyMode", latencyMode))
                .put("modules", new JSONArray(Arrays.asList("gain")));
    }

    private static JSONObject valid() throws Exception {
        return new JSONObject()
                .put("schemaVersion", 2)
                .put("generation", 7)
                .put("profiles", new JSONObject().put("default", preset("LL")))
                .put("defaultProfileId", "default")
                .put("appBindings", new JSONObject())
                .put("whitelist", new JSONObject().put("com.example.recorder", true))
                .put("captureOwners", new JSONObject().put("com.example.recorder", "lsposed"))
                .put(
                        "control",
                        new JSONObject()
                                .put("masterEnabled", true)
                                .put("bypass", false)
                                .put("panicUntilEpochMs", 0)
                                .put("sidetoneEnabled", false)
                                .put("sidetoneGainDb", 0)
                                .put("engineMode", "compatibility"));
    }
}
