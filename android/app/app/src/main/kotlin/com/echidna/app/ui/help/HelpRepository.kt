package com.echidna.app.ui.help

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/** A named group of docs for the Help index (derived from the docs directory layout). */
data class HelpGroup(val title: String, val docs: List<HelpDoc>)

/**
 * Loads the repository Markdown docs that the Gradle `stageHelpDocs` task bundled into the APK
 * assets (under [ASSET_ROOT]). The set is discovered dynamically by walking the asset subtree, so
 * whatever docs exist at build/merge time appear in Help with no code change — nothing is hardcoded.
 *
 * Titles come from each doc's first level-1 heading (falling back to a prettified filename); groups
 * come from the directory layout (`hardening/` → "Hardening", top-level → "Documentation").
 */
object HelpRepository {

    private const val TAG = "HelpRepository"

    /** Asset subdirectory the docs are staged into (mirrors `helpDocsAssetSubdir` in build.gradle.kts). */
    const val ASSET_ROOT = "help/docs"

    private const val GROUP_TOP_LEVEL = "Documentation"

    // Preferred group ordering; any group not listed sorts after these, alphabetically.
    private val GROUP_ORDER = listOf(GROUP_TOP_LEVEL, "Hardening", "Validation")

    private val HEADING = Regex("(?m)^#\\s+(.+?)\\s*#*$")

    /** Reads and parses every bundled doc. Returns an empty list (logged) if none are bundled. */
    fun load(context: Context): List<HelpDoc> {
        val am = context.assets
        val paths = try {
            listMarkdown(am, ASSET_ROOT)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enumerate bundled docs under $ASSET_ROOT", t)
            emptyList()
        }
        if (paths.isEmpty()) {
            Log.w(TAG, "No bundled docs found under assets/$ASSET_ROOT — was stageHelpDocs run?")
            return emptyList()
        }
        return paths.mapNotNull { fullPath ->
            val markdown = runCatching {
                am.open(fullPath).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrElse {
                Log.w(TAG, "Failed to read doc asset $fullPath", it)
                return@mapNotNull null
            }
            val id = fullPath.removePrefix("$ASSET_ROOT/")
            HelpDoc(
                id = id,
                title = deriveTitle(id, markdown),
                group = deriveGroup(id),
                order = if (id.substringAfterLast('/') == "index.md") -1 else 0,
                markdown = markdown,
            )
        }.sortedWith(compareBy({ groupRank(it.group) }, { it.order }, { it.title.lowercase() }))
    }

    /** Groups loaded [docs] for the Help index, ordered per [GROUP_ORDER] then alphabetically. */
    fun group(docs: List<HelpDoc>): List<HelpGroup> =
        docs.groupBy { it.group }
            .map { (title, groupDocs) ->
                HelpGroup(title, groupDocs.sortedWith(compareBy({ it.order }, { it.title.lowercase() })))
            }
            .sortedWith(compareBy({ groupRank(it.title) }, { it.title.lowercase() }))

    /** Doc-title heading, or a prettified filename when a doc has no level-1 heading. */
    fun deriveTitle(id: String, markdown: String): String {
        val heading = HEADING.find(markdown)?.groupValues?.get(1)?.trim()
        if (!heading.isNullOrBlank()) {
            return MarkdownParser.parseInlines(heading).plainText().trim()
        }
        return prettifyFilename(id.substringAfterLast('/'))
    }

    /** Directory-derived group: a subdirectory becomes its capitalized name, top-level docs group together. */
    fun deriveGroup(id: String): String {
        val dir = id.substringBeforeLast('/', "")
        if (dir.isEmpty()) return GROUP_TOP_LEVEL
        return dir.substringBefore('/').replace('-', ' ').replace('_', ' ')
            .split(' ').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    }

    private fun prettifyFilename(fileName: String): String =
        fileName.removeSuffix(".md").replace('-', ' ').replace('_', ' ')
            .split(' ').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    private fun groupRank(group: String): Int =
        GROUP_ORDER.indexOf(group).let { if (it >= 0) it else GROUP_ORDER.size }

    /** Recursively lists `*.md` asset paths under [dir] (paths are relative to the assets root). */
    private fun listMarkdown(am: AssetManager, dir: String): List<String> {
        val entries = am.list(dir) ?: return emptyList()
        val result = ArrayList<String>()
        for (entry in entries) {
            val full = if (dir.isEmpty()) entry else "$dir/$entry"
            if (entry.endsWith(".md", ignoreCase = true)) {
                result.add(full)
            } else {
                // AssetManager gives no is-directory flag; a non-empty listing means it is one.
                val children = am.list(full)
                if (!children.isNullOrEmpty()) result.addAll(listMarkdown(am, full))
            }
        }
        return result
    }
}
