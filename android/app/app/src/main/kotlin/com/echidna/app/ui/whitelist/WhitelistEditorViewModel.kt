package com.echidna.app.ui.whitelist

import android.app.Application
import android.content.Context
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
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow<List<AppEntry>>(emptyList())
    val entries: StateFlow<List<AppEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * When true (the persisted default), the suggested-apps section is filtered to apps actually
     * installed on this device. Flip it off to browse the full curated catalog.
     */
    private val _onlyInstalled = MutableStateFlow(prefs.getBoolean(KEY_ONLY_INSTALLED, true))
    val onlyInstalled: StateFlow<Boolean> = _onlyInstalled.asStateFlow()

    val presets = repo.presets

    // Cached raw device snapshot so flipping the installed-only toggle can re-filter instantly
    // without re-querying the PackageManager.
    private var snapshot: DeviceSnapshot? = null

    init {
        refresh()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Flips the installed-only suggestion filter, persists it, and re-filters cached entries. */
    fun setOnlyInstalled(value: Boolean) {
        if (_onlyInstalled.value == value) return
        _onlyInstalled.value = value
        prefs.edit().putBoolean(KEY_ONLY_INSTALLED, value).apply()
        snapshot?.let { _entries.value = it.toEntries(value) }
    }

    /**
     * Reads the persisted whitelist + app bindings back from the control service, discovers which
     * candidate packages are actually installed (launcher enumeration plus a PackageManager resolve
     * for suggested/bound packages that have no launcher activity), resolves their real display
     * names, and hands the raw snapshot to [WhitelistCatalog] to assemble rows. Preserves the service
     * read-back wiring (t1-e4 Major 5; t5-e13 nicer UI; t8-e8 installed-filter + toggle).
     */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val snap = loadSnapshot()
            snapshot = snap
            _entries.value = snap.toEntries(_onlyInstalled.value)
            _loading.value = false
        }
    }

    private suspend fun loadSnapshot(): DeviceSnapshot = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        val bindings = repo.fetchWhitelistBindings()
        val launchable = repo.installedLaunchablePackages()
        // The candidate universe: launchable apps + anything already whitelisted/bound + every
        // curated suggestion (so we can test each for install even if it has no launcher activity).
        val candidates = (launchable +
            bindings.whitelist.keys +
            bindings.appBindings.keys +
            CommonApps.suggestedPackages()).distinct()
        // Launchable apps are definitely installed; resolve the rest so installed-but-not-launchable
        // packages (e.g. some dialers) are still detected. Visibility for these is granted by the
        // <queries> LAUNCHER block in AndroidManifest.xml (no QUERY_ALL_PACKAGES needed).
        val installed = LinkedHashSet(launchable)
        val labels = HashMap<String, String>()
        candidates.forEach { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@forEach
            installed.add(pkg)
            runCatching { pm.getApplicationLabel(info).toString() }
                .getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?.let { labels[pkg] = it }
        }
        DeviceSnapshot(
            whitelist = bindings.whitelist,
            bindings = bindings.appBindings,
            installedPackages = installed,
            installedLabels = labels,
        )
    }

    fun toggleApp(packageName: String, enabled: Boolean) {
        _entries.value = _entries.value.map {
            if (it.packageName == packageName) it.copy(enabled = enabled) else it
        }
        snapshot = snapshot?.let { it.copy(whitelist = it.whitelist + (packageName to enabled)) }
        repo.updateWhitelist(packageName, enabled)
    }

    fun setAppPreset(packageName: String, presetId: String) {
        _entries.value = _entries.value.map {
            if (it.packageName == packageName) it.copy(presetId = presetId) else it
        }
        snapshot = snapshot?.let { it.copy(bindings = it.bindings + (packageName to presetId)) }
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
        val info = runCatching { pm.getApplicationInfo(trimmed, 0) }.getOrNull()
        val installed = info != null
        val label = info?.let {
            runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()?.trim()
        }
        val labels = if (!label.isNullOrBlank()) mapOf(trimmed to label) else emptyMap()
        val metadata = WhitelistCatalog.metadataFor(trimmed)
        _entries.value = _entries.value + AppEntry(
            packageName = trimmed,
            label = WhitelistCatalog.resolveLabel(trimmed, installed, labels),
            enabled = true,
            presetId = "",
            suggested = metadata.suggested,
            categoryLabel = metadata.categoryLabel,
            suggestionReason = metadata.suggestionReason,
            heuristic = metadata.heuristic,
            installed = installed
        )
        // Keep the cached snapshot consistent so a later installed-only toggle keeps this row.
        snapshot = snapshot?.let {
            it.copy(
                whitelist = it.whitelist + (trimmed to true),
                installedPackages = if (installed) it.installedPackages + trimmed else it.installedPackages,
                installedLabels = if (!label.isNullOrBlank()) it.installedLabels + (trimmed to label) else it.installedLabels,
            )
        }
        repo.updateWhitelist(trimmed, true)
    }

    fun removeApp(packageName: String) {
        _entries.value = _entries.value.filterNot { it.packageName == packageName }
        snapshot = snapshot?.let { it.copy(whitelist = it.whitelist - packageName) }
        repo.updateWhitelist(packageName, false)
    }

    private fun isValidPackageName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 128) return false
        if (!trimmed.contains('.')) return false
        return trimmed.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == ':' }
    }

    /** Immutable raw device state, cached so the installed-only toggle re-filters without I/O. */
    private data class DeviceSnapshot(
        val whitelist: Map<String, Boolean>,
        val bindings: Map<String, String>,
        val installedPackages: Set<String>,
        val installedLabels: Map<String, String>,
    ) {
        fun toEntries(onlyInstalled: Boolean): List<AppEntry> =
            WhitelistCatalog.buildEntries(
                whitelist = whitelist,
                bindings = bindings,
                installedPackages = installedPackages,
                installedLabels = installedLabels,
                onlyInstalledSuggestions = onlyInstalled,
            )
    }

    companion object {
        private const val PREFS_NAME = "whitelist_editor_prefs"
        private const val KEY_ONLY_INSTALLED = "only_installed_suggestions"
    }
}
