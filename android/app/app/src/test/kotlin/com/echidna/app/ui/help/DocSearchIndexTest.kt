package com.echidna.app.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-text search coverage. Uses synthetic docs so the expected ranking is deterministic, and
 * reproduces the approved preview: searching "bootloop" surfaces the recovery, limitations, and
 * checklist §15 sections, with heading matches outranking body-only matches.
 */
class DocSearchIndexTest {

    private val recovery = HelpDoc(
        id = "recovery.md", title = "Recovery", group = "Documentation", order = 0,
        markdown = """
            # Recovery

            ## Recovering from a bootloop

            If the device is stuck in a bootloop, use the Magisk disable marker from recovery.
        """.trimIndent(),
    )
    private val limitations = HelpDoc(
        id = "limitations.md", title = "Limitations", group = "Documentation", order = 0,
        markdown = """
            # Limitations

            ## Boot safety

            A bad module can leave some devices in a bootloop until the marker is set.
        """.trimIndent(),
    )
    private val checklist = HelpDoc(
        id = "hardening/checklist.md", title = "Checklist", group = "Hardening", order = 0,
        markdown = """
            # Checklist

            ## 14. SELinux policy

            Confirm the policy loads.

            ## 15. Bootloop watchdog

            Verify the bootloop watchdog disables the module after repeated failures.
        """.trimIndent(),
    )
    private val architecture = HelpDoc(
        id = "architecture.md", title = "Architecture", group = "Documentation", order = 0,
        markdown = "# Architecture\n\n## Components\n\nThe DSP engine and control service.\n",
    )

    private val index = DocSearchIndex(listOf(recovery, limitations, checklist, architecture))

    @Test
    fun `bootloop surfaces recovery, limitations, and checklist section 15`() {
        val results = index.search("bootloop")
        val docs = results.map { it.docId }.toSet()
        assertTrue("recovery expected: $docs", "recovery.md" in docs)
        assertTrue("limitations expected: $docs", "limitations.md" in docs)
        assertTrue("checklist expected: $docs", "hardening/checklist.md" in docs)
        // A doc with no bootloop mention must not appear.
        assertFalse("architecture must not match", "architecture.md" in docs)

        // The checklist hit points at the §15 section, not §14.
        val checklistHit = results.first { it.docId == "hardening/checklist.md" }
        assertTrue(checklistHit.sectionHeading.contains("15"))
        assertTrue(checklistHit.sectionHeading.lowercase().contains("bootloop"))
    }

    @Test
    fun `heading match outranks body-only match`() {
        val results = index.search("bootloop")
        // Top result must be a section whose heading contains the term (recovery or checklist),
        // never the body-only "Boot safety" section from limitations.
        assertTrue(results.first().sectionHeading.lowercase().contains("bootloop"))
        val limitationsRank = results.indexOfFirst { it.docId == "limitations.md" }
        val recoveryRank = results.indexOfFirst { it.docId == "recovery.md" }
        assertTrue("recovery (heading hit) should outrank limitations (body hit)", recoveryRank < limitationsRank)
    }

    @Test
    fun `search is case-insensitive`() {
        assertEquals(index.search("bootloop").map { it.docId }, index.search("BOOTLOOP").map { it.docId })
    }

    @Test
    fun `multi-word query requires all terms in the same section`() {
        val results = index.search("bootloop watchdog")
        // Only checklist §15 contains both terms; recovery/limitations mention only "bootloop".
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.docId == "hardening/checklist.md" })
        assertTrue(results.first().sectionHeading.contains("15"))
    }

    @Test
    fun `blank query returns nothing`() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun `results carry a snippet around the match`() {
        val hit = index.search("bootloop").first { it.docId == "recovery.md" }
        assertTrue(hit.snippet.lowercase().contains("bootloop"))
    }

    @Test
    fun `sections are split by heading`() {
        // checklist has intro-less content plus §14 and §15 headings.
        val checklistSections = index.allSections.filter { it.docId == "hardening/checklist.md" }
        assertTrue(checklistSections.any { it.heading.contains("14") })
        assertTrue(checklistSections.any { it.heading.contains("15") })
    }

    @Test
    fun `tokenize splits on non-alphanumerics and lowercases`() {
        assertEquals(listOf("boot", "loop", "watchdog"), DocSearchIndex.tokenize("Boot-Loop, watchdog!"))
    }

    @Test
    fun `countOccurrences counts non-overlapping matches`() {
        assertEquals(2, DocSearchIndex.countOccurrences("a-b-a-b-a".replace("-", ""), "ab"))
        assertEquals(3, DocSearchIndex.countOccurrences("bootloop bootloop bootloop", "bootloop"))
        assertEquals(0, DocSearchIndex.countOccurrences("nothing here", "xyz"))
    }
}
