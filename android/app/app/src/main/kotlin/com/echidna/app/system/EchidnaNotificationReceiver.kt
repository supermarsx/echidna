package com.echidna.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.echidna.app.MainActivity
import com.echidna.app.data.ControlStateRepository

class EchidnaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SystemActions.ACTION_TOGGLE_ENGINE -> {
                ControlStateRepository.toggleMaster()
                if (ControlStateRepository.notificationEnabled.value) {
                    NotificationController.updateNotification(context)
                } else {
                    NotificationController.cancel(context)
                }
                EchidnaWidgetProvider.updateAll(context)
            }
            SystemActions.ACTION_CYCLE_PRESET -> {
                ControlStateRepository.cyclePreset()
                if (ControlStateRepository.notificationEnabled.value) {
                    NotificationController.updateNotification(context)
                }
                EchidnaWidgetProvider.updateAll(context)
            }
            SystemActions.ACTION_OPEN_APP -> {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
