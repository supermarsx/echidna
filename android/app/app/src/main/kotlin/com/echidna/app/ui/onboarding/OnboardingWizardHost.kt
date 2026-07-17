package com.echidna.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * First-run setup wizard host (t14): a multi-step, back/next-navigable, skippable flow with a
 * progress indicator. It owns only the wizard chrome; step content lives in [OnboardingStepContent]
 * and all persistence goes through the reused [OnboardingViewModel]/repository.
 *
 * @param onFinished called once when the user completes or skips the whole wizard (the flag is
 *   already persisted by then) — the caller navigates into the normal app.
 * @param onOpenInstaller hand-off to the guided engine installer from the engine step.
 * @param onOpenLab hand-off to the Lab test-tone from the "hear it work" step.
 */
@Composable
fun OnboardingWizardHost(
    onFinished: () -> Unit,
    onOpenInstaller: () -> Unit,
    onOpenLab: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Normal completion / whole-wizard skip flips `finished`; hand back to the caller exactly once.
    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Surface(modifier = modifier.fillMaxSize().testTag(OnboardingTestTags.HOST)) {
        Scaffold(
            topBar = { WizardHeader(state, onSkipAll = viewModel::finishNow) },
            bottomBar = {
                WizardNavBar(
                    state = state,
                    onBack = viewModel::back,
                    onSkip = viewModel::skipStep,
                    onNext = viewModel::next,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    state.step.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OnboardingGap(12)
                OnboardingStepContent(
                    step = state.step,
                    viewModel = viewModel,
                    // Handing off to another screen exits the wizard: persist completion (without the
                    // finish-navigation effect) and let the caller navigate to that screen instead.
                    onOpenInstaller = {
                        viewModel.markComplete()
                        onOpenInstaller()
                    },
                    onOpenLab = {
                        viewModel.markComplete()
                        onOpenLab()
                    },
                )
                OnboardingGap(24)
            }
        }
    }
}

@Composable
private fun WizardHeader(state: OnboardingUiState, onSkipAll: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Step ${state.stepNumber} of ${state.totalSteps} · ${state.step.shortLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onSkipAll,
                modifier = Modifier.testTag(OnboardingTestTags.SKIP_ALL),
            ) { Text("Skip setup") }
        }
        OnboardingGap(6)
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.PROGRESS),
        )
    }
}

@Composable
private fun WizardNavBar(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Column {
        Divider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                enabled = !state.isFirst,
                modifier = Modifier.testTag(OnboardingTestTags.BACK),
            ) { Text("Back") }

            if (!state.isLast) {
                TextButton(
                    onClick = onSkip,
                    enabled = state.canAdvance,
                    modifier = Modifier.testTag(OnboardingTestTags.SKIP_STEP),
                ) { Text("Skip") }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onNext,
                enabled = state.canAdvance,
                modifier = Modifier.testTag(OnboardingTestTags.NEXT),
            ) { Text(if (state.isLast) "Enter Echidna" else "Next") }
        }
    }
}
