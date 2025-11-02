package com.echidna.app.model

data class CompatibilityResult(
    val selinuxStatus: String,
    val audioStack: List<AudioStackProbe>,
    val notes: List<String>
)

data class AudioStackProbe(
    val name: String,
    val supported: Boolean,
    val latencyEstimateMs: Int?,
    val message: String
)
