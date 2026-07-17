package com.echidna.app.data

import com.echidna.app.model.AccentColor
import com.echidna.app.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsRepositoryAppearanceTest {

    private val repo = ControlStateRepository

    @Test
    fun `appearance setters update and clamp the settings state`() {
        val original = repo.settingsState.value
        try {
            repo.setThemeMode(ThemeMode.LIGHT)
            repo.setDynamicColor(false)
            repo.setAccentColor(AccentColor.GREEN)
            repo.setStatusPollIntervalSeconds(6)
            repo.setKeepScreenOn(true)

            val state = repo.settingsState.value
            assertEquals(ThemeMode.LIGHT, state.themeMode)
            assertEquals(false, state.dynamicColor)
            assertEquals(AccentColor.GREEN, state.accentColor)
            assertEquals(6, state.statusPollIntervalSeconds)
            assertEquals(true, state.keepScreenOn)

            // Out-of-range poll interval is clamped to the documented 1..10 window.
            repo.setStatusPollIntervalSeconds(99)
            assertEquals(10, repo.settingsState.value.statusPollIntervalSeconds)
            repo.setStatusPollIntervalSeconds(0)
            assertEquals(1, repo.settingsState.value.statusPollIntervalSeconds)
        } finally {
            repo.setThemeMode(original.themeMode)
            repo.setDynamicColor(original.dynamicColor)
            repo.setAccentColor(original.accentColor)
            repo.setStatusPollIntervalSeconds(original.statusPollIntervalSeconds)
            repo.setKeepScreenOn(original.keepScreenOn)
        }
    }

    @Test
    fun `appearance choices survive a profile save and re-apply`() {
        val original = repo.settingsState.value
        repo.setThemeMode(ThemeMode.DARK)
        repo.setAccentColor(AccentColor.ROSE)
        repo.setDynamicColor(false)
        val profileId = repo.createSettingsProfile("Appearance Snapshot")

        try {
            // Mutate away from the snapshot, then re-apply it.
            repo.setThemeMode(ThemeMode.LIGHT)
            repo.setAccentColor(AccentColor.BLUE)
            repo.setDynamicColor(true)

            repo.applySettingsProfile(profileId!!)
            val restored = repo.settingsState.value
            assertEquals(ThemeMode.DARK, restored.themeMode)
            assertEquals(AccentColor.ROSE, restored.accentColor)
            assertEquals(false, restored.dynamicColor)
        } finally {
            profileId?.let { repo.deleteSettingsProfile(it) }
            repo.setThemeMode(original.themeMode)
            repo.setAccentColor(original.accentColor)
            repo.setDynamicColor(original.dynamicColor)
        }
    }
}
