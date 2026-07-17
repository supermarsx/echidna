package com.echidna.app.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM coverage for [helpAssetPath] — the doc-relative → staged-asset path resolver the Help
 * image renderer uses. Mirrors how `stageHelpDocs` lays image assets out under the docs root, so a
 * `![](assets/...)` in a top-level doc and a `![](../assets/...)` in `hardening/` both resolve.
 */
class HelpAssetPathTest {

    @Test
    fun `top-level doc resolves an assets-relative image against the docs root`() {
        assertEquals(
            "help/docs/assets/screenshots/14-help-tab.png",
            helpAssetPath("screenshots.md", "assets/screenshots/14-help-tab.png"),
        )
    }

    @Test
    fun `subdirectory doc resolves a parent-relative image`() {
        assertEquals(
            "help/docs/assets/diagrams/route.png",
            helpAssetPath("hardening/audioflinger-route.md", "../assets/diagrams/route.png"),
        )
    }

    @Test
    fun `an anchor or query suffix is stripped before resolving`() {
        assertEquals(
            "help/docs/assets/x.png",
            helpAssetPath("index.md", "assets/x.png#frag"),
        )
    }

    @Test
    fun `a destination that escapes the docs root does not resolve`() {
        assertNull(helpAssetPath("index.md", "../../secret.png"))
    }

    @Test
    fun `a pure anchor has no asset path`() {
        assertNull(helpAssetPath("index.md", "#section"))
    }
}
