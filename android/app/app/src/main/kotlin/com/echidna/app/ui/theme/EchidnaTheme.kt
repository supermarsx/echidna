package com.echidna.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.echidna.app.model.AccentColor
import com.echidna.app.model.ThemeMode

/** True on devices where Material-You dynamic color (wallpaper palette) is available. */
fun dynamicColorSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * Resolves whether the dark scheme should be used for [this] mode. [SYSTEM] defers to
 * [systemDark]; [LIGHT]/[DARK] are absolute. Pure, so it is unit-testable.
 */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * App-wide Material 3 theme honoring the user's persisted appearance choices:
 * - [themeMode] Light/Dark/System (System follows [isSystemInDarkTheme]).
 * - [dynamicColor] Material-You wallpaper palette on Android 12+ (ignored, with a static
 *   fallback, below 12 or when off).
 * - [accentColor] curated seed used for the static Light/Dark schemes when dynamic color is off.
 */
@Composable
fun EchidnaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    accentColor: AccentColor = AccentColor.VIOLET,
    content: @Composable () -> Unit
) {
    val dark = themeMode.isDark(isSystemInDarkTheme())
    val useDynamic = dynamicColor && dynamicColorSupported()
    val colorScheme = if (useDynamic) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        echidnaColorScheme(accentColor, dark)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
