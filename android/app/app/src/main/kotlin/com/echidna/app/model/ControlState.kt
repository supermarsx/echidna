package com.echidna.app.model

/**
 * Global control state read back from the control service's getControlState()
 * (t2-e6 signatures §4). Lets the app mirror the authoritative master/bypass/
 * panic/sidetone state the service persists instead of a local-only flag.
 */
data class ControlState(
    val masterEnabled: Boolean,
    val bypass: Boolean,
    val panicUntilEpochMs: Long,
    val sidetoneEnabled: Boolean,
    val sidetoneGainDb: Float,
    val engineMode: DspEngineMode
)
