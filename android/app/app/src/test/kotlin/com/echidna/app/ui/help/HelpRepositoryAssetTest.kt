package com.echidna.app.ui.help

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the build-time docs sync actually put the repository Markdown into the app assets and
 * that [HelpRepository] loads them. This asserts against the real bundled assets (staged by the
 * Gradle `stageHelpDocs` task), so it fails if the docs bundling regresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HelpRepositoryAssetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `bundled docs load from assets`() {
        val docs = HelpRepository.load(context)
        assertTrue("expected bundled docs under assets/${HelpRepository.ASSET_ROOT}", docs.isNotEmpty())
        // Known top-level and subdirectory docs are present, proving recursive bundling works.
        val ids = docs.map { it.id }.toSet()
        assertTrue("architecture.md missing: $ids", "architecture.md" in ids)
        assertTrue("hardening/checklist.md missing: $ids", "hardening/checklist.md" in ids)
    }

    @Test
    fun `loaded docs are grouped and titled`() {
        val docs = HelpRepository.load(context)
        val groups = HelpRepository.group(docs)
        assertTrue(groups.any { it.title == "Documentation" })
        assertTrue(groups.any { it.title == "Hardening" })
        // Every doc gets a non-blank title (heading or filename fallback).
        assertTrue(docs.all { it.title.isNotBlank() })
    }

    @Test
    fun `search index over the real docs finds bootloop`() {
        val docs = HelpRepository.load(context)
        val results = DocSearchIndex(docs).search("bootloop")
        assertTrue("expected 'bootloop' to match at least one bundled doc", results.isNotEmpty())
    }
}
