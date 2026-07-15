package com.echidna.app.ui.settings

import com.echidna.app.model.LegacyPreprocessorControlState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SettingsViewModelLegacyPreprocessorTest {

    @Test
    fun `service load failure and rebind states remain authoritative`() {
        val serviceState = MutableStateFlow(LegacyPreprocessorControlState())
        val viewModel = SettingsViewModel(
            legacyPreprocessorState = serviceState,
            legacyPreprocessorSetter = {},
        )

        assertFalse(viewModel.legacyPreprocessorState.value.loaded)

        serviceState.value = LegacyPreprocessorControlState(
            enabled = true,
            loaded = true,
            available = false,
            error = "Control service disconnected.",
        )
        assertTrue(viewModel.legacyPreprocessorState.value.enabled)
        assertFalse(viewModel.legacyPreprocessorState.value.available)
        assertEquals(
            "Control service disconnected.",
            viewModel.legacyPreprocessorState.value.error,
        )

        serviceState.value = LegacyPreprocessorControlState(
            enabled = false,
            loaded = true,
            available = true,
        )
        assertFalse(viewModel.legacyPreprocessorState.value.enabled)
        assertTrue(viewModel.legacyPreprocessorState.value.available)
    }

    @Test
    fun `toggle delegates without optimistically changing confirmed state`() {
        val serviceState = MutableStateFlow(
            LegacyPreprocessorControlState(
                enabled = false,
                loaded = true,
                available = true,
            ),
        )
        val requests = mutableListOf<Boolean>()
        val viewModel = SettingsViewModel(
            legacyPreprocessorState = serviceState,
            legacyPreprocessorSetter = requests::add,
        )

        viewModel.setLegacyPreprocessorEnabled(true)

        assertEquals(listOf(true), requests)
        assertFalse(viewModel.legacyPreprocessorState.value.enabled)
    }
}
