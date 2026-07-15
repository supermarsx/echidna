package com.echidna.app.model

/** Last service-confirmed state for the default-off legacy preprocessor attachment gate. */
data class LegacyPreprocessorControlState(
    val enabled: Boolean = false,
    val loaded: Boolean = false,
    val available: Boolean = false,
    val updating: Boolean = false,
    val error: String? = null,
)
