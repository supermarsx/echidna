package com.echidna.app.model

data class DspMetrics(
    val inputRms: Float,
    val inputPeak: Float,
    val outputRms: Float,
    val outputPeak: Float,
    val cpuLoadPercent: Float,
    val endToEndLatencyMs: Float,
    val xruns: Int
)
