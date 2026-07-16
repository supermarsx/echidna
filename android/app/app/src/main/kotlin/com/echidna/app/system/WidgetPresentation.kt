package com.echidna.app.system

import com.echidna.app.model.EngineStatus

/**
 * The honest engine states a home-screen widget can show. These mirror the five states of the
 * in-app [com.echidna.app.ui.components.EngineStatusCard] one-for-one so a widget never claims a
 * more capable state than the app itself would. Pure Kotlin (no Android/`R` dependency) so the
 * state-mapping is unit-testable on the JVM.
 */
enum class WidgetEngineState(val label: String, val detail: String) {
    ACTIVE("Active", "Processing audio"),
    STANDBY("Standby", "Master off · on standby"),
    BYPASSED("Bypassed", "Passing audio through"),
    NOT_INSTALLED("Not installed", "Install the engine module"),
    ERROR("Error", "Engine reported a problem")
}

/**
 * Maps the real [EngineStatus] plus the live master/bypass control flags to one honest widget
 * state, using the exact same precedence as `EngineStatusCard.engineUiState`:
 * a reported error wins, then a missing native module, then the live `active` flag; a disabled
 * master resolves to [WidgetEngineState.STANDBY] before an explicit bypass so a switched-off
 * engine never reads as "Bypassed". Never fabricates [WidgetEngineState.ACTIVE].
 */
fun engineWidgetState(
    status: EngineStatus,
    masterEnabled: Boolean,
    bypass: Boolean
): WidgetEngineState = when {
    status.lastError != null -> WidgetEngineState.ERROR
    !status.nativeInstalled -> WidgetEngineState.NOT_INSTALLED
    status.active -> WidgetEngineState.ACTIVE
    !masterEnabled -> WidgetEngineState.STANDBY
    bypass -> WidgetEngineState.BYPASSED
    else -> WidgetEngineState.STANDBY
}
