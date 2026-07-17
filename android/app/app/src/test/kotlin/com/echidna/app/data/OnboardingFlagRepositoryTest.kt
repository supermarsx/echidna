package com.echidna.app.data

import com.echidna.app.model.SettingsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repository-level behavior of the first-run onboarding flag (t14). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OnboardingFlagRepositoryTest {

    private val repo = ControlStateRepository

    @Test
    fun `setOnboardingComplete flips the persisted flag both ways`() {
        val original = repo.onboardingComplete.value
        try {
            repo.setOnboardingComplete(true)
            assertTrue(repo.onboardingComplete.value)
            assertTrue(repo.settingsState.value.onboardingComplete)

            repo.setOnboardingComplete(false)
            assertFalse(repo.onboardingComplete.value)
            assertFalse(repo.settingsState.value.onboardingComplete)
        } finally {
            repo.setOnboardingComplete(original)
        }
    }

    @Test
    fun `completing onboarding does not clear the active settings profile`() {
        val original = repo.onboardingComplete.value
        val profileId = repo.createSettingsProfile("Keep Active")
        assertNotNull(profileId)
        try {
            // Applying selects the profile as active; completing onboarding must leave it selected,
            // since onboarding completion is not a user-preference edit.
            repo.applySettingsProfile(profileId!!)
            repo.setOnboardingComplete(true)
            assertTrue(repo.onboardingComplete.value)
            assertTrue(
                "run-setup completion must not deselect the active profile",
                repo.activeSettingsProfileId.value == profileId,
            )
        } finally {
            profileId?.let { repo.deleteSettingsProfile(it) }
            repo.setOnboardingComplete(original)
        }
    }

    @Test
    fun `applying a settings profile preserves the live onboarding flag`() {
        val original = repo.onboardingComplete.value
        // Capture a profile while onboarding is marked complete...
        repo.setOnboardingComplete(true)
        val profileId = repo.createSettingsProfile("Snapshot Complete")
        assertNotNull(profileId)
        try {
            // ...then re-arm the wizard and apply that profile. The device-scoped flag must be
            // preserved (stay false), NOT overwritten by the profile's stored true.
            repo.setOnboardingComplete(false)
            assertTrue(repo.applySettingsProfile(profileId!!))
            assertFalse(
                "profile apply must not resurrect a completed-onboarding flag",
                repo.onboardingComplete.value,
            )
        } finally {
            profileId?.let { repo.deleteSettingsProfile(it) }
            repo.setOnboardingComplete(original)
        }
    }

    @Test
    fun `applying an old profile does not re-trigger a completed wizard`() {
        val original = repo.onboardingComplete.value
        // A pre-t14 profile decodes with onboardingComplete=false (the default).
        val legacyId = repo.importSettingsProfile(
            SettingsProfileSerializer.settingsToJson(SettingsState(onboardingComplete = false))
                .let { settingsJson ->
                    // Wrap the bare settings as a profile the importer accepts.
                    org.json.JSONObject()
                        .put("kind", "echidna.settings.profile")
                        .put("name", "Legacy")
                        .put("settings", org.json.JSONObject(settingsJson))
                        .toString()
                }
        )
        assertNotNull(legacyId)
        try {
            repo.setOnboardingComplete(true)
            assertTrue(repo.applySettingsProfile(legacyId!!))
            assertTrue(
                "an old profile must not send a completed user back into the wizard",
                repo.onboardingComplete.value,
            )
        } finally {
            legacyId?.let { repo.deleteSettingsProfile(it) }
            repo.setOnboardingComplete(original)
        }
    }
}
