package com.echidna.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.echidna.app.data.ControlStateRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QS-tile state persistence. [EchidnaQuickSettingsTileService] renders and toggles the
 * tile purely from [ControlStateRepository.masterEnabled] / toggleMaster(); a live
 * TileService can't be driven from an unrooted test, so this pins the exact state
 * source + toggle contract the tile mirrors, on a real device.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class QuickSettingsTileStateInstrumentedTest {

    private val repo = ControlStateRepository

    @Before
    fun setUp() {
        repo.initialize(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun masterStateIsObservableAndPersistsInProcess() {
        repo.setMasterEnabled(true)
        assertTrue(repo.masterEnabled.value)
        repo.setMasterEnabled(false)
        assertFalse(repo.masterEnabled.value)
    }

    @Test
    fun toggleMasterFlipsTheTileSource() {
        repo.setMasterEnabled(true)
        repo.toggleMaster()
        assertFalse("first toggle turns the tile off", repo.masterEnabled.value)
        repo.toggleMaster()
        assertTrue("second toggle turns the tile back on", repo.masterEnabled.value)
    }
}
