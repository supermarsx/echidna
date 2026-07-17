package com.echidna.app.ui.whitelist

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the installed-only toggle defaults ON and persists across ViewModel instances (t8-e8).
 * The control-service singleton is uninitialised here, so entry-building degrades to empty — this
 * test targets only the persisted toggle contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WhitelistEditorViewModelTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `installed-only filter defaults on`() {
        val vm = WhitelistEditorViewModel(app())
        assertTrue(vm.onlyInstalled.value)
    }

    @Test
    fun `toggle state persists across view-model instances`() {
        val first = WhitelistEditorViewModel(app())
        assertTrue(first.onlyInstalled.value)

        first.setOnlyInstalled(false)
        assertFalse(first.onlyInstalled.value)

        // A brand-new ViewModel reads the persisted preference back out of SharedPreferences.
        val second = WhitelistEditorViewModel(app())
        assertFalse("filter preference should survive re-creation", second.onlyInstalled.value)

        // Flip it back and confirm the round-trip both ways.
        second.setOnlyInstalled(true)
        val third = WhitelistEditorViewModel(app())
        assertTrue(third.onlyInstalled.value)
    }
}
