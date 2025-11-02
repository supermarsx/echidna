package com.echidna.app.ui

data class AppDestination(val route: String, val label: String) {
    companion object {
        val Dashboard = AppDestination("dashboard", "Dashboard")
        val PresetManager = AppDestination("presets", "Presets")
        val EffectsEditor = AppDestination("effects", "Effects")
        val Diagnostics = AppDestination("diagnostics", "Diagnostics")
        val Settings = AppDestination("settings", "Settings")
        val CompatibilityWizard = AppDestination("compatibility", "Compatibility")

        val bottomDestinations = listOf(Dashboard, PresetManager, EffectsEditor, Diagnostics, Settings)
    }
}
