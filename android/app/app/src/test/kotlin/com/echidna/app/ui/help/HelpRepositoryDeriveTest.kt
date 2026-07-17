package com.echidna.app.ui.help

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure coverage for the title/group derivation used when loading bundled docs. */
class HelpRepositoryDeriveTest {

    @Test
    fun `title comes from the first level-1 heading`() {
        assertEquals(
            "Build & Install",
            HelpRepository.deriveTitle("build-install.md", "# Build & Install\n\nsome body\n"),
        )
    }

    @Test
    fun `title strips inline markup from the heading`() {
        assertEquals(
            "Why It Is Hard",
            HelpRepository.deriveTitle("why-hard.md", "# Why It *Is* `Hard`\n"),
        )
    }

    @Test
    fun `title falls back to a prettified filename when there is no heading`() {
        assertEquals("Developer Readme", HelpRepository.deriveTitle("developer_readme.md", "no heading here\n"))
    }

    @Test
    fun `group is Documentation for a top-level doc`() {
        assertEquals("Documentation", HelpRepository.deriveGroup("verification.md"))
    }

    @Test
    fun `group is the capitalized subdirectory name`() {
        assertEquals("Hardening", HelpRepository.deriveGroup("hardening/checklist.md"))
        assertEquals("Validation", HelpRepository.deriveGroup("validation/legacy-preprocessor-api33-session.md"))
    }
}
