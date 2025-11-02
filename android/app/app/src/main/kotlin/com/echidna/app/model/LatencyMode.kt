package com.echidna.app.model

enum class LatencyMode(val label: String, val targetMs: Int) {
    LOW_LATENCY("Low-Latency", 15),
    BALANCED("Balanced", 20),
    HIGH_QUALITY("High-Quality", 30);
}
