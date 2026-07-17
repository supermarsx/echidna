package com.echidna.app.ui.theme

import com.echidna.app.model.AccentColor
import com.echidna.app.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchidnaThemeTest {

    @Test
    fun `theme mode resolves dark correctly`() {
        // SYSTEM defers to the system flag.
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        // LIGHT/DARK are absolute regardless of the system flag.
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun `dynamic color is gated on api 31`() {
        assertFalse(dynamicColorSupported(sdkInt = 30))
        assertTrue(dynamicColorSupported(sdkInt = 31))
        assertTrue(dynamicColorSupported(sdkInt = 34))
    }

    @Test
    fun `light and dark schemes differ for the same accent`() {
        val light = echidnaColorScheme(AccentColor.VIOLET, dark = false)
        val dark = echidnaColorScheme(AccentColor.VIOLET, dark = true)
        assertNotEquals(light.primary, dark.primary)
    }

    @Test
    fun `each accent produces a distinct primary`() {
        val primaries = enumValues<AccentColor>()
            .map { echidnaColorScheme(it, dark = false).primary }
        assertEquals(
            "accents should map to unique primary colors",
            enumValues<AccentColor>().size,
            primaries.toSet().size
        )
    }
}
