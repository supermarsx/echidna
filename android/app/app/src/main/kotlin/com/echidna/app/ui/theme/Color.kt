package com.echidna.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.echidna.app.model.AccentColor

/**
 * Curated accent palettes and the mapping from a [AccentColor] seed to polished Material 3
 * Light/Dark [ColorScheme]s. Each accent is described by a small M3-style tonal ramp
 * (tones 10/20/30/40/80/90) that the standard role assignment turns into a coherent scheme:
 *
 * - Light: primary = tone40, onPrimary = white, container = tone90, onContainer = tone10.
 * - Dark:  primary = tone80, onPrimary = tone20, container = tone30, onContainer = tone90.
 *
 * Neutral surface/background/error roles fall back to the Material 3 baseline (via
 * [lightColorScheme]/[darkColorScheme] defaults), which are already well-tuned; only the
 * accent-bearing roles are overridden so each choice reads distinctly without hand-authoring a
 * full scheme per accent.
 */
private data class AccentRamp(
    val tone10: Color,
    val tone20: Color,
    val tone30: Color,
    val tone40: Color,
    val tone80: Color,
    val tone90: Color
)

private val VioletRamp = AccentRamp(
    tone10 = Color(0xFF21005D),
    tone20 = Color(0xFF381E72),
    tone30 = Color(0xFF4F378B),
    tone40 = Color(0xFF6750A4),
    tone80 = Color(0xFFD0BCFF),
    tone90 = Color(0xFFEADDFF)
)

private val BlueRamp = AccentRamp(
    tone10 = Color(0xFF001B3F),
    tone20 = Color(0xFF002E6A),
    tone30 = Color(0xFF004494),
    tone40 = Color(0xFF1565C0),
    tone80 = Color(0xFFA6C8FF),
    tone90 = Color(0xFFD6E3FF)
)

private val TealRamp = AccentRamp(
    tone10 = Color(0xFF00201F),
    tone20 = Color(0xFF003735),
    tone30 = Color(0xFF00504D),
    tone40 = Color(0xFF006A67),
    tone80 = Color(0xFF4FD9D2),
    tone90 = Color(0xFF6FF7EF)
)

private val GreenRamp = AccentRamp(
    tone10 = Color(0xFF002106),
    tone20 = Color(0xFF00390F),
    tone30 = Color(0xFF005317),
    tone40 = Color(0xFF2E7D32),
    tone80 = Color(0xFF7FD98A),
    tone90 = Color(0xFFB9F0BE)
)

private val AmberRamp = AccentRamp(
    tone10 = Color(0xFF2A1700),
    tone20 = Color(0xFF452B00),
    tone30 = Color(0xFF633F00),
    tone40 = Color(0xFF845400),
    tone80 = Color(0xFFFFB951),
    tone90 = Color(0xFFFFDDB3)
)

private val RoseRamp = AccentRamp(
    tone10 = Color(0xFF3E0021),
    tone20 = Color(0xFF5E1136),
    tone30 = Color(0xFF7E2949),
    tone40 = Color(0xFF9E1F52),
    tone80 = Color(0xFFFFB1C8),
    tone90 = Color(0xFFFFD9E2)
)

private fun AccentColor.ramp(): AccentRamp = when (this) {
    AccentColor.VIOLET -> VioletRamp
    AccentColor.BLUE -> BlueRamp
    AccentColor.TEAL -> TealRamp
    AccentColor.GREEN -> GreenRamp
    AccentColor.AMBER -> AmberRamp
    AccentColor.ROSE -> RoseRamp
}

private fun AccentRamp.lightScheme(): ColorScheme =
    lightColorScheme(
        primary = tone40,
        onPrimary = Color.White,
        primaryContainer = tone90,
        onPrimaryContainer = tone10,
        inversePrimary = tone80,
        secondary = tone40,
        onSecondary = Color.White,
        secondaryContainer = tone90,
        onSecondaryContainer = tone10,
        tertiary = tone30,
        onTertiary = Color.White,
        tertiaryContainer = tone90,
        onTertiaryContainer = tone10
    )

private fun AccentRamp.darkScheme(): ColorScheme =
    darkColorScheme(
        primary = tone80,
        onPrimary = tone20,
        primaryContainer = tone30,
        onPrimaryContainer = tone90,
        inversePrimary = tone40,
        secondary = tone80,
        onSecondary = tone20,
        secondaryContainer = tone30,
        onSecondaryContainer = tone90,
        tertiary = tone80,
        onTertiary = tone20,
        tertiaryContainer = tone30,
        onTertiaryContainer = tone90
    )

/**
 * Resolves the static (non-dynamic) Material 3 [ColorScheme] for [accent] in the requested
 * light/dark variant. Pure and Android-free, so it is unit-testable without a device.
 */
fun echidnaColorScheme(accent: AccentColor, dark: Boolean): ColorScheme {
    val ramp = accent.ramp()
    return if (dark) ramp.darkScheme() else ramp.lightScheme()
}
