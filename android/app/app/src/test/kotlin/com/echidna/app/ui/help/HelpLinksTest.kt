package com.echidna.app.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure coverage for link classification/resolution used to route Markdown link taps. */
class HelpLinksTest {

    private val known = setOf(
        "index.md",
        "verification.md",
        "limitations.md",
        "hardening/checklist.md",
        "hardening/rt-safety.md",
    )

    @Test
    fun `sibling doc link resolves to internal doc`() {
        val target = HelpLinks.resolve("index.md", "verification.md", known)
        assertEquals(LinkTarget.InternalDoc("verification.md", null), target)
    }

    @Test
    fun `internal link keeps section anchor`() {
        val target = HelpLinks.resolve("index.md", "limitations.md#hal", known)
        assertEquals(LinkTarget.InternalDoc("limitations.md", "hal"), target)
    }

    @Test
    fun `relative link from a subdirectory doc resolves up and across`() {
        val target = HelpLinks.resolve("hardening/checklist.md", "../verification.md", known)
        assertEquals(LinkTarget.InternalDoc("verification.md", null), target)
    }

    @Test
    fun `sibling link within a subdirectory resolves`() {
        val target = HelpLinks.resolve("hardening/checklist.md", "rt-safety.md#latency", known)
        assertEquals(LinkTarget.InternalDoc("hardening/rt-safety.md", "latency"), target)
    }

    @Test
    fun `http and mailto links are external`() {
        assertTrue(HelpLinks.resolve("index.md", "https://example.com/x", known) is LinkTarget.External)
        assertTrue(HelpLinks.resolve("index.md", "mailto:a@b.co", known) is LinkTarget.External)
    }

    @Test
    fun `pure anchor is a same-doc scroll`() {
        assertEquals(LinkTarget.SameDocAnchor("start-here"), HelpLinks.resolve("index.md", "#start-here", known))
    }

    @Test
    fun `relative link to a non-bundled target is unresolved`() {
        val target = HelpLinks.resolve("index.md", "../spec.md", known)
        assertTrue(target is LinkTarget.Unresolved)
    }

    @Test
    fun `normalize collapses dot segments and rejects escaping the root`() {
        assertEquals("verification.md", HelpLinks.normalize("hardening/checklist.md", "../verification.md"))
        assertEquals("hardening/rt-safety.md", HelpLinks.normalize("hardening/checklist.md", "./rt-safety.md"))
        assertEquals(null, HelpLinks.normalize("index.md", "../../outside.md"))
    }
}
