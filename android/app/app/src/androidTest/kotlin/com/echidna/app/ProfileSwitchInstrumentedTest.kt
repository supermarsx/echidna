package com.echidna.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.data.ControlStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Profile-switch flow on a real device. The [ControlStateRepository] active-preset
 * StateFlow is derived asynchronously (combine + stateIn), so switches are asserted by
 * polling. Exercises the app's preset store against real Android storage + coroutines.
 *
 * Cleanup deliberately re-homes the active preset onto a survivor BEFORE deleting a
 * temporary preset: deleting the currently-active preset briefly leaves the derived
 * `activePreset` combine pointing at a missing id (ControlStateRepository.kt:117 uses
 * `first`, not `firstOrNull`), which throws on the coroutine thread and crashes the
 * process. That is a real product bug (reported separately, owner e7); these tests
 * avoid tripping it so the switch behaviour itself can be verified.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProfileSwitchInstrumentedTest {

    private val repo = ControlStateRepository

    @Before
    fun setUp() {
        // Idempotent: EchidnaApplication also initializes this; guarded no-op if already done.
        repo.initialize(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun awaitActivePresetId(expected: String, timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (repo.activePreset.value.id == expected) return true
            Thread.sleep(25)
        }
        return repo.activePreset.value.id == expected
    }

    /** Re-home active onto a survivor, let the combine settle, then delete [id]. */
    private fun safeDelete(id: String) {
        val survivor = repo.presets.value.firstOrNull { it.id != id } ?: return
        repo.selectPreset(survivor.id)
        awaitActivePresetId(survivor.id)
        repo.deletePreset(id)
    }

    @Test
    fun selectingAPresetUpdatesTheActivePreset() {
        val newId = repo.createPreset("IT Switch Target", null, null)
        try {
            repo.selectPreset(newId)
            assertTrue("active preset should switch to the created preset", awaitActivePresetId(newId))
            assertEquals("IT Switch Target", repo.activePreset.value.name)
        } finally {
            safeDelete(newId)
        }
    }

    @Test
    fun cyclePresetLandsOnAValidPreset() {
        val extra = repo.createPreset("IT Cycle Extra", null, null)
        try {
            repo.cyclePreset()
            // Whatever it lands on, the derived active preset must remain a valid member.
            Thread.sleep(200)
            val active = repo.activePreset.value
            assertNotNull(active)
            assertTrue("cycle must land on a real preset", repo.presets.value.any { it.id == active.id })
            assertTrue(repo.presets.value.size > 1)
        } finally {
            safeDelete(extra)
        }
    }

    @Test
    fun createdPresetSurvivesInTheStore() {
        val before = repo.presets.value.size
        val id = repo.createPreset("IT Persist", "note", null)
        try {
            assertEquals(before + 1, repo.presets.value.size)
            val created = repo.presets.value.firstOrNull { it.id == id }
            assertNotNull(created)
            assertEquals("note", created!!.description)
        } finally {
            safeDelete(id)
        }
    }
}
