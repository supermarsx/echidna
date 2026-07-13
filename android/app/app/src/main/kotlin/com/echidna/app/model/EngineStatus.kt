package com.echidna.app.model

data class EngineStatus(
    val nativeInstalled: Boolean,
    val active: Boolean,
    val selinuxMode: String,
    val lastError: String? = null,
    val latencyMs: Int? = null,
    val xruns: Int = 0,
    // True when the engine is installed and running but explicitly bypassing (passing audio
    // through untouched). Distinct from a disabled engine (master off), which is Standby.
    val bypass: Boolean = false
) {
    val summary: String
        get() = when {
            lastError != null -> "Error"
            !nativeInstalled -> "Not Installed"
            active -> "Active"
            bypass -> "Bypassed"
            else -> "Standby"
        }
}
