package com.echidna.app.data

import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.SettingsProfile
import com.echidna.app.model.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsRepositoryProfileTest {

    private val repo = ControlStateRepository

    @Test
    fun `settings profile create apply and delete restores saved state`() {
        val original = repo.settingsState.value
        val profileId = repo.createSettingsProfile("Saved Settings")
        assertNotNull(profileId)

        try {
            repo.setStartWithSystem(!original.startWithSystem)
            repo.setDspEngineMode(DspEngineMode.COMPATIBILITY)
            repo.setLatencyMode(LatencyMode.HIGH_QUALITY)
            repo.setSidetoneEnabled(!original.sidetoneEnabled)
            repo.updateSidetone(-12f)
            repo.setFailClosed(!original.failClosed)
            repo.setNotificationEnabled(!original.persistentNotification)

            assertFalse("manual edits should clear the active profile", repo.activeSettingsProfileId.value == profileId)
            assertTrue(repo.applySettingsProfile(profileId!!))

            val restored = repo.settingsState.value
            assertEquals(original.startWithSystem, restored.startWithSystem)
            assertEquals(original.engineMode, restored.engineMode)
            assertEquals(original.latencyMode, restored.latencyMode)
            assertEquals(original.sidetoneEnabled, restored.sidetoneEnabled)
            assertEquals(original.sidetoneLevelDb, restored.sidetoneLevelDb, 1e-3f)
            assertEquals(original.failClosed, restored.failClosed)
            assertEquals(original.persistentNotification, restored.persistentNotification)
            assertEquals(profileId, repo.activeSettingsProfileId.value)
        } finally {
            profileId?.let { repo.deleteSettingsProfile(it) }
            restoreSettings(original)
        }
    }

    @Test
    fun `settings profile import and export round trip through repository`() {
        val profile = SettingsProfile(
            id = "33333333-3333-3333-3333-333333333333",
            name = "Portable",
            createdAtEpochMs = 10,
            updatedAtEpochMs = 20,
            settings = SettingsState(
                startWithSystem = true,
                engineMode = DspEngineMode.LOW_LATENCY,
                latencyMode = LatencyMode.BALANCED,
                persistentNotification = false
            )
        )
        val importedId = repo.importSettingsProfile(SettingsProfileSerializer.profileToJson(profile))
        assertNotNull(importedId)

        try {
            val exported = repo.exportSettingsProfile(importedId!!)
            assertNotNull(exported)
            val restored = SettingsProfileSerializer.profileFromJson(exported!!)
            assertNotNull(restored)
            assertEquals("Portable", restored!!.name)
            assertEquals(DspEngineMode.LOW_LATENCY, restored.settings.engineMode)
            assertEquals(LatencyMode.BALANCED, restored.settings.latencyMode)
        } finally {
            importedId?.let { repo.deleteSettingsProfile(it) }
        }
    }

    @Test
    fun `restored active settings profile honours restore-last-profile`() {
        val profile = SettingsProfile(
            id = "44444444-4444-4444-4444-444444444444",
            name = "Startup",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            settings = SettingsState()
        )
        val enabled = SettingsProfileStore(
            settings = SettingsState(restoreLastProfile = true),
            profiles = listOf(profile),
            activeProfileId = profile.id
        )

        assertEquals(profile.id, repo.restoredActiveSettingsProfileId(enabled))
        assertNull(
            repo.restoredActiveSettingsProfileId(
                enabled.copy(settings = enabled.settings.copy(restoreLastProfile = false))
            )
        )
        val missing = enabled.copy(activeProfileId = "missing")
        assertNull(repo.restoredActiveSettingsProfileId(missing))
    }

    private fun restoreSettings(settings: SettingsState) {
        repo.setStartWithSystem(settings.startWithSystem)
        repo.setAutoStartEngine(settings.autoStartEngine)
        repo.setRestoreLastProfile(settings.restoreLastProfile)
        repo.setDspEngineMode(settings.engineMode)
        repo.setLatencyMode(settings.latencyMode)
        repo.setSidetoneEnabled(settings.sidetoneEnabled)
        repo.updateSidetone(settings.sidetoneLevelDb)
        repo.setDebugMode(settings.debugMode)
        repo.setTelemetryOptIn(settings.telemetryOptIn)
        repo.setVerboseLogging(settings.verboseLogging)
        repo.setFailClosed(settings.failClosed)
        repo.setAutoBypassOnError(settings.autoBypassOnError)
        repo.setPanicHoldMinutes(settings.panicHoldMinutes)
        repo.setNotificationEnabled(settings.persistentNotification)
        repo.setQuickControlsEnabled(settings.quickControlsEnabled)
        repo.setWidgetControlsEnabled(settings.widgetControlsEnabled)
        repo.setMasterEnabled(settings.masterEnabled)
        repo.setBypass(settings.bypass)
    }
}
