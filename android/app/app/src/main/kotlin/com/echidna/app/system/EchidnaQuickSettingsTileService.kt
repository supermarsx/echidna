package com.echidna.app.system

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.echidna.app.R
import com.echidna.app.data.ControlStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EchidnaQuickSettingsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch {
            combine(
                ControlStateRepository.masterEnabled,
                ControlStateRepository.quickControlsEnabled
            ) { enabled, controlsEnabled -> enabled to controlsEnabled }
                .collectLatest { (enabled, controlsEnabled) ->
                    updateTileState(enabled, controlsEnabled)
                }
        }
    }

    override fun onClick() {
        super.onClick()
        if (!ControlStateRepository.quickControlsEnabled.value) {
            updateTileState(ControlStateRepository.masterEnabled.value, controlsEnabled = false)
            return
        }
        ControlStateRepository.toggleMaster()
    }

    override fun onStopListening() {
        super.onStopListening()
        listenJob?.cancel()
        listenJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun updateTileState(enabled: Boolean, controlsEnabled: Boolean = true) {
        val tile = qsTile ?: return
        // Brand the tile with the monochrome Echidna mark (Android tints it per tile state).
        tile.icon = Icon.createWithResource(this, R.drawable.ic_echidna_mono)
        tile.state = when {
            !controlsEnabled -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = when {
            !controlsEnabled -> "Echidna Disabled"
            enabled -> "Echidna On"
            else -> "Echidna Off"
        }
        tile.updateTile()
    }
}
