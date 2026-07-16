package com.echidna.app.ui.alerts

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository

/**
 * Backs the top-level Alerts screen. It exposes the same live control-state flows the advisory
 * builder consumes; the alerts themselves are derived in the screen from these snapshots (see
 * [buildAdvisoryAlerts]). Read-only over [ControlStateRepository] — the Alerts screen only *routes*
 * to the destinations that resolve an advisory, it does not mutate engine state.
 */
class AlertsViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val settingsState = repo.settingsState
    val engineStatus = repo.engineStatus
    val moduleStatus = repo.moduleStatus
    val compatibility = repo.compatibilityState
    val telemetry = repo.telemetry
    val whitelistBindings = repo.whitelistBindings
}
