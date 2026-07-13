package com.echidna.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.echidna.app.data.ControlStateRepository

class EchidnaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> handleStartup(context)
        }
    }

    private fun handleStartup(context: Context) {
        ControlStateRepository.initialize(context.applicationContext)
        if (!ControlStateRepository.startWithSystem.value) return

        if (ControlStateRepository.autoStartEngine.value) {
            ControlStateRepository.setMasterEnabled(true)
        }
        if (ControlStateRepository.notificationEnabled.value) {
            NotificationController.ensureChannel(context)
            NotificationController.updateNotification(context)
        }
        if (ControlStateRepository.widgetControlsEnabled.value) {
            EchidnaWidgetProvider.updateAll(context)
        }
    }
}
