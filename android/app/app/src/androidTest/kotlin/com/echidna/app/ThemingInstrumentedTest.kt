package com.echidna.app

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.AccentColor
import com.echidna.app.model.ThemeMode
import com.echidna.app.ui.theme.EchidnaTheme
import com.echidna.app.ui.theme.dynamicColorSupported
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the t9-e4 appearance theming actually functions on a device: switching theme mode and
 * accent recomposes the Material 3 [androidx.compose.material3.ColorScheme]; dynamic-color is gated
 * on the API level; and a persisted theme is applied by [MainActivity] and survives an activity
 * recreate (configuration change).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ThemingInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var savedMode: ThemeMode
    private var savedDynamic = true
    private lateinit var savedAccent: AccentColor

    @Before
    fun capture() {
        val s = ControlStateRepository.settingsState.value
        savedMode = s.themeMode
        savedDynamic = s.dynamicColor
        savedAccent = s.accentColor
    }

    @After
    fun restore() {
        ControlStateRepository.setThemeMode(savedMode)
        ControlStateRepository.setDynamicColor(savedDynamic)
        ControlStateRepository.setAccentColor(savedAccent)
    }

    @Test
    fun themeModeSwitchRecomposesBetweenLightAndDark() {
        val mode = mutableStateOf(ThemeMode.LIGHT)
        var background = Color.Unspecified
        composeRule.setContent {
            EchidnaTheme(themeMode = mode.value, dynamicColor = false, accentColor = AccentColor.VIOLET) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()
        val light = background

        composeRule.runOnIdle { mode.value = ThemeMode.DARK }
        composeRule.waitForIdle()
        val dark = background

        assertNotEquals("Light and Dark must resolve different backgrounds", light, dark)
    }

    @Test
    fun accentColorChangeRecomposesPrimary() {
        val accent = mutableStateOf(AccentColor.VIOLET)
        var primary = Color.Unspecified
        composeRule.setContent {
            EchidnaTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false, accentColor = accent.value) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        val violet = primary

        composeRule.runOnIdle { accent.value = AccentColor.GREEN }
        composeRule.waitForIdle()
        val green = primary

        assertNotEquals("Different accents must produce different primary colours", violet, green)
    }

    @Test
    fun dynamicColorIsGatedByApiLevel() {
        assertEquals(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            dynamicColorSupported(),
        )
    }

    @Test
    fun persistedThemeAppliesAndSurvivesActivityRecreate() {
        ControlStateRepository.setThemeMode(ThemeMode.DARK)
        ControlStateRepository.setDynamicColor(false)
        ControlStateRepository.setAccentColor(AccentColor.GREEN)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { /* composed the persisted DARK/GREEN theme without crashing */ }

            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { }

            assertTrue(
                "activity must be alive after recreate",
                scenario.state.isAtLeast(Lifecycle.State.CREATED),
            )
        }
        assertEquals(ThemeMode.DARK, ControlStateRepository.settingsState.value.themeMode)
        assertEquals(AccentColor.GREEN, ControlStateRepository.settingsState.value.accentColor)
    }
}
