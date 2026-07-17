package com.echidna.app.ui

data class AppDestination(val route: String, val label: String) {
    companion object {
        val Dashboard = AppDestination("dashboard", "Dashboard")
        val PresetManager = AppDestination("presets", "Presets")
        val EffectsEditor = AppDestination("effects", "Effects")
        val Alerts = AppDestination("alerts", "Alerts")
        val Diagnostics = AppDestination("diagnostics", "Diagnostics")
        val Settings = AppDestination("settings", "Settings")
        val CompatibilityWizard = AppDestination("compatibility", "Compatibility")
        val WhitelistEditor = AppDestination("whitelist", "Whitelist")
        val InstallEngine = AppDestination("install", "Install engine")
        val Lab = AppDestination("lab", "Lab")

        val bottomDestinations =
            listOf(Dashboard, PresetManager, EffectsEditor, Lab, Alerts, Diagnostics, Settings)
    }
}
