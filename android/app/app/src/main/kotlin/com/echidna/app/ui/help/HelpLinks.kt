package com.echidna.app.ui.help

/**
 * Classifies and resolves a Markdown link destination relative to the doc it appears in.
 *
 * Pure (no Android deps) so link routing is unit-tested directly. Internal doc-to-doc links keep the
 * reader inside Help; same-doc `#anchor` links scroll in place; anything with a URL scheme opens
 * externally (the browser); links that resolve to no bundled doc are reported [Unresolved] so the UI
 * can no-op instead of navigating nowhere.
 */
sealed interface LinkTarget {
    /** A bundled doc (by id, e.g. `hardening/checklist.md`), optionally to a section [anchor]. */
    data class InternalDoc(val docId: String, val anchor: String?) : LinkTarget

    /** A `#anchor` within the current doc. */
    data class SameDocAnchor(val anchor: String) : LinkTarget

    /** An external URL (http/https/mailto/…) to open outside the app. */
    data class External(val url: String) : LinkTarget

    /** A relative link that does not resolve to any bundled doc. */
    data class Unresolved(val destination: String) : LinkTarget
}

object HelpLinks {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

    /**
     * Resolves [destination] (as written in the doc) for a link inside [currentDocId], given the set
     * of bundled [knownDocIds] (relative asset paths like `verification.md`, `hardening/rt-safety.md`).
     */
    fun resolve(currentDocId: String, destination: String, knownDocIds: Set<String>): LinkTarget {
        val dest = destination.trim()
        if (dest.isEmpty()) return LinkTarget.Unresolved(destination)

        // Pure same-doc anchor.
        if (dest.startsWith("#")) return LinkTarget.SameDocAnchor(dest.removePrefix("#"))

        // Absolute URL / mail / other scheme → external.
        if (SCHEME.containsMatchIn(dest) && !dest.startsWith("./") && !dest.startsWith("../")) {
            return LinkTarget.External(dest)
        }

        // Relative link: split off any #anchor, normalize against the current doc's directory.
        val anchor = dest.substringAfter('#', "").ifEmpty { null }
        val path = dest.substringBefore('#')
        if (path.isEmpty() && anchor != null) return LinkTarget.SameDocAnchor(anchor)

        val resolvedId = normalize(currentDocId, path)
        return when {
            resolvedId != null && resolvedId in knownDocIds -> LinkTarget.InternalDoc(resolvedId, anchor)
            // A relative link to a non-.md file or a doc not bundled: unresolved (UI no-ops).
            else -> LinkTarget.Unresolved(destination)
        }
    }

    /**
     * Normalizes a relative [path] against the directory of [currentDocId], collapsing `.`/`..`.
     * Returns the canonical bundled-doc id (forward-slash separated), or null if it escapes the root.
     */
    internal fun normalize(currentDocId: String, path: String): String? {
        val baseDir = currentDocId.substringBeforeLast('/', "")
        val combined = if (path.startsWith("/")) path.removePrefix("/") else if (baseDir.isEmpty()) path else "$baseDir/$path"
        val stack = ArrayList<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.size - 1)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/").ifEmpty { null }
    }
}
