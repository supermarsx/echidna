package com.echidna.app.ui.whitelist

import com.echidna.app.data.CommonApps

/**
 * Pure, Android-free assembly of whitelist rows.
 *
 * The ViewModel gathers the raw device state (which candidate packages are actually installed plus
 * their real PackageManager labels) and hands it here; this object owns the candidate universe, the
 * installed-only suggestion filter, suggestion metadata, label fallback, and ordering. Keeping it
 * free of [android] types means the installed-filter and suggestion logic is unit-testable without a
 * PackageManager or Robolectric.
 */
object WhitelistCatalog {

    data class Metadata(
        val categoryLabel: String?,
        val suggested: Boolean,
        val suggestionReason: String?,
        val heuristic: Boolean,
    )

    /**
     * @param whitelist persisted enable state per package.
     * @param bindings persisted per-app preset bindings.
     * @param installedPackages packages actually present on the device (launcher enumeration plus
     *   [android.content.pm.PackageManager.getApplicationInfo] resolve hits).
     * @param installedLabels real PackageManager labels for installed packages (pkg -> label).
     * @param onlyInstalledSuggestions when true (the default), curated suggestions that are NOT
     *   installed are hidden; when false, the full curated suggestion set is surfaced regardless of
     *   install state. Enabled/preset-bound apps are always kept so persisted state is never lost.
     */
    fun buildEntries(
        whitelist: Map<String, Boolean>,
        bindings: Map<String, String>,
        installedPackages: Set<String>,
        installedLabels: Map<String, String>,
        onlyInstalledSuggestions: Boolean,
    ): List<AppEntry> {
        val candidates = (installedPackages +
            whitelist.keys +
            bindings.keys +
            CommonApps.suggestedPackages()).distinct()
        return candidates
            .map { pkg ->
                val installed = pkg in installedPackages
                val metadata = metadataFor(pkg)
                AppEntry(
                    packageName = pkg,
                    label = resolveLabel(pkg, installed, installedLabels),
                    enabled = whitelist[pkg] ?: false,
                    presetId = bindings[pkg].orEmpty(),
                    suggested = metadata.suggested,
                    categoryLabel = metadata.categoryLabel,
                    suggestionReason = metadata.suggestionReason,
                    heuristic = metadata.heuristic,
                    installed = installed,
                )
            }
            // Always surface installed apps and anything carrying state; a suggestion that is not
            // installed appears only when the installed-only filter is off.
            .filter { entry ->
                entry.installed ||
                    entry.enabled ||
                    entry.presetId.isNotEmpty() ||
                    (entry.suggested && !onlyInstalledSuggestions)
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.enabled }
                    .thenByDescending { it.suggested }
                    .thenBy { it.categoryLabel.orEmpty() }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
    }

    /**
     * Friendly display name for a package: the real PackageManager label when installed and useful,
     * otherwise a curated [CommonApps] canonical name, otherwise the raw package id.
     */
    fun resolveLabel(
        packageName: String,
        installed: Boolean,
        installedLabels: Map<String, String>,
    ): String {
        if (installed) {
            val label = installedLabels[packageName]?.trim()
            // A real label that isn't just the package id (some apps have no proper label) wins.
            if (!label.isNullOrBlank() && label != packageName) return label
        }
        return CommonApps.canonicalName(packageName) ?: packageName
    }

    fun metadataFor(packageName: String): Metadata {
        val exactCategory = CommonApps.category(packageName)
        val inferredCategory = CommonApps.categoryFor(packageName)
        val heuristic = exactCategory == null && inferredCategory != null
        val suggested = inferredCategory?.suggested == true
        val reason = when {
            exactCategory?.suggested == true -> "${exactCategory.label} candidate"
            heuristic && inferredCategory?.suggested == true ->
                "Suggested by package-name heuristic"
            inferredCategory != null -> inferredCategory.label
            else -> null
        }
        return Metadata(
            categoryLabel = inferredCategory?.label,
            suggested = suggested,
            suggestionReason = reason,
            heuristic = heuristic,
        )
    }
}
