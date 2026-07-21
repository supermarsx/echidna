package com.echidna.app.data

import com.echidna.app.model.AccentColor
import com.echidna.app.model.CaptureOwner
import com.echidna.app.model.CaptureOwnerReason
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.ThemeMode
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Control-state behaviour of [ControlStateRepository]: the panic gate, the derived capture-owner
 * status, whitelist/binding mutations, and the coercion clamps that keep persisted settings inside
 * their honest ranges.
 *
 * The repository is a process singleton, so every test restores what it touched. Without a bound
 * [android.content.Context] the disk-persistence and service-push paths are guarded no-ops, which
 * isolates the in-memory state machine under test.
 *
 * `captureOwnerStatus` is republished by a `combine(...).stateIn(Dispatchers.Default)`, so reading
 * `.value` straight after a mutation is a race — [awaitOwner] bounds the wait instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ControlStateRepositoryStateTest {

    private val repo = ControlStateRepository

    private var originalMaster = true
    private var originalBypass = false
    private var originalEngineMode = DspEngineMode.NATIVE_FIRST
    private var originalWhitelist = emptyMap<String, Boolean>()
    private var originalBindings = emptyMap<String, String>()
    private lateinit var originalActiveId: String
    private lateinit var originalDefaultId: String
    private val createdPresets = mutableListOf<String>()
    private val createdProfiles = mutableListOf<String>()

    @Before
    fun setUp() {
        originalMaster = repo.masterEnabled.value
        originalBypass = repo.bypass.value
        originalEngineMode = repo.dspEngineMode.value
        originalWhitelist = repo.whitelistBindings.value.whitelist
        originalBindings = repo.whitelistBindings.value.appBindings
        originalActiveId = repo.activePreset.value.id
        originalDefaultId = repo.defaultPresetId.value
        repo.triggerPanic(0L)
    }

    @After
    fun tearDown() {
        repo.triggerPanic(0L)
        repo.setMasterEnabled(originalMaster)
        repo.setBypass(originalBypass)
        repo.setDspEngineMode(originalEngineMode)
        repo.whitelistBindings.value.whitelist.keys
            .filterNot(originalWhitelist::containsKey)
            .forEach { repo.updateWhitelist(it, false) }
        originalWhitelist.forEach { (pkg, enabled) -> repo.updateWhitelist(pkg, enabled) }
        repo.whitelistBindings.value.appBindings.keys
            .filterNot(originalBindings::containsKey)
            .forEach { repo.setAppPresetBinding(it, "") }
        createdProfiles.forEach(repo::deleteSettingsProfile)
        repo.selectPreset(originalActiveId)
        repo.setDefaultPreset(originalDefaultId)
        createdPresets.forEach(repo::deletePreset)
    }

    private fun preset(name: String, base: String? = null): String =
        repo.createPreset(name, null, base).also(createdPresets::add)

    private fun profile(name: String): String =
        checkNotNull(repo.createSettingsProfile(name)).also(createdProfiles::add)

    /**
     * Bounded wait for the Dispatchers.Default-backed `captureOwnerStatus` combine to catch up.
     * Matches on the whole status (owner AND reason) so a still-stale value can never satisfy it.
     */
    private fun awaitOwner(owner: CaptureOwner, reason: CaptureOwnerReason) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            val status = repo.captureOwnerStatus.value
            if (status.owner == owner && status.reason == reason) return
            Thread.sleep(2L)
        }
        fail("captureOwnerStatus never became $owner/$reason (was ${repo.captureOwnerStatus.value})")
    }

    /**
     * Bounded wait for `activePreset`, which is republished by the same Dispatchers.Default combine:
     * the id write is synchronous but the preset the UI reads back lands on another thread.
     */
    private fun awaitActivePreset(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            if (repo.activePreset.value.id == id) return
            Thread.sleep(2L)
        }
        fail("activePreset never became $id (was ${repo.activePreset.value.id})")
    }

    /** Bounded wait for `activePreset` to move OFF [id]; returns the id it settled on. */
    private fun awaitActivePresetOff(id: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            val current = repo.activePreset.value.id
            if (current != id) return current
            Thread.sleep(2L)
        }
        fail("activePreset never moved off $id")
        error("unreachable")
    }

    /** Publishes an authoritative policy with one enabled app so ownership can actually resolve. */
    private fun publishPolicyWithEnabledApp(pkg: String = "com.discord") {
        repo.setAppPresetBinding(pkg, repo.activePreset.value.id)
        repo.updateWhitelist(pkg, true)
        repo.setMasterEnabled(true)
        repo.setBypass(false)
    }

    // --- Panic gate ---------------------------------------------------------------------------

    @Test
    fun `panic disables capture ownership and clears itself when the hold expires`() {
        publishPolicyWithEnabledApp()
        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)

        repo.triggerPanic(300L)
        awaitOwner(CaptureOwner.NONE, CaptureOwnerReason.ENGINE_DISABLED)

        // The hold is time-bounded: it must release itself without any further user action.
        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)
    }

    @Test
    fun `a non-positive panic hold releases the gate instead of arming an immediate one`() {
        publishPolicyWithEnabledApp()
        repo.triggerPanic(60_000L)
        awaitOwner(CaptureOwner.NONE, CaptureOwnerReason.ENGINE_DISABLED)

        repo.triggerPanic(0L)

        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)
    }

    @Test
    fun `an enormous panic hold saturates instead of overflowing into the past`() {
        publishPolicyWithEnabledApp()
        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)

        // now + Long.MAX_VALUE would wrap negative, which reads as "panic already expired".
        repo.triggerPanic(Long.MAX_VALUE)

        awaitOwner(CaptureOwner.NONE, CaptureOwnerReason.ENGINE_DISABLED)
        repo.triggerPanic(0L)
        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)
    }

    @Test
    fun `panic does not overwrite the master and bypass the user set`() {
        repo.setMasterEnabled(true)
        repo.setBypass(false)

        repo.triggerPanic(60_000L)

        assertTrue("panic is a separate gate, not a master toggle", repo.masterEnabled.value)
        assertFalse("panic must not latch bypass", repo.bypass.value)
    }

    // --- Capture ownership derivation -----------------------------------------------------------

    @Test
    fun `capture ownership follows the engine mode and fails closed on every gate`() {
        publishPolicyWithEnabledApp()
        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)

        // Compatibility mode is the only mode that names the LSPosed shim as capture owner.
        repo.setDspEngineMode(DspEngineMode.COMPATIBILITY)
        awaitOwner(CaptureOwner.LSPOSED, CaptureOwnerReason.ACTIVE)

        repo.setMasterEnabled(false)
        awaitOwner(CaptureOwner.NONE, CaptureOwnerReason.ENGINE_DISABLED)

        repo.setMasterEnabled(true)
        repo.setBypass(true)
        awaitOwner(CaptureOwner.NONE, CaptureOwnerReason.ENGINE_DISABLED)
    }

    @Test
    fun `disabling the last whitelisted app leaves nothing owned`() {
        // Start from a known-empty enabled set: the singleton may carry entries from elsewhere.
        repo.whitelistBindings.value.whitelist.filterValues { it }.keys
            .forEach { repo.updateWhitelist(it, false) }
        publishPolicyWithEnabledApp("com.slack")
        awaitOwner(CaptureOwner.ZYGISK, CaptureOwnerReason.ACTIVE)

        repo.updateWhitelist("com.slack", false)

        awaitOwner(CaptureOwner.NONE, CaptureOwnerReason.NO_WHITELISTED_APPS)
    }

    // --- Whitelist and per-app bindings ----------------------------------------------------------

    @Test
    fun `a blank package name is rejected by both whitelist and binding mutations`() {
        val before = repo.whitelistBindings.value

        repo.updateWhitelist("", true)
        repo.updateWhitelist("   ", true)
        repo.setAppPresetBinding("", repo.activePreset.value.id)

        assertEquals("a blank key must never enter the published policy", before, repo.whitelistBindings.value)
    }

    @Test
    fun `an app binding to an unknown preset is refused rather than published`() {
        val pkg = "com.teamspeak3.client.android"

        repo.setAppPresetBinding(pkg, "no-such-preset-id")

        assertNull(
            "binding a package to a preset that does not exist would publish a dangling policy",
            repo.whitelistBindings.value.appBindings[pkg],
        )
    }

    @Test
    fun `an empty preset id clears an existing binding`() {
        val pkg = "com.wire"
        val target = preset("Binding Target")
        repo.setAppPresetBinding(pkg, target)
        assertEquals(target, repo.whitelistBindings.value.appBindings[pkg])

        repo.setAppPresetBinding(pkg, "")

        assertFalse(repo.whitelistBindings.value.appBindings.containsKey(pkg))
    }

    @Test
    fun `deleting a preset drops every app binding that pointed at it`() {
        val doomed = preset("Doomed Binding")
        val survivor = preset("Surviving Binding")
        repo.setAppPresetBinding("com.viber.voip", doomed)
        repo.setAppPresetBinding("com.skype.raider", survivor)

        repo.deletePreset(doomed)
        createdPresets.remove(doomed)

        assertFalse(
            "a binding to a deleted preset would leave the app with no resolvable profile",
            repo.whitelistBindings.value.appBindings.containsKey("com.viber.voip"),
        )
        assertEquals(survivor, repo.whitelistBindings.value.appBindings["com.skype.raider"])
    }

    // --- Preset selection ------------------------------------------------------------------------

    @Test
    fun `deleting the active preset re-homes the selection instead of leaving a dangling id`() {
        val scratch = preset("Deleted While Active")
        repo.selectPreset(scratch)
        awaitActivePreset(scratch)

        repo.deletePreset(scratch)
        createdPresets.remove(scratch)

        val rehomed = awaitActivePresetOff(scratch)
        assertTrue(
            "the active selection must land on a preset that still exists",
            repo.presets.value.any { it.id == rehomed },
        )
    }

    @Test
    fun `deleting the default preset re-homes the default`() {
        val scratch = preset("Deleted While Default")
        repo.setDefaultPreset(scratch)
        assertEquals(scratch, repo.defaultPresetId.value)

        repo.deletePreset(scratch)
        createdPresets.remove(scratch)

        assertNotEquals(scratch, repo.defaultPresetId.value)
        assertTrue(repo.presets.value.any { it.id == repo.defaultPresetId.value })
    }

    @Test
    fun `selecting or defaulting to an unknown preset is a no-op`() {
        val anchor = repo.presets.value.first().id
        repo.selectPreset(anchor)
        awaitActivePreset(anchor)
        val defaultBefore = repo.defaultPresetId.value

        repo.selectPreset("not-a-preset")
        repo.setDefaultPreset("not-a-preset")

        Thread.sleep(50L)
        assertEquals("an unknown id must not move the selection", anchor, repo.activePreset.value.id)
        assertEquals(defaultBefore, repo.defaultPresetId.value)
    }

    @Test
    fun `cyclePreset advances through the list and wraps at the end`() {
        val ids = repo.presets.value.map { it.id }
        repo.selectPreset(ids.first())
        awaitActivePreset(ids.first())

        repo.cyclePreset()
        awaitActivePreset(ids[1])

        repo.selectPreset(ids.last())
        awaitActivePreset(ids.last())
        repo.cyclePreset()
        // The cycle must wrap rather than run off the end of the list.
        awaitActivePreset(ids.first())
    }

    // --- Preset import / export -------------------------------------------------------------------

    @Test
    fun `importing a preset whose id already exists keeps both copies`() {
        val source = repo.presets.value.first()
        val json = checkNotNull(repo.exportPreset(source.id))

        val importedId = checkNotNull(repo.importPreset(json)).also(createdPresets::add)

        assertNotEquals("an id collision must not overwrite the existing preset", source.id, importedId)
        assertTrue(repo.presets.value.any { it.id == source.id })
        assertEquals(source.name, repo.presets.value.first { it.id == importedId }.name)
    }

    @Test
    fun `importing malformed preset json is rejected without touching the store`() {
        val before = repo.presets.value

        assertNull(repo.importPreset("not json at all"))
        assertNull(repo.importPreset("{}"))

        assertEquals(before, repo.presets.value)
    }

    @Test
    fun `exporting an unknown preset returns null instead of an empty document`() {
        assertNull(repo.exportPreset("no-such-preset"))
    }

    @Test
    fun `exportAllPresets emits every preset and each entry re-imports`() {
        val array = org.json.JSONArray(repo.exportAllPresets())

        assertEquals(repo.presets.value.size, array.length())
        val reimported = checkNotNull(repo.importPreset(array.getJSONObject(0).toString()))
            .also(createdPresets::add)
        assertEquals(repo.presets.value.first().name, repo.presets.value.first { it.id == reimported }.name)
    }

    @Test
    fun `sharePreset produces the same document as exportPreset`() {
        val id = repo.presets.value.first().id

        assertEquals(repo.exportPreset(id), repo.sharePreset(id))
        assertNull(repo.sharePreset("no-such-preset"))
    }

    // --- Settings profiles -------------------------------------------------------------------------

    @Test
    fun `a blank profile name is refused and a long one is trimmed and truncated`() {
        assertNull(repo.createSettingsProfile(""))
        assertNull(repo.createSettingsProfile("   "))

        val id = profile("  " + "n".repeat(120) + "  ")

        val created = repo.settingsProfiles.value.first { it.id == id }
        assertEquals(80, created.name.length)
        assertFalse(created.name.startsWith(" "))
    }

    @Test
    fun `applying or deleting an unknown profile reports failure`() {
        assertFalse(repo.applySettingsProfile("no-such-profile"))
        assertFalse(repo.deleteSettingsProfile("no-such-profile"))
        assertNull(repo.exportSettingsProfile("no-such-profile"))
    }

    @Test
    fun `deleting the active profile clears the active selection`() {
        val id = profile("Active Then Deleted")
        assertEquals(id, repo.activeSettingsProfileId.value)

        assertTrue(repo.deleteSettingsProfile(id))
        createdProfiles.remove(id)

        assertNull(repo.activeSettingsProfileId.value)
        assertTrue(repo.settingsProfiles.value.none { it.id == id })
    }

    @Test
    fun `deleting a non-active profile leaves the active selection alone`() {
        val active = profile("Stays Active")
        val other = profile("Removed")
        repo.applySettingsProfile(active)

        assertTrue(repo.deleteSettingsProfile(other))
        createdProfiles.remove(other)

        assertEquals(active, repo.activeSettingsProfileId.value)
    }

    @Test
    fun `any manual settings edit clears the active profile so the UI cannot claim a stale one`() {
        val id = profile("Edited Away")
        assertEquals(id, repo.activeSettingsProfileId.value)

        repo.setKeepScreenOn(!repo.keepScreenOn.value)

        assertNull("an edited profile is no longer the applied profile", repo.activeSettingsProfileId.value)
        repo.setKeepScreenOn(!repo.keepScreenOn.value)
    }

    @Test
    fun `completing onboarding persists without clearing the applied profile`() {
        val originalOnboarding = repo.onboardingComplete.value
        val id = profile("Survives Onboarding")
        try {
            repo.setOnboardingComplete(true)

            assertTrue(repo.onboardingComplete.value)
            assertEquals(
                "finishing first-run setup is not a settings edit and must not drop the profile",
                id,
                repo.activeSettingsProfileId.value,
            )
        } finally {
            repo.setOnboardingComplete(originalOnboarding)
        }
    }

    @Test
    fun `applying a profile restores its settings but never re-arms the onboarding wizard`() {
        val originalOnboarding = repo.onboardingComplete.value
        val originalTheme = repo.themeMode.value
        val originalAccent = repo.accentColor.value
        try {
            repo.setOnboardingComplete(false)
            repo.setThemeMode(ThemeMode.DARK)
            repo.setAccentColor(AccentColor.VIOLET)
            // A profile captured before the user finished setup carries onboardingComplete = false.
            val id = profile("Pre-Onboarding Snapshot")

            repo.setOnboardingComplete(true)
            repo.setThemeMode(ThemeMode.LIGHT)

            assertTrue(repo.applySettingsProfile(id))

            assertEquals(ThemeMode.DARK, repo.themeMode.value)
            assertTrue(
                "applying an old profile must not send the user back through first-run setup",
                repo.onboardingComplete.value,
            )
            assertEquals(id, repo.activeSettingsProfileId.value)
        } finally {
            repo.setThemeMode(originalTheme)
            repo.setAccentColor(originalAccent)
            repo.setOnboardingComplete(originalOnboarding)
        }
    }

    @Test
    fun `importing a profile whose id collides keeps the original`() {
        val id = profile("Collision Source")
        val json = checkNotNull(repo.exportSettingsProfile(id))

        val importedId = checkNotNull(repo.importSettingsProfile(json)).also(createdProfiles::add)

        assertNotEquals(id, importedId)
        assertTrue(repo.settingsProfiles.value.any { it.id == id })
        assertEquals(
            repo.settingsProfiles.value.first { it.id == id }.name,
            repo.settingsProfiles.value.first { it.id == importedId }.name,
        )
    }

    @Test
    fun `importing malformed profile json is rejected without adding a profile`() {
        val before = repo.settingsProfiles.value.size

        assertNull(repo.importSettingsProfile("}{"))

        assertEquals(before, repo.settingsProfiles.value.size)
    }

    @Test
    fun `exportCurrentSettings reflects live state and round-trips back through the store`() {
        val original = repo.latencyMode.value
        try {
            repo.setLatencyMode(LatencyMode.HIGH_QUALITY)
            val exported = repo.exportCurrentSettings()

            val decoded = checkNotNull(SettingsProfileSerializer.settingsFromJson(exported))
            assertEquals(LatencyMode.HIGH_QUALITY, decoded.latencyMode)
        } finally {
            repo.setLatencyMode(original)
        }
    }

    // --- Range clamping ---------------------------------------------------------------------------

    @Test
    fun `every user-facing numeric setting is clamped to its honest range`() {
        val originalSidetone = repo.sidetoneLevel.value
        val originalPanic = repo.panicHoldMinutes.value
        val originalLatency = repo.alertLatencyThresholdMs.value
        val originalXrun = repo.alertXrunThreshold.value
        val originalPoll = repo.statusPollIntervalSeconds.value
        try {
            repo.updateSidetone(-500f)
            assertEquals(-60f, repo.sidetoneLevel.value, 1e-3f)
            repo.updateSidetone(120f)
            assertEquals(-6f, repo.sidetoneLevel.value, 1e-3f)

            repo.setPanicHoldMinutes(0)
            assertEquals(1, repo.panicHoldMinutes.value)
            repo.setPanicHoldMinutes(9_999)
            assertEquals(60, repo.panicHoldMinutes.value)

            repo.setAlertLatencyThresholdMs(-1)
            assertEquals(5, repo.alertLatencyThresholdMs.value)
            repo.setAlertLatencyThresholdMs(10_000)
            assertEquals(250, repo.alertLatencyThresholdMs.value)

            repo.setAlertXrunThreshold(0)
            assertEquals(1, repo.alertXrunThreshold.value)
            repo.setAlertXrunThreshold(10_000)
            assertEquals(100, repo.alertXrunThreshold.value)

            repo.setStatusPollIntervalSeconds(0)
            assertEquals(1, repo.statusPollIntervalSeconds.value)
            repo.setStatusPollIntervalSeconds(600)
            assertEquals(10, repo.statusPollIntervalSeconds.value)
        } finally {
            repo.updateSidetone(originalSidetone)
            repo.setPanicHoldMinutes(originalPanic)
            repo.setAlertLatencyThresholdMs(originalLatency)
            repo.setAlertXrunThreshold(originalXrun)
            repo.setStatusPollIntervalSeconds(originalPoll)
        }
    }

    // --- Engine status derivation -------------------------------------------------------------------

    @Test
    fun `the engine never reads active while no native engine is installed`() {
        assertFalse(repo.engineStatus.value.nativeInstalled)

        repo.setMasterEnabled(true)
        repo.setBypass(false)

        assertFalse(
            "master-on must not fabricate an active engine when nothing is installed",
            repo.engineStatus.value.active,
        )
    }

    @Test
    fun `toggleMaster flips the persisted master switch in both directions`() {
        // The Quick Settings tile and the home-screen widget both drive this entry point.
        repo.setMasterEnabled(true)

        repo.toggleMaster()
        assertFalse(repo.masterEnabled.value)
        assertFalse("the toggle must reach persisted settings", repo.settingsState.value.masterEnabled)

        repo.toggleMaster()
        assertTrue(repo.masterEnabled.value)
        assertTrue(repo.settingsState.value.masterEnabled)
    }

    @Test
    fun `bypass is mirrored into engine status immediately, not a poll later`() {
        repo.setBypass(true)
        assertTrue(repo.engineStatus.value.bypass)
        assertFalse(repo.engineStatus.value.active)

        repo.setBypass(false)
        assertFalse(repo.engineStatus.value.bypass)
    }

    @Test
    fun `latency mode selection updates the reported target latency`() {
        val original = repo.latencyMode.value
        try {
            repo.setLatencyMode(LatencyMode.HIGH_QUALITY)
            assertEquals(LatencyMode.HIGH_QUALITY.targetMs, repo.engineStatus.value.latencyMs)

            repo.setLatencyMode(LatencyMode.LOW_LATENCY)
            assertEquals(LatencyMode.LOW_LATENCY.targetMs, repo.engineStatus.value.latencyMs)
        } finally {
            repo.setLatencyMode(original)
        }
    }

    @Test
    fun `quiescing for a module operation stops processing through the persisted controls`() {
        repo.setMasterEnabled(true)
        repo.setBypass(false)

        repo.quiesceEngineForModuleOp()

        assertFalse("a module op must not run against a live engine", repo.masterEnabled.value)
        assertTrue(repo.bypass.value)
        // Quiesce goes through the persisted control path, so it survives the reboot that
        // finishes a module install/uninstall.
        assertFalse(repo.settingsState.value.masterEnabled)
        assertTrue(repo.settingsState.value.bypass)
    }

    // --- Fail-closed behaviour without a bound control service ----------------------------------------

    @Test
    fun `every privileged operation degrades honestly while the service is unbound`() {
        assertFalse("an unbound service must never read as bound", repo.isServiceBound())
        assertNull("telemetry export has no source without a service", repo.exportTelemetry())
        assertNull("diagnostics export has no source without a service", repo.exportDiagnostics())

        // The offline install path is context-only, so it must stay usable with the service down.
        assertNotNull("the release source must not depend on a bound service", repo.releaseArtifactSource())

        // Fire-and-forget privileged requests must be silently dropped, never crash the caller.
        repo.installEngineModule("/data/local/tmp/echidna.zip")
        repo.uninstallEngineModule()
        repo.setLegacyPreprocessorEnabled(true)

        assertFalse(
            "the attachment gate must not flip optimistically without a service confirmation",
            repo.legacyPreprocessorState.value.enabled,
        )
    }

    @Test
    fun `unbound suspend operations report failure rather than a fabricated success`() =
        kotlinx.coroutines.runBlocking {
            assertFalse(repo.disableEngineModule())
            assertFalse(repo.rebootDevice())
            assertNull(repo.refreshModuleStatus())

            val bindings = repo.fetchWhitelistBindings()
            assertTrue(bindings.whitelist.isEmpty())
            assertTrue(bindings.appBindings.isEmpty())
            Unit
        }

    @Test
    fun `the compatibility probe reports an unavailable service instead of fabricated probe data`() {
        repo.runCompatibilityProbe()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (repo.compatibilityState.value == null && System.nanoTime() < deadline) {
            Thread.sleep(2L)
        }
        val result = repo.compatibilityState.value
        assertNotNull("the probe must settle rather than hang on null", result)

        assertTrue(result!!.selinuxStatus.contains("Unknown"))
        assertEquals(1, result.audioStack.size)
        assertFalse(
            "an unbound service must not report a supported probe",
            result.audioStack.single().supported,
        )
        assertTrue(result.notes.single().contains("control service"))
    }
}
