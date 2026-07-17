package com.echidna.app

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.ui.whitelist.WhitelistEditorScreen
import com.echidna.app.ui.whitelist.WhitelistEditorViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Per-App Whitelist "Only installed" suggestion filter on a real device.
 *
 * A curated suggestion that is definitely NOT installed on a bare emulator (WhatsApp) is used as the
 * probe: with the filter off it must be surfaced as a suggestion; with the filter on it must be
 * hidden. The preference is also proven durable across ViewModel instances, and the filter chip is
 * driven through the actual Compose UI.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WhitelistEditorInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var savedPref = true

    @Before
    fun captureAndSeedPref() {
        savedPref = prefs.getBoolean(KEY_ONLY_INSTALLED, true)
    }

    @After
    fun restorePref() {
        prefs.edit().putBoolean(KEY_ONLY_INSTALLED, savedPref).apply()
    }

    @Test
    fun onlyInstalledFilterHidesUninstalledSuggestions() {
        prefs.edit().putBoolean(KEY_ONLY_INSTALLED, true).apply()
        val vm = WhitelistEditorViewModel(app)
        awaitLoaded(vm)

        vm.setOnlyInstalled(false)
        awaitCondition { vm.entries.value.any { it.packageName == WHATSAPP } }
        assertTrue(
            "with the filter off, an uninstalled curated suggestion must be surfaced",
            vm.entries.value.any { it.packageName == WHATSAPP && it.suggested && !it.installed },
        )

        vm.setOnlyInstalled(true)
        awaitCondition { vm.entries.value.none { it.packageName == WHATSAPP } }
        assertFalse(
            "with the filter on, the uninstalled suggestion must be hidden",
            vm.entries.value.any { it.packageName == WHATSAPP },
        )
    }

    @Test
    fun onlyInstalledPreferencePersistsAcrossViewModels() {
        val first = WhitelistEditorViewModel(app)
        awaitLoaded(first)
        first.setOnlyInstalled(false)

        val second = WhitelistEditorViewModel(app)
        assertFalse(
            "a new ViewModel must read the persisted installed-only preference",
            second.onlyInstalled.value,
        )

        second.setOnlyInstalled(true)
        val third = WhitelistEditorViewModel(app)
        assertTrue(third.onlyInstalled.value)
    }

    @Test
    fun filterChipTogglesSuggestionScopeFromUi() {
        prefs.edit().putBoolean(KEY_ONLY_INSTALLED, true).apply()
        val vm = WhitelistEditorViewModel(app)
        awaitLoaded(vm)

        composeRule.setContent {
            MaterialTheme { WhitelistEditorScreen(viewModel = vm) }
        }

        composeRule.onNodeWithText("Per-App Whitelist").assertIsDisplayed()
        composeRule.onNodeWithText("Only apps installed on this device").assertIsDisplayed()

        composeRule.onNodeWithText("Only installed").performClick()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Showing all curated suggestions")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Showing all curated suggestions").assertIsDisplayed()
    }

    private fun awaitLoaded(vm: WhitelistEditorViewModel) {
        awaitCondition { !vm.loading.value && vm.entries.value.isNotEmpty() }
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val PREFS_NAME = "whitelist_editor_prefs"
        const val KEY_ONLY_INSTALLED = "only_installed_suggestions"
        const val TIMEOUT_MS = 5_000L
    }
}
