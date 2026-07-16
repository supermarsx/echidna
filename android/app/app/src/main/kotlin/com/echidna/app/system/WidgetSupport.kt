package com.echidna.app.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.echidna.app.MainActivity
import com.echidna.app.R
import com.echidna.app.data.ControlStateRepository

/**
 * Shared rendering + control plumbing for the Echidna home-screen widgets. Keeps the individual
 * providers thin: every control here routes through the same [ControlStateRepository] the app UI
 * uses (which drives the bound AIDL EchidnaControlService), and the status colours/labels are
 * derived from the real [com.echidna.app.model.EngineStatus] — never fabricated.
 */
internal object WidgetSupport {

    /** The honest engine state, from the live repository status + control flags. */
    fun engineState(): WidgetEngineState = engineWidgetState(
        status = ControlStateRepository.engineStatus.value,
        masterEnabled = ControlStateRepository.masterEnabled.value,
        bypass = ControlStateRepository.bypass.value
    )

    /**
     * Points the status dot at the pre-coloured drawable for the current engine state. Uses
     * `setImageViewResource` (a guaranteed-remotable method) rather than a runtime colour filter,
     * so it is safe on every supported API level.
     */
    fun applyStatusDot(views: RemoteViews, dotId: Int, state: WidgetEngineState) {
        views.setImageViewResource(
            dotId,
            when (state) {
                WidgetEngineState.ACTIVE -> R.drawable.widget_dot_active
                WidgetEngineState.STANDBY -> R.drawable.widget_dot_standby
                WidgetEngineState.BYPASSED -> R.drawable.widget_dot_bypassed
                WidgetEngineState.NOT_INSTALLED -> R.drawable.widget_dot_notinstalled
                WidgetEngineState.ERROR -> R.drawable.widget_dot_error
            }
        )
    }

    /**
     * Styles a toggle "pill": accent fill + on-accent text when [on], neutral surface otherwise.
     * Uses only RemoteViews-safe int/text setters so it works on every supported API level.
     */
    fun applyPill(context: Context, views: RemoteViews, id: Int, label: String, on: Boolean) {
        views.setTextViewText(id, label)
        views.setInt(
            id,
            "setBackgroundResource",
            if (on) R.drawable.widget_btn_accent else R.drawable.widget_btn
        )
        views.setTextColor(
            id,
            ContextCompat.getColor(
                context,
                if (on) R.color.widget_on_accent else R.color.widget_on_surface
            )
        )
    }

    /** A broadcast PendingIntent addressed explicitly to [provider] (no implicit delivery). */
    fun controlIntent(
        context: Context,
        provider: Class<*>,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, provider).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Opens the companion app. */
    fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Selects the preset before/after the active one, wrapping around, via the shared control path. */
    fun stepPreset(forward: Boolean) {
        val presets = ControlStateRepository.presets.value
        if (presets.isEmpty()) return
        val activeId = ControlStateRepository.activePreset.value.id
        val index = presets.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
        val target = if (forward) {
            presets[(index + 1) % presets.size]
        } else {
            presets[(index - 1 + presets.size) % presets.size]
        }
        ControlStateRepository.selectPreset(target.id)
    }

    /** Whether the user has enabled widget controls (Settings). Mirrors the existing widget. */
    fun controlsEnabled(): Boolean = ControlStateRepository.widgetControlsEnabled.value

    /** Refreshes every Echidna widget so they all reflect one coherent state after a change. */
    fun refreshAllWidgets(context: Context) {
        EchidnaWidgetProvider.updateAll(context)
    }
}
