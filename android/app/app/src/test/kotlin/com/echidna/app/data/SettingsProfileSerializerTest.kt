package com.echidna.app.data

import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.SettingsProfile
import com.echidna.app.model.SettingsState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsProfileSerializerTest {

    private fun fullSettings(): SettingsState =
        SettingsState(
            startWithSystem = true,
            autoStartEngine = true,
            restoreLastProfile = false,
            engineMode = DspEngineMode.COMPATIBILITY,
            latencyMode = LatencyMode.HIGH_QUALITY,
            sidetoneEnabled = true,
            sidetoneLevelDb = -18f,
            debugMode = true,
            telemetryOptIn = true,
            verboseLogging = true,
            failClosed = false,
            autoBypassOnError = false,
            panicHoldMinutes = 12,
            persistentNotification = false,
            quickControlsEnabled = false,
            widgetControlsEnabled = false,
            showInstallAlerts = false,
            showBridgeAlerts = false,
            showHardwareAlerts = false,
            showInstallMixupAlerts = false,
            alertLatencyThresholdMs = 75,
            alertXrunThreshold = 9,
            remindCompatibilityProbe = false,
            masterEnabled = false,
            bypass = true,
            defaultPresetId = "preset-a"
        )

    @Test
    fun `profile json round trips all settings groups`() {
        val profile = SettingsProfile(
            id = "11111111-1111-1111-1111-111111111111",
            name = "Travel",
            createdAtEpochMs = 100,
            updatedAtEpochMs = 200,
            settings = fullSettings()
        )

        val json = SettingsProfileSerializer.profileToJson(profile)
        val root = JSONObject(json)
        assertEquals("echidna.settings.profile", root.getString("kind"))
        assertTrue(root.getJSONObject("settings").has("startup"))
        assertTrue(root.getJSONObject("settings").has("engine"))
        assertTrue(root.getJSONObject("settings").has("diagnostics"))
        assertTrue(root.getJSONObject("settings").has("safety"))
        assertTrue(root.getJSONObject("settings").has("control"))
        assertTrue(root.getJSONObject("settings").has("alerts"))

        val restored = SettingsProfileSerializer.profileFromJson(json)
        assertNotNull(restored)
        assertEquals(profile.id, restored!!.id)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.settings, restored.settings)
    }

    @Test
    fun `raw settings json imports as a profile`() {
        val json = SettingsProfileSerializer.settingsToJson(fullSettings())
        val profile = SettingsProfileSerializer.profileFromJson(json)

        assertNotNull(profile)
        assertEquals("Imported Settings", profile!!.name)
        assertEquals(DspEngineMode.COMPATIBILITY, profile.settings.engineMode)
        assertEquals(LatencyMode.HIGH_QUALITY, profile.settings.latencyMode)
    }

    @Test
    fun `settings parser clamps bounded numeric fields`() {
        val settings = SettingsProfileSerializer.settingsFromJson(
            """
            {
              "engine": {"sidetoneLevelDb": 20},
              "safety": {"panicHoldMinutes": 500},
              "alerts": {"alertLatencyThresholdMs": 999, "alertXrunThreshold": 0}
            }
            """.trimIndent()
        )

        assertNotNull(settings)
        assertEquals(-6f, settings!!.sidetoneLevelDb, 1e-3f)
        assertEquals(60, settings.panicHoldMinutes)
        assertEquals(250, settings.alertLatencyThresholdMs)
        assertEquals(1, settings.alertXrunThreshold)
    }

    @Test
    fun `store json preserves profiles and active id`() {
        val profile = SettingsProfile(
            id = "22222222-2222-2222-2222-222222222222",
            name = "Quiet",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            settings = fullSettings()
        )
        val store = SettingsProfileStore(
            settings = SettingsState(startWithSystem = true),
            profiles = listOf(profile),
            activeProfileId = profile.id
        )

        val restored = SettingsProfileSerializer.storeFromJson(
            SettingsProfileSerializer.storeToJson(store)
        )

        assertNotNull(restored)
        assertEquals(store.settings.startWithSystem, restored!!.settings.startWithSystem)
        assertEquals(profile.id, restored.activeProfileId)
        assertEquals(profile.name, restored.profiles.single().name)
    }

    @Test
    fun `profile import rejects malformed json`() {
        assertNull(SettingsProfileSerializer.profileFromJson("not json"))
        assertNull(SettingsProfileSerializer.profileFromJson("""{"name":"Missing settings"}"""))
    }
}
