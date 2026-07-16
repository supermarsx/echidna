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
 * Compact 2x1 control widget: a live, honest engine-status indicator (colour dot + label),
 * a master enable/disable toggle and a Panic button. All actions drive the shared
 * [ControlStateRepository] control path (bound AIDL EchidnaControlService).
 */
class EchidnaControlWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val enabled = WidgetSupport.controlsEnabled()
        when (intent.action) {
            SystemActions.ACTION_TOGGLE_ENGINE -> if (enabled) ControlStateRepository.toggleMaster()
            SystemActions.ACTION_TRIGGER_PANIC -> if (enabled) ControlStateRepository.triggerPanic()
            else -> return
        }
        WidgetSupport.refreshAllWidgets(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, EchidnaControlWidgetProvider::class.java)
            )
            ids.forEach { id -> render(context, manager, id) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_control)
            val state = WidgetSupport.engineState()
            WidgetSupport.applyStatusDot(views, R.id.controlStatusDot, state)

            val master = ControlStateRepository.masterEnabled.value
            val enabled = WidgetSupport.controlsEnabled()

            if (enabled) {
                views.setTextViewText(R.id.controlStatusLabel, state.label)
                WidgetSupport.applyPill(
                    context,
                    views,
                    R.id.controlMaster,
                    context.getString(if (master) R.string.widget_on else R.string.widget_off),
                    master
                )
                views.setOnClickPendingIntent(
                    R.id.controlMaster,
                    WidgetSupport.controlIntent(
                        context,
                        EchidnaControlWidgetProvider::class.java,
                        SystemActions.ACTION_TOGGLE_ENGINE,
                        id * 10 + 1
                    )
                )
                views.setOnClickPendingIntent(
                    R.id.controlPanic,
                    WidgetSupport.controlIntent(
                        context,
                        EchidnaControlWidgetProvider::class.java,
                        SystemActions.ACTION_TRIGGER_PANIC,
                        id * 10 + 2
                    )
                )
            } else {
                views.setTextViewText(
                    R.id.controlStatusLabel,
                    context.getString(R.string.widget_controls_disabled)
                )
                WidgetSupport.applyPill(
                    context,
                    views,
                    R.id.controlMaster,
                    context.getString(R.string.widget_off),
                    on = false
                )
                // Controls disabled in Settings: tapping opens the app rather than acting silently.
                val open = WidgetSupport.openAppIntent(context, id * 10 + 4)
                views.setOnClickPendingIntent(R.id.controlMaster, open)
                views.setOnClickPendingIntent(R.id.controlPanic, open)
            }

            views.setOnClickPendingIntent(
                R.id.controlHeader,
                WidgetSupport.openAppIntent(context, id * 10 + 3)
            )
            manager.updateAppWidget(id, views)
        }
    }
}
