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
        // Same authoritative-poll race as the toggle test below.
        retryingThroughAuthoritativePoll {
            repo.setMasterEnabled(true)
            if (!repo.masterEnabled.value) return@retryingThroughAuthoritativePoll false
            repo.setMasterEnabled(false)
            assertFalse(repo.masterEnabled.value)
            true
        }
    }

    @Test
    fun toggleMasterFlipsTheTileSource() {
        // The repository's status-poll loop mirrors the bound control service's state every
        // cycle ("global control state is authoritative"), so once the in-process service is
        // bound it can rewrite masterEnabled between a toggle and the assertion — the local
        // push back to the service is asynchronous, so the poll can still read the old value.
        // Retry the whole sequence: an attempt the poll interrupted proves nothing either way,
        // while a clean pass still pins the toggle contract the tile mirrors.
        retryingThroughAuthoritativePoll {
            repo.setMasterEnabled(true)
            if (!repo.masterEnabled.value) return@retryingThroughAuthoritativePoll false
            repo.toggleMaster()
            if (repo.masterEnabled.value) return@retryingThroughAuthoritativePoll false
            repo.toggleMaster()
            assertTrue("second toggle turns the tile back on", repo.masterEnabled.value)
            true
        }
    }

    /**
     * Runs [attempt] until it reports a clean, uninterrupted observation, or fails the test once
     * [ATTEMPTS] consecutive attempts were all clobbered by the authoritative status poll. Only a
     * genuinely broken toggle contract can exhaust every attempt: a working one needs just a
     * single window between two poll cycles, which are seconds apart.
     */
    private fun retryingThroughAuthoritativePoll(attempt: () -> Boolean) {
        repeat(ATTEMPTS) { index ->
            if (attempt()) return
            if (index < ATTEMPTS - 1) Thread.sleep(RETRY_BACKOFF_MS)
        }
        throw AssertionError(
            "toggleMaster never flipped masterEnabled cleanly in $ATTEMPTS attempts; " +
                "the toggle contract the QS tile mirrors is broken"
        )
    }

    private companion object {
        const val ATTEMPTS = 5
        const val RETRY_BACKOFF_MS = 250L
    }
}
