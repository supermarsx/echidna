package com.echidna.app.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.echidna.app.MainActivity
import com.echidna.app.R
import com.echidna.app.data.ControlStateRepository

object NotificationController {
    private const val CHANNEL_ID = "echidna_controls"
    private const val NOTIFICATION_ID = 0xEC1

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    fun updateNotification(context: Context) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, buildNotification(context))
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(context: Context): Notification {
        val enabled = ControlStateRepository.masterEnabled.value
        val presetName = ControlStateRepository.activePreset.value.name
        val toggleIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, EchidnaNotificationReceiver::class.java).apply { action = SystemActions.ACTION_TOGGLE_ENGINE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cycleIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, EchidnaNotificationReceiver::class.java).apply { action = SystemActions.ACTION_CYCLE_PRESET },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(if (enabled) "Engine Enabled" else "Engine Bypassed")
            .setSubText("Preset: $presetName")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_tile, context.getString(R.string.notification_toggle), toggleIntent)
            .addAction(R.drawable.ic_tile, context.getString(R.string.notification_cycle), cycleIntent)
            .addAction(R.drawable.ic_tile, context.getString(R.string.notification_open), openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
