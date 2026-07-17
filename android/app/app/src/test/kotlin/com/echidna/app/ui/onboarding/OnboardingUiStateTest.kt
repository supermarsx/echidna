package com.echidna.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the wizard's step math and the recovery gate — no Android, no Robolectric.
 */
class OnboardingUiStateTest {

    private val ordered = OnboardingStep.ordered

    @Test
    fun initialState_startsOnWelcome_atStepOne() {
        val state = OnboardingUiState()
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertEquals(1, state.stepNumber)
        assertEquals(ordered.size, state.totalSteps)
        assertTrue(state.isFirst)
        assertFalse(state.isLast)
        assertFalse(state.finished)
    }

    @Test
    fun advanced_movesForward_untilLastStepThenFinishes() {
        var state = OnboardingUiState()
        // Walk all the way to DONE, acknowledging recovery when we reach it.
        while (!state.isLast) {
            if (state.step == OnboardingStep.RECOVERY) {
                state = state.withRecoveryAck(true)
            }
            val before = state.index
            state = state.advanced()
            assertEquals(before + 1, state.index)
        }
        assertEquals(OnboardingStep.DONE, state.step)
        assertFalse(state.finished)
        // Advancing off the last step finishes the wizard without changing the index.
        val finished = state.advanced()
        assertTrue(finished.finished)
        assertTrue(finished.isLast)
    }

    @Test
    fun back_isNoOpOnFirstStep_andStepsBackwardOtherwise() {
        val first = OnboardingUiState()
        assertEquals(first, first.back())

        val second = first.advanced()
        assertEquals(OnboardingStep.PERMISSIONS, second.step)
        assertEquals(OnboardingStep.WELCOME, second.back().step)
    }

    @Test
    fun recoveryStep_blocksAdvance_untilAcknowledged() {
        // Jump to the recovery step.
        var state = OnboardingUiState().goTo(ordered.indexOf(OnboardingStep.RECOVERY))
        assertEquals(OnboardingStep.RECOVERY, state.step)
        assertFalse(state.canAdvance)

        // advanced() is a no-op while unacknowledged.
        val blocked = state.advanced()
        assertEquals(OnboardingStep.RECOVERY, blocked.step)
        assertFalse(blocked.finished)

        // After acknowledgement, it advances.
        state = state.withRecoveryAck(true)
        assertTrue(state.canAdvance)
        assertEquals(OnboardingStep.THEME, state.advanced().step)
    }

    @Test
    fun everyNonRecoveryStep_canAdvanceWithoutInput() {
        ordered.forEachIndexed { index, step ->
            val state = OnboardingUiState(index = index)
            if (step == OnboardingStep.RECOVERY) {
                assertFalse("recovery must gate advance", state.canAdvance)
            } else {
                assertTrue("$step should be skippable with defaults", state.canAdvance)
            }
        }
    }

    @Test
    fun progress_increasesMonotonically_endsAtOne() {
        var state = OnboardingUiState()
        var last = 0f
        for (i in ordered.indices) {
            state = state.goTo(i)
            assertTrue(state.progress > last || i == 0)
            last = state.progress
        }
        assertEquals(1f, OnboardingUiState().goTo(ordered.lastIndex).progress, 0.0001f)
    }

    @Test
    fun goTo_clampsToValidRange() {
        val state = OnboardingUiState()
        assertEquals(0, state.goTo(-5).index)
        assertEquals(ordered.lastIndex, state.goTo(999).index)
    }
}
