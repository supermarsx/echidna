package com.echidna.app.ui.whitelist

import com.echidna.app.data.CommonApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit coverage for the installed-only suggestion filter and label fallback (t8-e8). No
 * Android/PackageManager needed — [WhitelistCatalog] takes the resolved device snapshot as data.
 */
class WhitelistCatalogTest {

    private fun packagesOf(entries: List<AppEntry>) = entries.map { it.packageName }.toSet()

    @Test
    fun `installed filter narrows suggestions to the installed set`() {
        // Discord is a curated suggestion AND installed; WhatsApp is a curated suggestion but NOT
        // installed; the plain app is installed but uncatalogued.
        val installed = setOf("com.discord", "com.example.plain")
        val entries = WhitelistCatalog.buildEntries(
            whitelist = emptyMap(),
            bindings = emptyMap(),
            installedPackages = installed,
            installedLabels = emptyMap(),
            onlyInstalledSuggestions = true,
        )
        val pkgs = packagesOf(entries)

        assertTrue("installed suggestion is kept", "com.discord" in pkgs)
        assertTrue("installed uncatalogued app is kept", "com.example.plain" in pkgs)
        assertFalse("not-installed suggestion is filtered out", "com.whatsapp" in pkgs)
        // Every surfaced row is either installed or carries state — nothing not-installed leaks in.
        assertTrue(entries.all { it.installed || it.enabled || it.presetId.isNotEmpty() })
    }

    @Test
    fun `toggle off shows the full curated suggestion set regardless of install state`() {
        val entries = WhitelistCatalog.buildEntries(
            whitelist = emptyMap(),
            bindings = emptyMap(),
            installedPackages = emptySet(),
            installedLabels = emptyMap(),
            onlyInstalledSuggestions = false,
        )
        val pkgs = packagesOf(entries)

        // The complete curated suggestion catalog must be present even though nothing is installed.
        val suggested = CommonApps.suggestedPackages()
        assertTrue("catalog is non-trivial", suggested.size > 10)
        assertTrue("all curated suggestions surface when filter is off", pkgs.containsAll(suggested))
        assertTrue(entries.any { it.packageName == "com.whatsapp" && !it.installed })
    }

    @Test
    fun `enabled or bound apps are kept even when not installed and filter is on`() {
        val entries = WhitelistCatalog.buildEntries(
            whitelist = mapOf("com.example.enabled" to true),
            bindings = mapOf("com.example.bound" to "preset-1"),
            installedPackages = emptySet(),
            installedLabels = emptyMap(),
            onlyInstalledSuggestions = true,
        )
        val byPkg = entries.associateBy { it.packageName }

        val enabled = byPkg["com.example.enabled"]
        assertTrue("enabled row present", enabled != null)
        assertTrue(enabled!!.enabled)
        assertFalse(enabled.installed)

        val bound = byPkg["com.example.bound"]
        assertTrue("preset-bound row present", bound != null)
        assertEquals("preset-1", bound!!.presetId)
    }

    @Test
    fun `label resolution prefers real label then curated name then package id`() {
        val entries = WhitelistCatalog.buildEntries(
            whitelist = emptyMap(),
            bindings = emptyMap(),
            installedPackages = setOf("com.discord", "com.example.plain"),
            installedLabels = mapOf("com.discord" to "Discord Nitro"),
            onlyInstalledSuggestions = false,
        )
        val byPkg = entries.associateBy { it.packageName }

        // Installed with a real label -> real label wins.
        assertEquals("Discord Nitro", byPkg["com.discord"]!!.label)
        // Not installed, curated -> canonical catalog name.
        assertEquals("WhatsApp", byPkg["com.whatsapp"]!!.label)
        // Installed, no real label, uncatalogued -> raw package id.
        assertEquals("com.example.plain", byPkg["com.example.plain"]!!.label)
    }
}
