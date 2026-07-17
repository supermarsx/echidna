package com.echidna.app.ui.onboarding

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.AccentColor
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.SettingsState
import com.echidna.app.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the first-run setup wizard (t14). Owns only the wizard's own navigation state; every
 * choice a step makes is written straight through the existing [ControlStateRepository] so there is
 * a single source of truth and a single persistence path — the wizard invents no new storage.
 *
 * The pure step math lives in [OnboardingUiState]; this class layers persistence side-effects and
 * re-exposes the repository flows the steps render.
 */
class OnboardingViewModel(
    private val repo: ControlStateRepository = ControlStateRepository,
    steps: List<OnboardingStep> = OnboardingStep.ordered,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState(steps = steps))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    // Repository flows the step composables observe (read-only; reused, not re-derived).
    val settingsState: StateFlow<SettingsState> = repo.settingsState
    val presets = repo.presets
    val activePreset = repo.activePreset
    val moduleStatus: StateFlow<ModuleStatus?> = repo.moduleStatus
    val compatibility = repo.compatibilityState
    val whitelistBindings = repo.whitelistBindings

    // --- Navigation ---------------------------------------------------------------------------

    /** Advance to the next step (or finish on the last). Blocked on the recovery step until ack. */
    fun next() {
        _state.value = _state.value.advanced()
        persistIfFinished()
    }

    /** Skip this step, keeping its sane default. Identical to [next]; the recovery gate still holds. */
    fun skipStep() = next()

    fun back() {
        _state.value = _state.value.back()
    }

    fun goTo(index: Int) {
        _state.value = _state.value.goTo(index)
    }

    fun acknowledgeRecovery(acknowledged: Boolean) {
        _state.value = _state.value.withRecoveryAck(acknowledged)
    }

    /** Skip the whole wizard ("I'll do it later"). Marks onboarding complete with defaults intact. */
    fun finishNow() {
        _state.value = _state.value.copy(finished = true)
        repo.setOnboardingComplete(true)
    }

    /**
     * Persists onboarding completion WITHOUT flipping the host's `finished` navigation. Used when a
     * step hands off directly to another screen (installer/Lab): the wizard is done, but the caller
     * navigates to that screen rather than back into the app's start destination.
     */
    fun markComplete() {
        repo.setOnboardingComplete(true)
    }

    private fun persistIfFinished() {
        if (_state.value.finished) repo.setOnboardingComplete(true)
    }

    // --- Reused repository actions -------------------------------------------------------------

    fun runCompatibilityProbe() = repo.runCompatibilityProbe()

    fun setThemeMode(mode: ThemeMode) = repo.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = repo.setDynamicColor(enabled)

    fun setAccentColor(accent: AccentColor) = repo.setAccentColor(accent)

    fun selectPreset(presetId: String) = repo.selectPreset(presetId)

    /**
     * A single honest "advisory alerts" master for the wizard. There is no single alerts pref — the
     * app has four independent advisory categories — so on/off flips all four together, matching the
     * granular toggles the user can still fine-tune later in Settings.
     */
    fun setAlertsEnabled(enabled: Boolean) {
        repo.setShowInstallAlerts(enabled)
        repo.setShowBridgeAlerts(enabled)
        repo.setShowHardwareAlerts(enabled)
        repo.setShowInstallMixupAlerts(enabled)
    }

    fun setPersistentNotification(enabled: Boolean) = repo.setNotificationEnabled(enabled)

    fun setHighPriorityNotification(enabled: Boolean) = repo.setHighPriorityNotification(enabled)

    fun setQuickControlsEnabled(enabled: Boolean) = repo.setQuickControlsEnabled(enabled)

    suspend fun refreshModuleStatus(): ModuleStatus? = repo.refreshModuleStatus()

    suspend fun installedLaunchablePackages(): List<String> = repo.installedLaunchablePackages()

    fun updateWhitelist(packageName: String, enabled: Boolean) =
        repo.updateWhitelist(packageName, enabled)
}
