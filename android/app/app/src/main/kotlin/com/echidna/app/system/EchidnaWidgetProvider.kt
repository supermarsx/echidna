package com.echidna.app.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.echidna.app.R
import com.echidna.app.data.ControlStateRepository

class EchidnaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == SystemActions.ACTION_TOGGLE_ENGINE) {
            ControlStateRepository.toggleMaster()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EchidnaWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EchidnaWidgetProvider::class.java))
            ids.forEach { id -> updateWidget(context, manager, id) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val enabled = ControlStateRepository.masterEnabled.value
            val presetName = ControlStateRepository.activePreset.value.name
            val views = RemoteViews(context.packageName, R.layout.widget_echidna)
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.app_name))
            views.setTextViewText(
                R.id.widgetStatus,
                if (enabled) "Enabled – $presetName" else "Disabled"
            )
            views.setTextViewText(R.id.widgetToggle, if (enabled) "Disable" else "Enable")
            val toggleIntent = Intent(context, EchidnaNotificationReceiver::class.java).apply {
                action = SystemActions.ACTION_TOGGLE_ENGINE
            }
            val pendingToggle = PendingIntent.getBroadcast(
                context,
                id,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetToggle, pendingToggle)
            manager.updateAppWidget(id, views)
        }
    }
}
