package com.echidna.app.model

/**
 * User-facing theme mode. [SYSTEM] follows the OS light/dark setting; [LIGHT]/[DARK] force a
 * scheme regardless of the system. Kept Compose-free so it can live in [SettingsState]; the
 * mapping to an actual Material 3 `ColorScheme` lives in `ui/theme`.
 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromId(id: String?): ThemeMode =
            values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Curated accent/seed choices used when Material-You dynamic color is off or unavailable
 * (below Android 12). Each entry names a seed hue; `ui/theme` turns it into polished Light and
 * Dark schemes. [VIOLET] is the historical default (matches the previous hardcoded look).
 */
enum class AccentColor(val id: String, val label: String) {
    VIOLET("violet", "Violet"),
    BLUE("blue", "Blue"),
    TEAL("teal", "Teal"),
    GREEN("green", "Green"),
    AMBER("amber", "Amber"),
    ROSE("rose", "Rose");

    companion object {
        fun fromId(id: String?): AccentColor =
            values().firstOrNull { it.id == id } ?: VIOLET
    }
}
