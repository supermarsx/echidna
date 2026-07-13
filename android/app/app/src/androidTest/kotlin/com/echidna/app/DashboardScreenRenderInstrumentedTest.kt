package com.echidna.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.ui.dashboard.DashboardScreen
import com.echidna.app.ui.dashboard.DashboardViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke test: the dashboard — a key screen — actually composes and renders
 * its core controls from the [DashboardViewModel]'s default state. Guards against the
 * kind of composition/wiring breakage that only shows up when the screen is laid out.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DashboardScreenRenderInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardRendersCoreSections() {
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(viewModel = DashboardViewModel())
            }
        }
        composeRule.onNodeWithText("Voice processing").assertIsDisplayed()
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Preset"))
        composeRule.onNodeWithText("Preset").assertIsDisplayed()
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(
            hasText("Latency Mode")
        )
        composeRule.onNodeWithText("Latency Mode").assertIsDisplayed()
    }
}
