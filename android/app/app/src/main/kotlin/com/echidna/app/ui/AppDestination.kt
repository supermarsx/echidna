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

        // First-run onboarding wizard (t14). Not a bottom-bar destination; shown at first launch and
        // re-runnable from Settings. Rendered full-screen (no top/bottom app chrome) — see MainActivity.
        val Onboarding = AppDestination("onboarding", "Setup")

        // In-app Help & Docs (t15). Not a bottom-nav tab (the bar already holds seven); reached via the
        // top-app-bar Help action (app-wide) and the Settings "Help & Docs" entry — see MainActivity.
        val Help = AppDestination("help", "Help")

        val bottomDestinations =
            listOf(Dashboard, PresetManager, EffectsEditor, Lab, Alerts, Diagnostics, Settings)

        /** Every destination, for resolving the current route to a top-app-bar title. */
        val allDestinations = listOf(
            Dashboard, PresetManager, EffectsEditor, Alerts, Diagnostics, Settings,
            CompatibilityWizard, WhitelistEditor, InstallEngine, Lab, Onboarding, Help,
        )

        /** Human-readable title for [route], defaulting to the app name when unknown. */
        fun titleForRoute(route: String?): String =
            allDestinations.firstOrNull { it.route == route }?.label ?: "Echidna"
    }
}
