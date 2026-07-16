package com.echidna.app.system

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.echidna.app.R
import com.echidna.app.data.ControlStateRepository

/**
 * Wide 3x1 preset widget: shows the active preset name and lets the user cycle to the previous /
 * next preset. Cycling applies the preset through the shared [ControlStateRepository] preset path
 * (same as the in-app Preset Manager); tapping the name opens the app.
 */
class EchidnaPresetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            SystemActions.ACTION_PRESET_PREV -> WidgetSupport.stepPreset(forward = false)
            SystemActions.ACTION_PRESET_NEXT -> WidgetSupport.stepPreset(forward = true)
            else -> return
        }
        WidgetSupport.refreshAllWidgets(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, EchidnaPresetWidgetProvider::class.java)
            )
            ids.forEach { id -> render(context, manager, id) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_preset)
            views.setTextViewText(R.id.presetName, ControlStateRepository.activePreset.value.name)

            views.setOnClickPendingIntent(
                R.id.presetPrev,
                WidgetSupport.controlIntent(
                    context,
                    EchidnaPresetWidgetProvider::class.java,
                    SystemActions.ACTION_PRESET_PREV,
                    id * 10 + 1
                )
            )
            views.setOnClickPendingIntent(
                R.id.presetNext,
                WidgetSupport.controlIntent(
                    context,
                    EchidnaPresetWidgetProvider::class.java,
                    SystemActions.ACTION_PRESET_NEXT,
                    id * 10 + 2
                )
            )
            views.setOnClickPendingIntent(
                R.id.presetCenter,
                WidgetSupport.openAppIntent(context, id * 10 + 3)
            )
            manager.updateAppWidget(id, views)
        }
    }
}
