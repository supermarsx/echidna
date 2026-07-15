package com.echidna.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.model.LegacyPreprocessorControlState
import com.echidna.app.ui.settings.LegacyPreprocessorSection
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class LegacyPreprocessorSettingsSectionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unavailableStateIsDisabledAndExplainsExperimentalLimits() {
        composeRule.setContent {
            MaterialTheme {
                LegacyPreprocessorSection(
                    state = LegacyPreprocessorControlState(),
                    onEnabledChange = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(TITLE)
            .assertIsNotEnabled()
            .assertIsOff()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Unavailable; last confirmed off",
                ),
            )
        composeRule.onNodeWithText("does not prove the effect is loaded", substring = true)
            .assertExists()
        composeRule.onNodeWithText("trusted, explicitly whitelisted user 0", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Stable-AIDL-only devices are unsupported", substring = true)
            .assertExists()
        composeRule.onNodeWithText("not an SDK-level compatibility verdict", substring = true)
            .assertExists()
    }

    @Test
    fun confirmedStateIsAccessibleAndToggleRequestsAServiceUpdate() {
        val requested = AtomicBoolean(false)
        val state = mutableStateOf(
            LegacyPreprocessorControlState(
                enabled = false,
                loaded = true,
                available = true,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                LegacyPreprocessorSection(
                    state = state.value,
                    onEnabledChange = requested::set,
                )
            }
        }

        composeRule.onNodeWithContentDescription(TITLE)
            .assertIsEnabled()
            .assertIsOff()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Off; confirmed by control service",
                ),
            )
            .performClick()
        composeRule.runOnIdle { assertTrue(requested.get()) }

        composeRule.runOnIdle {
            state.value = LegacyPreprocessorControlState(
                enabled = true,
                loaded = true,
                available = false,
                error = "Control service disconnected.",
            )
        }
        composeRule.onNodeWithContentDescription(TITLE)
            .assertIsNotEnabled()
            .assertIsOn()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Unavailable; last confirmed on",
                ),
            )
        composeRule.onNodeWithText("Control service disconnected.").assertExists()
    }

    private companion object {
        private const val TITLE = "Legacy AudioFlinger preprocessor (experimental)"
    }
}
