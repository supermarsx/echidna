package com.echidna.app.model

data class EngineStatus(
    val nativeInstalled: Boolean,
    val active: Boolean,
    val selinuxMode: String,
    val lastError: String? = null,
    val latencyMs: Int? = null,
    val xruns: Int = 0
) {
    val summary: String
        get() = when {
            !nativeInstalled -> "Not Installed"
            !active -> "Bypassed"
            lastError != null -> "Error"
            else -> "Active"
        }
}
