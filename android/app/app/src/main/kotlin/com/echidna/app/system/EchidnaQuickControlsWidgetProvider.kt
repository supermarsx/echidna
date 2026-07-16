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
 * Large 4x2 hub widget: honest engine-status header, active preset with prev/next, and quick
 * toggles for master, bypass and sidetone plus a Panic button. Every control drives the shared
 * [ControlStateRepository] path (bound AIDL EchidnaControlService).
 */
class EchidnaQuickControlsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val enabled = WidgetSupport.controlsEnabled()
        when (intent.action) {
            SystemActions.ACTION_TOGGLE_ENGINE ->
                if (enabled) ControlStateRepository.toggleMaster()
            SystemActions.ACTION_TOGGLE_BYPASS ->
                if (enabled) ControlStateRepository.setBypass(!ControlStateRepository.bypass.value)
            SystemActions.ACTION_TOGGLE_SIDETONE ->
                if (enabled) {
                    ControlStateRepository.setSidetoneEnabled(!ControlStateRepository.sidetoneEnabled.value)
                }
            SystemActions.ACTION_TRIGGER_PANIC ->
                if (enabled) ControlStateRepository.triggerPanic()
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
                ComponentName(context, EchidnaQuickControlsWidgetProvider::class.java)
            )
            ids.forEach { id -> render(context, manager, id) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick)
            val state = WidgetSupport.engineState()
            WidgetSupport.applyStatusDot(views, R.id.quickStatusDot, state)

            val enabled = WidgetSupport.controlsEnabled()
            views.setTextViewText(
                R.id.quickStatusLabel,
                if (enabled) state.detail else context.getString(R.string.widget_controls_disabled)
            )
            views.setTextViewText(R.id.quickPresetName, ControlStateRepository.activePreset.value.name)

            val master = ControlStateRepository.masterEnabled.value
            val bypass = ControlStateRepository.bypass.value
            val sidetone = ControlStateRepository.sidetoneEnabled.value

            WidgetSupport.applyPill(
                context, views, R.id.quickMaster, context.getString(R.string.widget_master),
                on = enabled && master
            )
            WidgetSupport.applyPill(
                context, views, R.id.quickBypass, context.getString(R.string.widget_bypass),
                on = enabled && bypass
            )
            WidgetSupport.applyPill(
                context, views, R.id.quickSidetone, context.getString(R.string.widget_sidetone),
                on = enabled && sidetone
            )

            if (enabled) {
                views.setOnClickPendingIntent(R.id.quickMaster, control(context, SystemActions.ACTION_TOGGLE_ENGINE, id * 10 + 1))
                views.setOnClickPendingIntent(R.id.quickBypass, control(context, SystemActions.ACTION_TOGGLE_BYPASS, id * 10 + 2))
                views.setOnClickPendingIntent(R.id.quickSidetone, control(context, SystemActions.ACTION_TOGGLE_SIDETONE, id * 10 + 3))
                views.setOnClickPendingIntent(R.id.quickPanic, control(context, SystemActions.ACTION_TRIGGER_PANIC, id * 10 + 4))
            } else {
                val open = WidgetSupport.openAppIntent(context, id * 10 + 9)
                views.setOnClickPendingIntent(R.id.quickMaster, open)
                views.setOnClickPendingIntent(R.id.quickBypass, open)
                views.setOnClickPendingIntent(R.id.quickSidetone, open)
                views.setOnClickPendingIntent(R.id.quickPanic, open)
            }

            // Preset cycling and open-app are always available.
            views.setOnClickPendingIntent(R.id.quickPresetPrev, control(context, SystemActions.ACTION_PRESET_PREV, id * 10 + 5))
            views.setOnClickPendingIntent(R.id.quickPresetNext, control(context, SystemActions.ACTION_PRESET_NEXT, id * 10 + 6))
            views.setOnClickPendingIntent(R.id.quickOpen, WidgetSupport.openAppIntent(context, id * 10 + 7))

            manager.updateAppWidget(id, views)
        }

        private fun control(context: Context, action: String, requestCode: Int) =
            WidgetSupport.controlIntent(
                context,
                EchidnaQuickControlsWidgetProvider::class.java,
                action,
                requestCode
            )
    }
}
