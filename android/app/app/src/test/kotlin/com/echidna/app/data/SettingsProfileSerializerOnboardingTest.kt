package com.echidna.app.data

import com.echidna.app.model.SettingsState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the first-run onboarding flag (t14) survives the settings serialization round-trip. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsProfileSerializerOnboardingTest {

    @Test
    fun onboardingComplete_roundTripsTrue() {
        val json = SettingsProfileSerializer.settingsToJson(SettingsState(onboardingComplete = true))
        val restored = SettingsProfileSerializer.settingsFromJson(json)
        assertTrue(restored!!.onboardingComplete)
    }

    @Test
    fun onboardingComplete_roundTripsFalse() {
        val json = SettingsProfileSerializer.settingsToJson(SettingsState(onboardingComplete = false))
        val restored = SettingsProfileSerializer.settingsFromJson(json)
        assertFalse(restored!!.onboardingComplete)
    }

    @Test
    fun onboardingComplete_isWrittenUnderOnboardingSection() {
        val json = SettingsProfileSerializer.settingsToJson(SettingsState(onboardingComplete = true))
        val obj = JSONObject(json)
        assertTrue(obj.getJSONObject("onboarding").getBoolean("complete"))
    }

    @Test
    fun legacyJson_withoutOnboardingSection_defaultsToFalse() {
        // A settings blob written before t14 has no "onboarding" object; it must decode to false so
        // an existing install is not treated as having completed a wizard it never saw.
        val legacy = JSONObject().apply {
            put("appearance", JSONObject().put("themeMode", "dark"))
        }.toString()
        val restored = SettingsProfileSerializer.settingsFromJson(legacy)
        assertFalse(restored!!.onboardingComplete)
    }

    @Test
    fun storeRoundTrip_preservesOnboardingComplete() {
        val store = SettingsProfileStore(
            settings = SettingsState(onboardingComplete = true),
            profiles = emptyList(),
            activeProfileId = null,
        )
        val restored = SettingsProfileSerializer.storeFromJson(SettingsProfileSerializer.storeToJson(store))
        assertTrue(restored!!.settings.onboardingComplete)
    }
}
