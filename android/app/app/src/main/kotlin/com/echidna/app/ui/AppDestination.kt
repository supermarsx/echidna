package com.echidna.app.ui

/**
 * @param rendersOwnTitle true when the screen already draws its own in-content title/header. The
 *   app-wide top bar then suppresses its title on that route so the screen is not double-titled
 *   (t18). Dashboard and Help have no in-content header, so the top bar supplies their title.
 */
data class AppDestination(
    val route: String,
    val label: String,
    val rendersOwnTitle: Boolean = false,
) {
    companion object {
        // Dashboard has no in-content header: the top bar supplies its title.
        val Dashboard = AppDestination("dashboard", "Dashboard")
        val PresetManager = AppDestination("presets", "Presets", rendersOwnTitle = true)
        val EffectsEditor = AppDestination("effects", "Effects", rendersOwnTitle = true)
        val Alerts = AppDestination("alerts", "Alerts", rendersOwnTitle = true)
        val Diagnostics = AppDestination("diagnostics", "Diagnostics", rendersOwnTitle = true)
        val Settings = AppDestination("settings", "Settings", rendersOwnTitle = true)
        val CompatibilityWizard = AppDestination("compatibility", "Compatibility", rendersOwnTitle = true)
        val WhitelistEditor = AppDestination("whitelist", "Whitelist", rendersOwnTitle = true)
        val InstallEngine = AppDestination("install", "Install engine", rendersOwnTitle = true)
        val Lab = AppDestination("lab", "Lab", rendersOwnTitle = true)

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

        /**
         * Title for the app-wide top bar on [route], or null when the screen renders its own
         * in-content header (the bar then shows no title, so the screen is not double-titled — t18).
         * Unknown routes fall back to the app name so the bar is never blank unexpectedly.
         */
        fun topBarTitle(route: String?): String? {
            val destination = allDestinations.firstOrNull { it.route == route } ?: return "Echidna"
            return if (destination.rendersOwnTitle) null else destination.label
        }
    }
}
