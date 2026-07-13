package com.echidna.app.ui.whitelist

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.CommonApps
import com.echidna.app.data.ControlStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single row in the whitelist editor.
 *
 * @param packageName the app's package id (e.g. `com.whatsapp`) — always shown so the mapping is
 *   unambiguous.
 * @param label the friendly display name shown as the primary text — the real PackageManager label
 *   when installed, otherwise a curated [CommonApps] canonical name, otherwise the package id.
 * @param enabled whether the app is on the whitelist.
 * @param presetId the bound preset id (empty = Default).
 * @param suggested true for common voice/calling/social/game/streaming apps worth surfacing.
 * @param categoryLabel curated or inferred local category label, if one is known.
 * @param suggestionReason why the app is being suggested; null for ordinary installed apps.
 * @param heuristic true when the category came from package-name heuristics rather than catalog.
 * @param installed whether the app is actually installed on this device.
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val presetId: String,
    val suggested: Boolean,
    val categoryLabel: String?,
    val suggestionReason: String?,
    val heuristic: Boolean,
    val installed: Boolean
)

class WhitelistEditorViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ControlStateRepository

    private val _entries = MutableStateFlow<List<AppEntry>>(emptyList())
    val entries: StateFlow<List<AppEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val presets = repo.presets

    init {
        refresh()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /**
     * Reads the persisted whitelist + app bindings back from the control service and merges them
     * with the installed launchable apps, then resolves each package to a friendly display name and
     * flags common voice/comms apps — so the editor shows real names + package ids, not just ids
     * (t1-e4 Major 5; t5-e13 nicer UI). Preserves the service read-back wiring.
     */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val bindings = repo.fetchWhitelistBindings()
            val installed = repo.installedLaunchablePackages()
            _entries.value = buildEntries(bindings.whitelist, bindings.appBindings, installed)
            _loading.value = false
        }
    }

    private suspend fun buildEntries(
        whitelistMap: Map<String, Boolean>,
        bindingsMap: Map<String, String>,
        installedPackages: List<String>
    ): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        // Surface installed apps, anything already whitelisted/bound, and any known suggested app so
        // good voice-changer candidates show up even before they are added.
        val packages = (installedPackages +
            whitelistMap.keys +
            bindingsMap.keys +
            CommonApps.suggestedPackages()).distinct()
        packages
            .map { pkg ->
                val installed = isInstalled(pm, pkg)
                val metadata = appMetadata(pkg)
                AppEntry(
                    packageName = pkg,
                    label = resolveLabel(pm, pkg, installed),
                    enabled = whitelistMap[pkg] ?: false,
                    presetId = bindingsMap[pkg].orEmpty(),
                    suggested = metadata.suggested,
                    categoryLabel = metadata.categoryLabel,
                    suggestionReason = metadata.suggestionReason,
                    heuristic = metadata.heuristic,
                    installed = installed
                )
            }
            // Only surface un-installed apps if they carry state or are a suggestion worth adding.
            .filter { it.installed || it.enabled || it.presetId.isNotEmpty() || it.suggested }
            .sortedWith(
                compareByDescending<AppEntry> { it.enabled }
                    .thenByDescending { it.suggested }
                    .thenBy { it.categoryLabel.orEmpty() }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getApplicationInfo(packageName, 0); true }.getOrDefault(false)

    /**
     * Friendly display name for a package: the real PackageManager label when installed and useful,
     * otherwise a curated [CommonApps] canonical name, otherwise the raw package id.
     */
    private fun resolveLabel(pm: PackageManager, packageName: String, installed: Boolean): String {
        if (installed) {
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()?.trim()
            // A real label that isn't just the package id (some apps have no proper label) wins.
            if (!label.isNullOrBlank() && label != packageName) return label
        }
        return CommonApps.canonicalName(packageName) ?: packageName
    }

    fun toggleApp(packageName: String, enabled: Boolean) {
        _entries.value = _entries.value.map {
            if (it.packageName == packageName) it.copy(enabled = enabled) else it
        }
        repo.updateWhitelist(packageName, enabled)
    }

    fun setAppPreset(packageName: String, presetId: String) {
        _entries.value = _entries.value.map {
            if (it.packageName == packageName) it.copy(presetId = presetId) else it
        }
        repo.setAppPresetBinding(packageName, presetId)
    }

    fun addApp(packageName: String) {
        val trimmed = packageName.trim()
        if (!isValidPackageName(trimmed)) return
        if (_entries.value.any { it.packageName == trimmed }) {
            // Already listed — just enable it rather than duplicating the row.
            toggleApp(trimmed, true)
            return
        }
        val pm = getApplication<Application>().packageManager
        val installed = isInstalled(pm, trimmed)
        val metadata = appMetadata(trimmed)
        _entries.value = _entries.value + AppEntry(
            packageName = trimmed,
            label = resolveLabel(pm, trimmed, installed),
            enabled = true,
            presetId = "",
            suggested = metadata.suggested,
            categoryLabel = metadata.categoryLabel,
            suggestionReason = metadata.suggestionReason,
            heuristic = metadata.heuristic,
            installed = installed
        )
        repo.updateWhitelist(trimmed, true)
    }

    fun removeApp(packageName: String) {
        _entries.value = _entries.value.filterNot { it.packageName == packageName }
        repo.updateWhitelist(packageName, false)
    }

    private fun isValidPackageName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 128) return false
        if (!trimmed.contains('.')) return false
        return trimmed.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == ':' }
    }

    private fun appMetadata(packageName: String): AppMetadata {
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
        return AppMetadata(
            categoryLabel = inferredCategory?.label,
            suggested = suggested,
            suggestionReason = reason,
            heuristic = heuristic
        )
    }

    private data class AppMetadata(
        val categoryLabel: String?,
        val suggested: Boolean,
        val suggestionReason: String?,
        val heuristic: Boolean
    )
}
