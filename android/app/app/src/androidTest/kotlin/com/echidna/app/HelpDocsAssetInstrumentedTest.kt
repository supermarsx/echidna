package com.echidna.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.echidna.app.ui.help.DocSearchIndex
import com.echidna.app.ui.help.HelpRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Confirms the build-time docs sync actually packaged the repository Markdown into the installed
 * APK's assets (on-device), and that the Help pipeline reads them: recursive subtree bundling,
 * grouping, and full-text search all operate on the real bundled docs.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class HelpDocsAssetInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun docsAreBundledInApkAssets() {
        val topLevel = context.assets.list(HelpRepository.ASSET_ROOT)?.toList().orEmpty()
        assertTrue("expected docs under assets/${HelpRepository.ASSET_ROOT}", topLevel.isNotEmpty())
        // A known top-level doc and a known subdirectory are both present (recursive bundling).
        assertTrue("architecture.md not bundled: $topLevel", topLevel.contains("architecture.md"))
        val hardening = context.assets.list("${HelpRepository.ASSET_ROOT}/hardening")?.toList().orEmpty()
        assertTrue("hardening/checklist.md not bundled: $hardening", hardening.contains("checklist.md"))
    }

    @Test
    fun referencedImageAssetsAreBundledInApkAssets() {
        // stageHelpDocs mirrors docs/assets/** (png/webp/svg) alongside the Markdown so the in-app
        // Help renderer can decode `![](assets/screenshots/x.png)` from the APK. Assert the tree is
        // present and carries at least one decodable raster (filename-agnostic; S1 owns the names).
        val screenshots = context.assets
            .list("${HelpRepository.ASSET_ROOT}/assets/screenshots")?.toList().orEmpty()
        assertTrue(
            "expected staged screenshots under assets/${HelpRepository.ASSET_ROOT}/assets/screenshots",
            screenshots.any { it.endsWith(".png", ignoreCase = true) },
        )
        // The staged asset is actually openable (a real byte stream the renderer can decode).
        val png = screenshots.first { it.endsWith(".png", ignoreCase = true) }
        val bytes = context.assets
            .open("${HelpRepository.ASSET_ROOT}/assets/screenshots/$png")
            .use { it.readBytes() }
        assertTrue("staged image $png is empty", bytes.isNotEmpty())
    }

    @Test
    fun helpRepositoryLoadsAndSearchesBundledDocs() {
        val docs = HelpRepository.load(context)
        assertTrue("no docs loaded", docs.isNotEmpty())
        assertTrue("architecture.md missing", docs.any { it.id == "architecture.md" })
        assertTrue("hardening/checklist.md missing", docs.any { it.id == "hardening/checklist.md" })
        // Full-text search over the real docs finds the approved-preview term.
        assertTrue(DocSearchIndex(docs).search("bootloop").isNotEmpty())
    }
}
