package com.echidna.app.data

import android.content.Context
import android.content.Intent
import com.echidna.app.model.AudioStackProbe
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.ControlState
import com.echidna.app.model.CpuHeatPoint
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.EffectModule
import com.echidna.app.model.FormantState
import com.echidna.app.model.LatencyBucket
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.LegacyPreprocessorControlState
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import com.echidna.app.model.PresetValidator
import com.echidna.app.model.PresetWarning
import com.echidna.app.model.SettingsProfile
import com.echidna.app.model.SettingsState
import com.echidna.app.model.TelemetrySample
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TunerState
import com.echidna.app.model.WhitelistBindings
import com.echidna.app.system.ControlServiceClient
import com.echidna.app.system.ControlServiceSyncSnapshot
import com.echidna.app.system.LegacyPreprocessorServiceResult
import com.echidna.app.system.EchidnaWidgetProvider
import com.echidna.app.system.NotificationController
import com.echidna.app.ui.diagnostics.NoteUtils
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_APP_STATE_BYTES = 10L * 1024L * 1024L

object ControlStateRepository {
    // Spec §12: panic engages a global bypass for N minutes.
    private const val PANIC_HOLD_MS = 5L * 60L * 1000L

    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var serviceClient: ControlServiceClient
    private lateinit var presetStateWriter: LatestAtomicTextFileWriter
    private lateinit var settingsStateWriter: LatestAtomicTextFileWriter

    private val _masterEnabled = MutableStateFlow(true)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private val _bypass = MutableStateFlow(false)
    val bypass: StateFlow<Boolean> = _bypass.asStateFlow()

    private val _panicUntilEpochMs = MutableStateFlow(0L)

    private val _sidetoneEnabled = MutableStateFlow(false)
    val sidetoneEnabled: StateFlow<Boolean> = _sidetoneEnabled.asStateFlow()

    private val _sidetoneLevel = MutableStateFlow(-24f)
    val sidetoneLevel: StateFlow<Float> = _sidetoneLevel.asStateFlow()

    private val _latencyMode = MutableStateFlow(LatencyMode.LOW_LATENCY)
    val latencyMode: StateFlow<LatencyMode> = _latencyMode.asStateFlow()

    // De-faked: real state is populated from the service's getModuleStatus()/getControlState().
    private val _engineStatus = MutableStateFlow(
        EngineStatus(nativeInstalled = false, active = false, selinuxMode = "Unknown", latencyMs = 0)
    )
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private val _moduleStatus = MutableStateFlow<ModuleStatus?>(null)
    val moduleStatus: StateFlow<ModuleStatus?> = _moduleStatus.asStateFlow()

    private val _metrics = MutableStateFlow(
        DspMetrics(
            inputRms = -120f,
            inputPeak = -120f,
            outputRms = -120f,
            outputPeak = -120f,
            cpuLoadPercent = 0f,
            endToEndLatencyMs = 0f,
            xruns = 0
        )
    )
    val metrics: StateFlow<DspMetrics> = _metrics.asStateFlow()

    private val _telemetry = MutableStateFlow(defaultTelemetry())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _whitelistBindings =
        MutableStateFlow(WhitelistBindings(emptyMap(), emptyMap()))
    val whitelistBindings: StateFlow<WhitelistBindings> = _whitelistBindings.asStateFlow()
    @Volatile private var appBindingsAuthoritative = false
    @Volatile private var whitelistAuthoritative = false

    private val _latencyHistogram = MutableStateFlow<List<LatencyBucket>>(emptyList())
    val latencyHistogram: StateFlow<List<LatencyBucket>> = _latencyHistogram.asStateFlow()

    private val _cpuHeatmap = MutableStateFlow<List<CpuHeatPoint>>(emptyList())
    val cpuHeatmap: StateFlow<List<CpuHeatPoint>> = _cpuHeatmap.asStateFlow()

    private val _tunerState = MutableStateFlow(TunerState("—", 0f, 0f, 0f, 0f))
    val tunerState: StateFlow<TunerState> = _tunerState.asStateFlow()

    private val _formantState = MutableStateFlow(FormantState(0f, 0f))
    val formantState: StateFlow<FormantState> = _formantState.asStateFlow()

    private val _presetWarnings = MutableStateFlow<List<PresetWarning>>(emptyList())
    val presetWarnings: StateFlow<List<PresetWarning>> = _presetWarnings.asStateFlow()

    private val _telemetryOptIn = MutableStateFlow(false)
    val telemetryOptIn: StateFlow<Boolean> = _telemetryOptIn.asStateFlow()

    private val _presets = MutableStateFlow(defaultPresets())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _activePresetId = MutableStateFlow(_presets.value.first().id)
    val activePreset: StateFlow<Preset> = combine(_presets, _activePresetId) { list, id ->
        // Never let a transient (list, id) mismatch throw: `_activePresetId` can briefly point
        // at an id absent from `_presets` while persisted state is loaded or the active preset is
        // deleted. Fall back to the first preset rather than crash the Dispatchers.Default coroutine.
        list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: _presets.value.first()
    }.stateIn(scope, SharingStarted.Eagerly, _presets.value.first())

    private val _compatibilityState = MutableStateFlow<CompatibilityResult?>(null)
    val compatibilityState: StateFlow<CompatibilityResult?> = _compatibilityState.asStateFlow()

    private val _defaultPresetId = MutableStateFlow(_presets.value.first().id)
    val defaultPresetId: StateFlow<String> = _defaultPresetId.asStateFlow()

    private val _notificationEnabled = MutableStateFlow(true)
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled.asStateFlow()

    private val _startWithSystem = MutableStateFlow(false)
    val startWithSystem: StateFlow<Boolean> = _startWithSystem.asStateFlow()

    private val _autoStartEngine = MutableStateFlow(false)
    val autoStartEngine: StateFlow<Boolean> = _autoStartEngine.asStateFlow()

    private val _restoreLastProfile = MutableStateFlow(true)
    val restoreLastProfile: StateFlow<Boolean> = _restoreLastProfile.asStateFlow()

    private val _dspEngineMode = MutableStateFlow(DspEngineMode.NATIVE_FIRST)
    val dspEngineMode: StateFlow<DspEngineMode> = _dspEngineMode.asStateFlow()

    private val _debugMode = MutableStateFlow(false)
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _verboseLogging = MutableStateFlow(false)
    val verboseLogging: StateFlow<Boolean> = _verboseLogging.asStateFlow()

    private val _failClosed = MutableStateFlow(true)
    val failClosed: StateFlow<Boolean> = _failClosed.asStateFlow()

    private val _autoBypassOnError = MutableStateFlow(true)
    val autoBypassOnError: StateFlow<Boolean> = _autoBypassOnError.asStateFlow()

    private val _panicHoldMinutes = MutableStateFlow(5)
    val panicHoldMinutes: StateFlow<Int> = _panicHoldMinutes.asStateFlow()

    private val _quickControlsEnabled = MutableStateFlow(true)
    val quickControlsEnabled: StateFlow<Boolean> = _quickControlsEnabled.asStateFlow()

    private val _widgetControlsEnabled = MutableStateFlow(true)
    val widgetControlsEnabled: StateFlow<Boolean> = _widgetControlsEnabled.asStateFlow()

    private val _showInstallAlerts = MutableStateFlow(true)
    val showInstallAlerts: StateFlow<Boolean> = _showInstallAlerts.asStateFlow()

    private val _showBridgeAlerts = MutableStateFlow(true)
    val showBridgeAlerts: StateFlow<Boolean> = _showBridgeAlerts.asStateFlow()

    private val _showHardwareAlerts = MutableStateFlow(true)
    val showHardwareAlerts: StateFlow<Boolean> = _showHardwareAlerts.asStateFlow()

    private val _showInstallMixupAlerts = MutableStateFlow(true)
    val showInstallMixupAlerts: StateFlow<Boolean> = _showInstallMixupAlerts.asStateFlow()

    private val _alertLatencyThresholdMs = MutableStateFlow(40)
    val alertLatencyThresholdMs: StateFlow<Int> = _alertLatencyThresholdMs.asStateFlow()

    private val _alertXrunThreshold = MutableStateFlow(3)
    val alertXrunThreshold: StateFlow<Int> = _alertXrunThreshold.asStateFlow()

    private val _remindCompatibilityProbe = MutableStateFlow(true)
    val remindCompatibilityProbe: StateFlow<Boolean> = _remindCompatibilityProbe.asStateFlow()

    private val _settingsProfiles = MutableStateFlow<List<SettingsProfile>>(emptyList())
    val settingsProfiles: StateFlow<List<SettingsProfile>> = _settingsProfiles.asStateFlow()

    private val _activeSettingsProfileId = MutableStateFlow<String?>(null)
    val activeSettingsProfileId: StateFlow<String?> = _activeSettingsProfileId.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _legacyPreprocessorState = MutableStateFlow(LegacyPreprocessorControlState())
    val legacyPreprocessorState: StateFlow<LegacyPreprocessorControlState> =
        _legacyPreprocessorState.asStateFlow()
    private val legacyPreprocessorOperation = AtomicLong(0L)

    private var observersStarted = false

    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            presetStateWriter = LatestAtomicTextFileWriter(
                java.io.File(context.filesDir, "echidna_presets.json")
            )
            settingsStateWriter = LatestAtomicTextFileWriter(
                java.io.File(context.filesDir, "echidna_settings.json")
            )
            loadPersistedPresets()
            // Seed first-run defaults and immediately rewrite legacy presets after their one-time
            // id migration. Otherwise a user can create a per-app binding before any preset edit,
            // restart, and find that every generated default id changed underneath that binding.
            persistPresets()
            loadPersistedSettings()
            NotificationController.ensureChannel(context)
            if (_notificationEnabled.value) {
                NotificationController.updateNotification(context)
            } else {
                NotificationController.cancel(context)
            }
            serviceClient = ControlServiceClient(context)
            // Queue the complete app-owned state before the asynchronous bind. The client applies
            // only the newest queued value once the service connection is confirmed.
            synchronizeServiceState()
            scope.launch {
                serviceClient.connectionState.collect { connected ->
                    if (connected) {
                        fetchWhitelistBindings()
                        refreshLegacyPreprocessorState(showLoading = true)
                    } else {
                        markLegacyPreprocessorDisconnected()
                    }
                }
            }
            scope.launch {
                serviceClient.telemetryUpdates.collect { payload ->
                    TelemetryParser.parse(payload)?.let { applyTelemetry(it) }
                }
            }
            serviceClient.bind()
            scope.launch {
                var tick = 0L
                while (true) {
                    delay(2000)
                    serviceClient.fetchSnapshot()?.let { snapshot ->
                        TelemetryParser.parse(snapshot)?.let { applyTelemetry(it) }
                    }
                    if (serviceClient.isBound()) {
                        _telemetryOptIn.value = serviceClient.isTelemetryOptedIn()
                        refreshSettingsState()
                        // Global control state is authoritative; mirror it locally every cycle.
                        serviceClient.getControlState()?.let { json ->
                            TelemetryParser.parseControlState(json)?.let { applyControlState(it) }
                        }
                        // Module/SELinux/HAL probes change slowly — refresh every ~10s.
                        if (tick % 5L == 0L) {
                            fetchWhitelistBindings()
                            refreshLegacyPreprocessorState(showLoading = false)
                            serviceClient.getModuleStatus()?.let { json ->
                                TelemetryParser.parseModuleStatus(json)?.let { applyModuleStatus(it) }
                            }
                        }
                    }
                    tick += 1
                }
            }
        }
        if (!observersStarted) {
            observersStarted = true
            scope.launch {
                combine(masterEnabled, activePreset, notificationEnabled) { master, preset, notif ->
                    Triple(master, preset, notif)
                }.collect { (_, preset, notifEnabled) ->
                    if (notifEnabled) {
                        NotificationController.updateNotification(context)
                    } else {
                        NotificationController.cancel(context)
                    }
                    updatePresetWarnings(preset)
                    EchidnaWidgetProvider.updateAll(context)
                }
            }
            scope.launch {
                activePreset.collect {
                    pushPresetToService()
                }
            }
        }
        updatePresetWarnings(activePreset.value)
    }

    fun toggleMaster() {
        setMasterEnabled(!_masterEnabled.value)
    }

    fun setMasterEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
        // Reflect the new master state in the derived engine status immediately, so the status
        // card doesn't lag a poll behind the toggle (and isn't clobbered by the next telemetry).
        _engineStatus.value = _engineStatus.value.copy(active = engineActive())
        commitSettingsChange()
        synchronizeServiceState()
    }

    fun setBypass(bypass: Boolean) {
        _bypass.value = bypass
        _engineStatus.value = _engineStatus.value.copy(active = engineActive(), bypass = _bypass.value)
        commitSettingsChange()
        synchronizeServiceState()
    }

    fun setSidetoneEnabled(enabled: Boolean) {
        _sidetoneEnabled.value = enabled
        commitSettingsChange()
        pushSidetone()
    }

    fun updateSidetone(levelDb: Float) {
        _sidetoneLevel.value = levelDb.coerceIn(-60f, -6f)
        commitSettingsChange()
        pushSidetone()
    }

    private fun pushSidetone() {
        synchronizeServiceState()
    }

    private fun pushEngineMode() {
        synchronizeServiceState()
    }

    fun setLatencyMode(mode: LatencyMode) {
        _latencyMode.value = mode
        _engineStatus.value = _engineStatus.value.copy(latencyMs = mode.targetMs)
        commitSettingsChange()
        synchronizeServiceState()
    }

    /** Engages the time-bounded global panic gate without overwriting base controls (spec §12). */
    fun triggerPanic(holdMs: Long = PANIC_HOLD_MS) {
        val now = System.currentTimeMillis()
        val deadline = when {
            holdMs <= 0L -> 0L
            holdMs >= Long.MAX_VALUE - now -> Long.MAX_VALUE
            else -> now + holdMs
        }
        _panicUntilEpochMs.value = deadline
        _engineStatus.value = _engineStatus.value.copy(active = engineActive())
        persistPresets()
        if (::serviceClient.isInitialized) {
            scope.launch { serviceClient.triggerPanic(holdMs) }
        }
        if (deadline > 0L) {
            scope.launch {
                while (_panicUntilEpochMs.value == deadline) {
                    val remainingMs = deadline - System.currentTimeMillis()
                    if (remainingMs <= 0L) break
                    delay(remainingMs)
                }
                if (_panicUntilEpochMs.value == deadline) {
                    _panicUntilEpochMs.value = 0L
                    persistPresets()
                    _engineStatus.value = _engineStatus.value.copy(active = engineActive())
                }
            }
        }
    }

    fun selectPreset(presetId: String) {
        if (_presets.value.any { it.id == presetId }) {
            _activePresetId.value = presetId
            persistPresets()
            _presets.value.firstOrNull { it.id == presetId }?.let { updatePresetWarnings(it) }
            if (_presets.value.any { it.id == presetId }) pushPresetToService()
        }
    }

    fun cyclePreset() {
        val list = _presets.value
        val idx = list.indexOfFirst { it.id == _activePresetId.value }
        if (idx >= 0) {
            val next = list[(idx + 1) % list.size]
            _activePresetId.value = next.id
            persistPresets()
            pushPresetToService()
        }
    }

    fun createPreset(name: String, description: String?, basePresetId: String?): String {
        val base = basePresetId?.let { id -> _presets.value.find { it.id == id } }
        val template = base ?: defaultEmptyPreset()
        val preset = template.copy(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            modules = template.modules.map { cloneModule(it) }
        )
        _presets.value = _presets.value + preset
        persistPresets()
        synchronizeServiceState()
        return preset.id
    }

    fun deletePreset(id: String) {
        val list = _presets.value
        if (list.size == 1) return
        val filtered = list.filterNot { it.id == id }
        if (filtered.size != list.size) {
            // Re-home the active/default ids off the deleted preset BEFORE publishing the filtered
            // list, so the `activePreset` combine never sees a (filtered, deleted-id) mismatch.
            if (_activePresetId.value == id) {
                _activePresetId.value = filtered.first().id
                updatePresetWarnings(filtered.first())
            }
            if (_defaultPresetId.value == id) {
                _defaultPresetId.value = filtered.first().id
            }
            _presets.value = filtered
            if (appBindingsAuthoritative) {
                _whitelistBindings.value = _whitelistBindings.value.copy(
                    appBindings = _whitelistBindings.value.appBindings
                        .filterValues { presetId -> presetId != id }
                )
            }
            persistPresets()
            synchronizeServiceState()
        }
    }

    fun updatePreset(preset: Preset) {
        _presets.value = _presets.value.map { if (it.id == preset.id) preset else it }
        if (_activePresetId.value == preset.id) {
            updatePresetWarnings(preset)
        }
        persistPresets()
        synchronizeServiceState()
    }

    fun renamePreset(id: String, name: String) {
        _presets.value = _presets.value.map { if (it.id == id) it.copy(name = name) else it }
        persistPresets()
        synchronizeServiceState()
    }

    fun setDefaultPreset(id: String) {
        if (_presets.value.any { it.id == id }) {
            _defaultPresetId.value = id
            commitSettingsChange()
            persistPresets()
            synchronizeServiceState()
        }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        _notificationEnabled.value = enabled
        commitSettingsChange()
        if (::context.isInitialized) {
            if (enabled) {
                NotificationController.updateNotification(context)
            } else {
                NotificationController.cancel(context)
            }
        }
    }

    fun setStartWithSystem(enabled: Boolean) {
        _startWithSystem.value = enabled
        commitSettingsChange()
    }

    fun setAutoStartEngine(enabled: Boolean) {
        _autoStartEngine.value = enabled
        commitSettingsChange()
    }

    fun setRestoreLastProfile(enabled: Boolean) {
        _restoreLastProfile.value = enabled
        commitSettingsChange()
    }

    fun setDspEngineMode(mode: DspEngineMode) {
        _dspEngineMode.value = mode
        commitSettingsChange()
        pushEngineMode()
    }

    fun setDebugMode(enabled: Boolean) {
        _debugMode.value = enabled
        commitSettingsChange()
    }

    fun setVerboseLogging(enabled: Boolean) {
        _verboseLogging.value = enabled
        commitSettingsChange()
    }

    fun setFailClosed(enabled: Boolean) {
        _failClosed.value = enabled
        commitSettingsChange()
    }

    fun setAutoBypassOnError(enabled: Boolean) {
        _autoBypassOnError.value = enabled
        commitSettingsChange()
    }

    fun setPanicHoldMinutes(minutes: Int) {
        _panicHoldMinutes.value = minutes.coerceIn(1, 60)
        commitSettingsChange()
    }

    fun setQuickControlsEnabled(enabled: Boolean) {
        _quickControlsEnabled.value = enabled
        commitSettingsChange()
    }

    fun setWidgetControlsEnabled(enabled: Boolean) {
        _widgetControlsEnabled.value = enabled
        commitSettingsChange()
        if (::context.isInitialized) {
            EchidnaWidgetProvider.updateAll(context)
        }
    }

    fun setShowInstallAlerts(enabled: Boolean) {
        _showInstallAlerts.value = enabled
        commitSettingsChange()
    }

    fun setShowBridgeAlerts(enabled: Boolean) {
        _showBridgeAlerts.value = enabled
        commitSettingsChange()
    }

    fun setShowHardwareAlerts(enabled: Boolean) {
        _showHardwareAlerts.value = enabled
        commitSettingsChange()
    }

    fun setShowInstallMixupAlerts(enabled: Boolean) {
        _showInstallMixupAlerts.value = enabled
        commitSettingsChange()
    }

    fun setAlertLatencyThresholdMs(thresholdMs: Int) {
        _alertLatencyThresholdMs.value = thresholdMs.coerceIn(5, 250)
        commitSettingsChange()
    }

    fun setAlertXrunThreshold(threshold: Int) {
        _alertXrunThreshold.value = threshold.coerceIn(1, 100)
        commitSettingsChange()
    }

    fun setRemindCompatibilityProbe(enabled: Boolean) {
        _remindCompatibilityProbe.value = enabled
        commitSettingsChange()
    }

    fun setLegacyPreprocessorEnabled(enabled: Boolean) {
        if (!::serviceClient.isInitialized) return
        val current = _legacyPreprocessorState.value
        if (
            !current.loaded || !current.available || current.updating || current.enabled == enabled
        ) {
            return
        }
        val operation = legacyPreprocessorOperation.incrementAndGet()
        _legacyPreprocessorState.value = current.copy(updating = true, error = null)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                serviceClient.updateLegacyPreprocessorEnabled(enabled)
            }
            applyLegacyPreprocessorResult(operation, result)
        }
    }

    fun runCompatibilityProbe() {
        scope.launch {
            _compatibilityState.value = null
            val status = if (::serviceClient.isInitialized) {
                // refreshStatus() forces a fresh privileged probe; fall back to the cached one.
                (serviceClient.refreshStatus() ?: serviceClient.getModuleStatus())
                    ?.let { TelemetryParser.parseModuleStatus(it) }
            } else {
                null
            }
            status?.let { applyModuleStatus(it) }
            _compatibilityState.value = status?.let { buildCompatibilityFromStatus(it) }
                ?: unavailableCompatibilityResult()
        }
    }

    fun importPreset(json: String): String? {
        val imported = PresetSerializer.fromJson(json) ?: return null
        val preset = if (_presets.value.any { it.id == imported.id }) {
            imported.copy(id = UUID.randomUUID().toString())
        } else {
            imported
        }
        _presets.value = _presets.value + preset
        persistPresets()
        synchronizeServiceState()
        return preset.id
    }

    fun exportPreset(presetId: String): String? {
        val preset = _presets.value.firstOrNull { it.id == presetId } ?: return null
        return PresetSerializer.toJson(preset)
    }

    fun exportAllPresets(): String {
        val array = org.json.JSONArray()
        _presets.value.forEach { preset ->
            val json = PresetSerializer.toJson(preset)
            array.put(org.json.JSONObject(json))
        }
        return array.toString()
    }

    fun sharePreset(presetId: String): String? = exportPreset(presetId)

    fun createSettingsProfile(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val now = System.currentTimeMillis()
        val profile = SettingsProfile(
            id = UUID.randomUUID().toString(),
            name = trimmed.take(80),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            settings = currentSettingsState()
        )
        _settingsProfiles.value = _settingsProfiles.value + profile
        _activeSettingsProfileId.value = profile.id
        persistSettings()
        return profile.id
    }

    fun applySettingsProfile(profileId: String): Boolean {
        val profile = _settingsProfiles.value.firstOrNull { it.id == profileId } ?: return false
        applySettingsState(profile.settings, pushSideEffects = true)
        _activeSettingsProfileId.value = profile.id
        persistSettings()
        return true
    }

    fun deleteSettingsProfile(profileId: String): Boolean {
        val profiles = _settingsProfiles.value
        val filtered = profiles.filterNot { it.id == profileId }
        if (filtered.size == profiles.size) return false
        _settingsProfiles.value = filtered
        if (_activeSettingsProfileId.value == profileId) {
            _activeSettingsProfileId.value = null
        }
        persistSettings()
        return true
    }

    fun exportSettingsProfile(profileId: String): String? =
        _settingsProfiles.value.firstOrNull { it.id == profileId }
            ?.let(SettingsProfileSerializer::profileToJson)

    fun exportCurrentSettings(): String =
        SettingsProfileSerializer.settingsToJson(currentSettingsState())

    fun importSettingsProfile(json: String): String? {
        val imported = SettingsProfileSerializer.profileFromJson(json) ?: return null
        val profile = if (_settingsProfiles.value.any { it.id == imported.id }) {
            imported.copy(id = UUID.randomUUID().toString())
        } else {
            imported
        }
        _settingsProfiles.value = _settingsProfiles.value + profile
        persistSettings()
        return profile.id
    }

    fun updateWhitelist(packageName: String, enabled: Boolean) {
        if (packageName.isBlank()) return
        whitelistAuthoritative = true
        _whitelistBindings.value = _whitelistBindings.value.copy(
            whitelist = _whitelistBindings.value.whitelist + (packageName to enabled),
        )
        persistPresets()
        synchronizeServiceState()
    }

    fun setAppPresetBinding(packageName: String, presetId: String) {
        if (packageName.isBlank()) return
        val current = _whitelistBindings.value
        val nextBindings = if (presetId.isBlank()) {
            current.appBindings - packageName
        } else {
            if (_presets.value.none { it.id == presetId }) return
            current.appBindings + (packageName to presetId)
        }
        appBindingsAuthoritative = true
        _whitelistBindings.value = current.copy(appBindings = nextBindings)
        persistPresets()
        synchronizeServiceState()
    }

    private fun persistPresets() {
        if (!::context.isInitialized) return
        // Capture one immutable transaction before dispatch. The single writer preserves UI
        // mutation order; AtomicFile prevents a crash/reboot from leaving truncated JSON.
        val payload = PresetStoreCodec.encode(
            PersistedPresetStore(
                presets = _presets.value,
                activePresetId = _activePresetId.value,
                defaultPresetId = _defaultPresetId.value,
                appBindings = if (appBindingsAuthoritative) {
                    _whitelistBindings.value.appBindings
                } else {
                    null
                },
                whitelist = if (whitelistAuthoritative) {
                    _whitelistBindings.value.whitelist
                } else {
                    null
                },
                panicUntilEpochMs = _panicUntilEpochMs.value,
            )
        )
        presetStateWriter.submit(payload)
    }

    private fun loadPersistedPresets() {
        if (!::context.isInitialized) return
        val file = java.io.File(context.filesDir, "echidna_presets.json")
        if (!file.exists()) return
        val restored = runCatching {
            if (file.length() > MAX_APP_STATE_BYTES) return
            PresetStoreCodec.decode(readAtomicUtf8(file))
        }.getOrNull() ?: return
        // Repair/assign ids before publishing the list so activePreset never observes a mismatch.
        _activePresetId.value = restored.activePresetId
        _defaultPresetId.value = restored.defaultPresetId
        _presets.value = restored.presets
        restored.appBindings?.let { bindings ->
            appBindingsAuthoritative = true
            _whitelistBindings.value = _whitelistBindings.value.copy(appBindings = bindings)
        }
        restored.whitelist?.let { whitelist ->
            whitelistAuthoritative = true
            _whitelistBindings.value = _whitelistBindings.value.copy(whitelist = whitelist)
        }
        _panicUntilEpochMs.value = restored.panicUntilEpochMs
    }

    private fun commitSettingsChange(clearActiveProfile: Boolean = true) {
        if (clearActiveProfile) {
            _activeSettingsProfileId.value = null
        }
        persistSettings()
    }

    private fun persistSettings() {
        val store = SettingsProfileStore(
            settings = currentSettingsState(),
            profiles = _settingsProfiles.value,
            activeProfileId = _activeSettingsProfileId.value
        )
        _settingsState.value = store.settings
        if (!::context.isInitialized) return
        val payload = SettingsProfileSerializer.storeToJson(store)
        settingsStateWriter.submit(payload)
    }

    private fun loadPersistedSettings() {
        if (!::context.isInitialized) return
        val file = java.io.File(context.filesDir, "echidna_settings.json")
        if (!file.exists()) {
            refreshSettingsState()
            return
        }
        val store = runCatching {
            if (file.length() > MAX_APP_STATE_BYTES) return@runCatching null
            SettingsProfileSerializer.storeFromJson(readAtomicUtf8(file))
        }.getOrNull() ?: run {
            refreshSettingsState()
            return
        }
        _settingsProfiles.value = store.profiles
        _activeSettingsProfileId.value = restoredActiveSettingsProfileId(store)
        applySettingsState(store.settings, pushSideEffects = false)
    }

    internal fun restoredActiveSettingsProfileId(store: SettingsProfileStore): String? =
        store.activeProfileId
            ?.takeIf { store.settings.restoreLastProfile }
            ?.takeIf { id -> store.profiles.any { it.id == id } }

    internal suspend fun awaitPersistenceForTest() {
        withContext(Dispatchers.IO) {
            check(presetStateWriter.awaitIdle()) { "preset persistence did not become idle" }
            check(settingsStateWriter.awaitIdle()) { "settings persistence did not become idle" }
        }
    }

    private fun refreshSettingsState() {
        _settingsState.value = currentSettingsState()
    }

    private fun currentSettingsState(): SettingsState =
        SettingsState(
            startWithSystem = _startWithSystem.value,
            autoStartEngine = _autoStartEngine.value,
            restoreLastProfile = _restoreLastProfile.value,
            engineMode = _dspEngineMode.value,
            latencyMode = _latencyMode.value,
            sidetoneEnabled = _sidetoneEnabled.value,
            sidetoneLevelDb = _sidetoneLevel.value,
            debugMode = _debugMode.value,
            telemetryOptIn = _telemetryOptIn.value,
            verboseLogging = _verboseLogging.value,
            failClosed = _failClosed.value,
            autoBypassOnError = _autoBypassOnError.value,
            panicHoldMinutes = _panicHoldMinutes.value,
            persistentNotification = _notificationEnabled.value,
            quickControlsEnabled = _quickControlsEnabled.value,
            widgetControlsEnabled = _widgetControlsEnabled.value,
            showInstallAlerts = _showInstallAlerts.value,
            showBridgeAlerts = _showBridgeAlerts.value,
            showHardwareAlerts = _showHardwareAlerts.value,
            showInstallMixupAlerts = _showInstallMixupAlerts.value,
            alertLatencyThresholdMs = _alertLatencyThresholdMs.value,
            alertXrunThreshold = _alertXrunThreshold.value,
            remindCompatibilityProbe = _remindCompatibilityProbe.value,
            masterEnabled = _masterEnabled.value,
            bypass = _bypass.value,
            defaultPresetId = _defaultPresetId.value
        )

    private fun applySettingsState(settings: SettingsState, pushSideEffects: Boolean) {
        _startWithSystem.value = settings.startWithSystem
        _autoStartEngine.value = settings.autoStartEngine
        _restoreLastProfile.value = settings.restoreLastProfile
        _dspEngineMode.value = settings.engineMode
        _latencyMode.value = settings.latencyMode
        _sidetoneEnabled.value = settings.sidetoneEnabled
        _sidetoneLevel.value = settings.sidetoneLevelDb.coerceIn(-60f, -6f)
        _debugMode.value = settings.debugMode
        _telemetryOptIn.value = settings.telemetryOptIn
        _verboseLogging.value = settings.verboseLogging
        _failClosed.value = settings.failClosed
        _autoBypassOnError.value = settings.autoBypassOnError
        _panicHoldMinutes.value = settings.panicHoldMinutes.coerceIn(1, 60)
        _notificationEnabled.value = settings.persistentNotification
        _quickControlsEnabled.value = settings.quickControlsEnabled
        _widgetControlsEnabled.value = settings.widgetControlsEnabled
        _showInstallAlerts.value = settings.showInstallAlerts
        _showBridgeAlerts.value = settings.showBridgeAlerts
        _showHardwareAlerts.value = settings.showHardwareAlerts
        _showInstallMixupAlerts.value = settings.showInstallMixupAlerts
        _alertLatencyThresholdMs.value = settings.alertLatencyThresholdMs.coerceIn(5, 250)
        _alertXrunThreshold.value = settings.alertXrunThreshold.coerceIn(1, 100)
        _remindCompatibilityProbe.value = settings.remindCompatibilityProbe
        _masterEnabled.value = settings.masterEnabled
        _bypass.value = settings.bypass
        settings.defaultPresetId
            ?.takeIf { id -> _presets.value.any { it.id == id } }
            ?.let { _defaultPresetId.value = it }
        _engineStatus.value = _engineStatus.value.copy(
            active = engineActive(),
            bypass = _bypass.value,
            latencyMs = _latencyMode.value.targetMs
        )
        refreshSettingsState()
        if (!pushSideEffects) return
        if (::context.isInitialized) {
            if (_notificationEnabled.value) {
                NotificationController.updateNotification(context)
            } else {
                NotificationController.cancel(context)
            }
            EchidnaWidgetProvider.updateAll(context)
        }
        synchronizeServiceState()
    }

    /** Builds the wizard result from the real module/SELinux/HAL probe (schema §3). */
    private fun buildCompatibilityFromStatus(status: ModuleStatus): CompatibilityResult {
        val stack = status.audioStack
        val cpu = status.cpu
        val probes = listOf(
            AudioStackProbe(
                name = "CPU architecture / Zygisk ABI",
                supported = cpu.nativeHooksSupported,
                latencyEstimateMs = null,
                message = buildString {
                    append(cpu.message.ifBlank { "CPU architecture probe unavailable." })
                    if (cpu.supportedAbis.isNotEmpty()) {
                        append(" Supported ABIs: ${cpu.supportedAbis.joinToString()}.")
                    }
                }
            ),
            AudioStackProbe(
                name = "AAudio (native low-latency)",
                supported = stack.aaudioSupported,
                latencyEstimateMs = null,
                message = if (stack.aaudioSupported) "Native low-latency path available"
                          else "Not reported by this device"
            ),
            AudioStackProbe(
                name = "OpenSL ES library",
                supported = stack.openSlEsAvailable,
                latencyEstimateMs = null,
                message = if (stack.openSlEsAvailable) {
                    "libOpenSLES.so is present; live hook coverage still requires a scoped app."
                } else {
                    "libOpenSLES.so was not found in common system/vendor library paths."
                }
            ),
            AudioStackProbe(
                name = "AudioFlinger client library",
                supported = stack.audioFlingerClientAvailable,
                latencyEstimateMs = null,
                message = if (stack.audioFlingerClientAvailable) {
                    "libaudioclient.so is present for AudioFlinger client-path probing."
                } else {
                    "libaudioclient.so was not found in common system/vendor library paths."
                }
            ),
            AudioStackProbe(
                name = "tinyalsa library",
                supported = stack.tinyAlsaAvailable,
                latencyEstimateMs = null,
                message = if (stack.tinyAlsaAvailable) {
                    "libtinyalsa.so is present; HAL/tinyalsa hooks remain device-gated."
                } else {
                    "libtinyalsa.so was not found in common system/vendor library paths."
                }
            ),
            AudioStackProbe(
                name = "Low-latency audio",
                supported = stack.lowLatency,
                latencyEstimateMs = null,
                message = if (stack.lowLatency) "FEATURE_AUDIO_LOW_LATENCY present"
                          else "Low-latency feature absent"
            ),
            AudioStackProbe(
                name = "Pro audio",
                supported = stack.proAudio,
                latencyEstimateMs = null,
                message = if (stack.proAudio) "FEATURE_AUDIO_PRO present"
                          else "Pro-audio feature absent"
            )
        )
        val notes = buildList {
            add(
                "CPU ABI: ${cpu.primaryAbi.ifBlank { "Unknown" }}; Zygisk ABI: " +
                    cpu.zygiskAbi.ifBlank { "Unknown" }
            )
            add("CPU hook support: ${cpu.supportLevel.ifBlank { "unknown" }}")
            add("Vendor HAL: ${stack.hal.ifBlank { "Unknown" }}")
            add("Vendor family: ${stack.vendorFamily.ifBlank { "Unknown" }}")
            if (stack.sampleRate > 0) add("Output sample rate: ${stack.sampleRate} Hz")
            if (stack.framesPerBuffer > 0) add("Frames per buffer: ${stack.framesPerBuffer}")
            add("Magisk module: ${if (status.magiskModuleInstalled) "installed" else "not installed"}")
            add("Zygisk: ${if (status.zygiskEnabled) "enabled" else "disabled"}")
            add(
                "Policy tool: " +
                    if (status.policyToolAvailable) "available (application unverified)" else "unverified",
            )
            add(
                "Native capture route: " +
                    if (status.nativeRouteVerified) "verified" else "unverified",
            )
            if (status.javaFallbackRecommended) add("LSPosed compatibility path recommended")
            status.notes?.let { add(it) }
            status.lastError?.let { add("Last error: $it") }
        }
        return CompatibilityResult(status.selinuxStatus, probes, notes)
    }

    /** Honest placeholder when the control service is unbound — no fabricated probe data. */
    private fun unavailableCompatibilityResult(): CompatibilityResult = CompatibilityResult(
        selinuxStatus = "Unknown (control service unavailable)",
        audioStack = listOf(
            AudioStackProbe(
                name = "Control service",
                supported = false,
                latencyEstimateMs = null,
                message = "Not bound — SELinux/HAL probes unavailable"
            )
        ),
        notes = listOf("Bind the Echidna control service to run SELinux and HAL probes.")
    )

    private fun applyModuleStatus(status: ModuleStatus) {
        _moduleStatus.value = status
        val nativeInstalled = status.magiskModuleInstalled || status.zygiskEnabled
        _engineStatus.value = _engineStatus.value.copy(
            nativeInstalled = nativeInstalled,
            selinuxMode = status.selinuxStatus,
            lastError = status.lastError,
            // Re-derive active against the fresh install signal (a module that just dropped out
            // must not read Active); still gated on master/bypass.
            active = engineActive(nativeInstalled, status.nativeRouteVerified)
        )
    }

    private fun applyControlState(state: ControlState) {
        val panicChanged = _panicUntilEpochMs.value != state.panicUntilEpochMs
        _masterEnabled.value = state.masterEnabled
        _bypass.value = state.bypass
        _panicUntilEpochMs.value = state.panicUntilEpochMs
        _sidetoneEnabled.value = state.sidetoneEnabled
        _dspEngineMode.value = state.engineMode
        // Only adopt a persisted gain that fits the slider range; ignore the 0.0 default.
        if (state.sidetoneGainDb in -60f..-6f) {
            _sidetoneLevel.value = state.sidetoneGainDb
        }
        _engineStatus.value = _engineStatus.value.copy(active = engineActive())
        if (panicChanged) {
            persistPresets()
        }
        refreshSettingsState()
    }

    private suspend fun refreshLegacyPreprocessorState(showLoading: Boolean) {
        if (!::serviceClient.isInitialized || !serviceClient.isBound()) return
        val current = _legacyPreprocessorState.value
        // A periodic read must not supersede an in-flight write and hide its confirmed result.
        if (current.updating) return
        val operation = legacyPreprocessorOperation.incrementAndGet()
        if (showLoading && !current.loaded) {
            _legacyPreprocessorState.value = current.copy(
                available = false,
                updating = true,
                error = null,
            )
        }
        val result = withContext(Dispatchers.IO) {
            serviceClient.readLegacyPreprocessorEnabled()
        }
        applyLegacyPreprocessorResult(operation, result)
    }

    private fun applyLegacyPreprocessorResult(
        operation: Long,
        result: LegacyPreprocessorServiceResult,
    ) {
        if (legacyPreprocessorOperation.get() != operation) return
        val previous = _legacyPreprocessorState.value
        _legacyPreprocessorState.value = when (result) {
            is LegacyPreprocessorServiceResult.Success -> LegacyPreprocessorControlState(
                enabled = result.enabled,
                loaded = true,
                available = true,
            )
            is LegacyPreprocessorServiceResult.Failure -> previous.copy(
                enabled = result.confirmedEnabled ?: previous.enabled,
                loaded = result.confirmedEnabled != null || previous.loaded,
                available = serviceClient.isBound(),
                updating = false,
                error = result.message,
            )
        }
    }

    private fun markLegacyPreprocessorDisconnected() {
        legacyPreprocessorOperation.incrementAndGet()
        val previous = _legacyPreprocessorState.value
        _legacyPreprocessorState.value = previous.copy(
            available = false,
            updating = false,
            error = if (previous.loaded) {
                "Control service disconnected. The switch shows the last confirmed value."
            } else {
                null
            },
        )
    }

    /**
     * The engine counts as genuinely "active" only when the native engine is installed/running
     * AND the user's master switch is on, bypass is off, and no timed panic gate is active.
     * Telemetry polls must never force this
     * true — otherwise turning Master off (or enabling Bypass) is silently overwritten on the next
     * ~2s telemetry poll and the status card snaps back to Active (t5-e7). Reads the live master
     * and bypass flags, so callers must update those first.
     */
    private fun engineActive(
        nativeInstalled: Boolean = _engineStatus.value.nativeInstalled,
        routeVerified: Boolean = _moduleStatus.value?.nativeRouteVerified == true,
    ): Boolean =
        nativeInstalled &&
            routeVerified &&
            _masterEnabled.value &&
            !_bypass.value &&
            (_panicUntilEpochMs.value <= 0L || System.currentTimeMillis() >= _panicUntilEpochMs.value)

    /** Reads the persisted whitelist + app bindings back from the service (schema §2). */
    suspend fun fetchWhitelistBindings(): WhitelistBindings = withContext(Dispatchers.IO) {
        if (!::serviceClient.isInitialized) return@withContext WhitelistBindings(emptyMap(), emptyMap())
        val json = serviceClient.getWhitelistBindings()
            ?: return@withContext WhitelistBindings(emptyMap(), emptyMap())
        val bindings = TelemetryParser.parseWhitelistBindings(json) ?: WhitelistBindings(
            emptyMap(),
            emptyMap()
        )
        val validIds = _presets.value.mapTo(mutableSetOf(), Preset::id)
        val bindingsWereAuthoritative = appBindingsAuthoritative
        val whitelistWasAuthoritative = whitelistAuthoritative
        val resolvedBindings = if (bindingsWereAuthoritative) {
            _whitelistBindings.value.appBindings
        } else {
            appBindingsAuthoritative = true
            bindings.appBindings.filterValues(validIds::contains)
        }
        val resolvedWhitelist = if (whitelistWasAuthoritative) {
            _whitelistBindings.value.whitelist
        } else {
            whitelistAuthoritative = true
            bindings.whitelist
        }
        val resolved = WhitelistBindings(resolvedWhitelist, resolvedBindings)
        _whitelistBindings.value = resolved
        if (!bindingsWereAuthoritative || !whitelistWasAuthoritative) {
            persistPresets()
        }
        if (
            !bindingsWereAuthoritative ||
            !whitelistWasAuthoritative ||
            bindings.appBindings != resolvedBindings ||
            bindings.whitelist != resolvedWhitelist
        ) {
            synchronizeServiceState()
        }
        resolved
    }

    /** Enumerates user-launchable packages so the whitelist editor need not hand-type them. */
    suspend fun installedLaunchablePackages(): List<String> = withContext(Dispatchers.IO) {
        if (!::context.isInitialized) return@withContext emptyList()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != context.packageName }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        _telemetryOptIn.value = enabled
        commitSettingsChange()
        synchronizeServiceState()
    }

    fun exportTelemetry(includeTrends: Boolean = true): String? {
        return if (::serviceClient.isInitialized) serviceClient.exportTelemetry(includeTrends) else null
    }

    fun exportDiagnostics(includeTrends: Boolean = true): String? {
        return if (::serviceClient.isInitialized) {
            serviceClient.exportDiagnostics(includeTrends)
        } else {
            null
        }
    }

    private fun applyTelemetry(snapshot: TelemetrySnapshot) {
        _telemetry.value = snapshot
        _metrics.value = DspMetrics(
            inputRms = snapshot.inputRms,
            inputPeak = snapshot.inputPeak,
            outputRms = snapshot.outputRms,
            outputPeak = snapshot.outputPeak,
            cpuLoadPercent = snapshot.averageCpuPercent,
            endToEndLatencyMs = snapshot.averageLatencyMs,
            xruns = snapshot.xruns
        )
        _engineStatus.value = _engineStatus.value.copy(
            latencyMs = snapshot.averageLatencyMs.roundToInt(),
            xruns = snapshot.xruns,
            lastError = null,
            // Do NOT force active=true here: telemetry keeps flowing regardless of the control
            // state, so gate on the real install signal + master/bypass instead (t5-e7).
            active = engineActive()
        )
        _latencyHistogram.value = buildLatencyHistogram(snapshot.samples)
        _cpuHeatmap.value = buildCpuHeatmap(snapshot.samples)
        _tunerState.value = buildTunerState(snapshot)
        _formantState.value = FormantState(snapshot.formantShiftCents, snapshot.formantWidth)
    }

    private fun buildLatencyHistogram(samples: List<TelemetrySample>): List<LatencyBucket> {
        if (samples.isEmpty()) return emptyList()
        val thresholds = floatArrayOf(5f, 10f, 20f, 40f)
        val counts = IntArray(thresholds.size + 1)
        samples.forEach { sample ->
            val durationMs = sample.durationUs / 1000f
            var placed = false
            for (index in thresholds.indices) {
                if (durationMs <= thresholds[index]) {
                    counts[index] += 1
                    placed = true
                    break
                }
            }
            if (!placed) {
                counts[counts.lastIndex] += 1
            }
        }
        val labels = listOf("≤5 ms", "≤10 ms", "≤20 ms", "≤40 ms", ">40 ms")
        return counts.mapIndexed { index, value -> LatencyBucket(labels[index], value) }
    }

    private fun buildCpuHeatmap(samples: List<TelemetrySample>): List<CpuHeatPoint> {
        if (samples.isEmpty()) return emptyList()
        val window = samples.takeLast(64)
        return window.mapIndexed { index, sample ->
            val percent = if (sample.durationUs <= 0) 0f else sample.cpuUs.toFloat() / sample.durationUs.toFloat() * 100f
            CpuHeatPoint(index, percent.coerceIn(0f, 100f))
        }
    }

    private fun buildTunerState(snapshot: TelemetrySnapshot): TunerState {
        val detected = snapshot.detectedPitchHz
        val target = if (snapshot.targetPitchHz > 0f) snapshot.targetPitchHz
        else if (detected > 0f) NoteUtils.midiTargetFrequency(detected) else 0f
        val note = NoteUtils.frequencyToNoteName(if (target > 0f) target else detected)
        val cents = NoteUtils.centsOff(if (detected > 0f) detected else target, target)
        val confidence = when {
            detected <= 0f -> 0f
            kotlin.math.abs(cents) <= 5f -> 1f
            kotlin.math.abs(cents) <= 20f -> 0.6f
            else -> 0.3f
        }
        return TunerState(note, cents, detected, target, confidence)
    }

    private fun updatePresetWarnings(preset: Preset) {
        _presetWarnings.value = PresetValidator.evaluate(preset)
    }

    private fun pushPresetToService() {
        if (!::serviceClient.isInitialized) return
        synchronizeServiceState()
    }

    /**
     * Queues one coherent app-owned state value. The client retains only the newest value until
     * the asynchronous bind succeeds, then replays it once after each service reconnection.
     */
    private fun synchronizeServiceState() {
        if (!::serviceClient.isInitialized) return
        val policyStateJson = if (appBindingsAuthoritative && whitelistAuthoritative) {
            val captureOwner = if (_dspEngineMode.value == DspEngineMode.COMPATIBILITY) {
                "lsposed"
            } else {
                "zygisk"
            }
            val captureOwners = _whitelistBindings.value.whitelist
                .filterValues { enabled -> enabled }
                .keys
                .associateWith { captureOwner }
            runCatching {
                ProfileBindingSyncCodec.encode(
                    presets = _presets.value,
                    defaultProfileId = _defaultPresetId.value,
                    appBindings = _whitelistBindings.value.appBindings,
                    whitelist = _whitelistBindings.value.whitelist,
                    captureOwners = captureOwners,
                    control = PolicyControlState(
                        masterEnabled = _masterEnabled.value,
                        bypass = _bypass.value,
                        panicUntilEpochMs = _panicUntilEpochMs.value,
                        sidetoneEnabled = _sidetoneEnabled.value,
                        sidetoneGainDb = _sidetoneLevel.value,
                        engineMode = _dspEngineMode.value.id,
                    ),
                )
            }.getOrElse { exception ->
                _engineStatus.value = _engineStatus.value.copy(
                    active = false,
                    lastError = "Policy rejected: ${exception.message ?: "invalid state"}",
                )
                return
            }
        } else {
            null
        }
        serviceClient.synchronize(
            ControlServiceSyncSnapshot(
                policyStateJson = policyStateJson,
                telemetryOptIn = _telemetryOptIn.value,
            )
        )
    }

    private fun defaultPresets(): List<Preset> = listOf(
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Natural Mask",
            description = "Balanced mask with light pitch and formant shifts",
            tags = setOf("NAT", "LL"),
            latencyMode = LatencyMode.LOW_LATENCY,
            dryWet = 60,
            modules = listOf(
                EffectModule.Gate(true, -50f, 5f, 120f, 3f),
                EffectModule.Equalizer(
                    enabled = true,
                    bands = listOf(
                        band(120f, 2f, 0.7f),
                        band(3500f, -2f, 2.0f)
                    ),
                    bandCount = 5
                ),
                EffectModule.Compressor(true, EffectModule.CompressorMode.MANUAL, -26f, 3.5f, 6f, 5f, 120f, 4f),
                EffectModule.Pitch(true, 2.5f, 0f, EffectModule.PitchQuality.LL, preserveFormants = true),
                EffectModule.Formant(true, -180f, intelligibilityAssist = true),
                EffectModule.Reverb(true, 8f, 20f, 5f, 8f),
                EffectModule.Mix(true, 70f, 0f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Darth Vader",
            description = "Low pitch + dark EQ",
            tags = setOf("FX", "LL"),
            latencyMode = LatencyMode.LOW_LATENCY,
            dryWet = 75,
            modules = listOf(
                EffectModule.Gate(true, -45f, 5f, 80f, 3f),
                EffectModule.Equalizer(
                    enabled = true,
                    bands = listOf(
                        band(3500f, -12f, 0.7f)
                    ),
                    bandCount = 5
                ),
                EffectModule.Compressor(true, EffectModule.CompressorMode.MANUAL, -30f, 3f, 6f, 8f, 160f, 4f),
                EffectModule.Pitch(true, -7f, 0f, EffectModule.PitchQuality.LL, preserveFormants = false),
                EffectModule.Formant(true, -250f, intelligibilityAssist = true),
                EffectModule.Reverb(true, 10f, 20f, 6f, 10f),
                EffectModule.Mix(true, 80f, 0f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Studio Warm",
            description = "Gentle studio sheen",
            tags = setOf("HQ", "NAT"),
            latencyMode = LatencyMode.HIGH_QUALITY,
            dryWet = 55,
            modules = listOf(
                EffectModule.Gate(true, -48f, 8f, 150f, 3f),
                EffectModule.Equalizer(
                    enabled = true,
                    bands = listOf(
                        band(120f, 2f, 1.0f),
                        band(8000f, -1.5f, 1.2f)
                    ),
                    bandCount = 5
                ),
                EffectModule.Compressor(true, EffectModule.CompressorMode.MANUAL, -24f, 2f, 6f, 10f, 220f, 3f),
                EffectModule.Reverb(true, 12f, 18f, 10f, 12f),
                EffectModule.Mix(true, 50f, 0f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Helium",
            description = "Bright, high-pitched character",
            tags = setOf("FX", "LL"),
            latencyMode = LatencyMode.LOW_LATENCY,
            dryWet = 70,
            modules = listOf(
                EffectModule.Gate(true, -55f, 4f, 80f, 2f),
                EffectModule.Equalizer(
                    enabled = true,
                    bands = listOf(
                        band(160f, -6f, 1.0f),
                        band(3000f, 2f, 1.2f)
                    ),
                    bandCount = 5
                ),
                EffectModule.Pitch(true, 6f, 0f, EffectModule.PitchQuality.LL, preserveFormants = false),
                EffectModule.Formant(true, 200f, intelligibilityAssist = true),
                EffectModule.Mix(true, 80f, 0f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Radio Comms",
            description = "Narrow-band radio voice",
            tags = setOf("NAT", "LL"),
            latencyMode = LatencyMode.LOW_LATENCY,
            dryWet = 60,
            modules = listOf(
                EffectModule.Gate(true, -52f, 5f, 100f, 4f),
                EffectModule.Equalizer(
                    enabled = true,
                    bands = listOf(
                        band(300f, -3f, 1.5f),
                        band(3400f, -6f, 1.5f)
                    ),
                    bandCount = 5
                ),
                EffectModule.Compressor(true, EffectModule.CompressorMode.MANUAL, -28f, 4f, 6f, 5f, 150f, 6f),
                EffectModule.Mix(true, 65f, -2f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Robotizer",
            description = "Tight correction and synthetic tone",
            tags = setOf("FX", "HQ"),
            latencyMode = LatencyMode.HIGH_QUALITY,
            dryWet = 80,
            modules = listOf(
                EffectModule.AutoTune(true, MusicalKey.C, MusicalScale.CHROMATIC, 15f, 20f, 0f, false, 80f),
                EffectModule.Formant(true, 0f, intelligibilityAssist = false),
                EffectModule.Reverb(true, 18f, 25f, 8f, 18f),
                EffectModule.Mix(true, 80f, 0f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Cher-Tune",
            description = "Signature fast retune effect",
            tags = setOf("FX", "HQ"),
            latencyMode = LatencyMode.HIGH_QUALITY,
            dryWet = 90,
            modules = listOf(
                EffectModule.AutoTune(true, MusicalKey.C, MusicalScale.MAJOR, 3f, 5f, 0f, true, 100f),
                EffectModule.Mix(true, 90f, 0f)
            )
        ),
        Preset(
            id = UUID.randomUUID().toString(),
            name = "Anonymous",
            description = "Low, masked identity",
            tags = setOf("NAT", "LL"),
            latencyMode = LatencyMode.LOW_LATENCY,
            dryWet = 65,
            modules = listOf(
                EffectModule.Gate(true, -48f, 6f, 120f, 3f),
                EffectModule.Pitch(true, -2f, 0f, EffectModule.PitchQuality.LL, preserveFormants = false),
                EffectModule.Formant(true, -150f, intelligibilityAssist = true),
                EffectModule.Equalizer(
                    enabled = true,
                    bands = listOf(
                        band(6000f, -3f, 2.0f)
                    ),
                    bandCount = 5
                ),
                EffectModule.Mix(true, 60f, 0f)
            )
        )
    )

    private fun defaultEmptyPreset(): Preset = Preset(
        id = UUID.randomUUID().toString(),
        name = "New Preset",
        description = null,
        tags = emptySet(),
        latencyMode = LatencyMode.BALANCED,
        dryWet = 50,
        modules = listOf(
            EffectModule.Gate(false, -60f, 5f, 120f, 3f),
            EffectModule.Equalizer(false, bands = emptyList(), bandCount = 5),
            EffectModule.Compressor(false, EffectModule.CompressorMode.MANUAL, -30f, 3f, 6f, 8f, 160f, 0f),
            EffectModule.Pitch(false, 0f, 0f, EffectModule.PitchQuality.LL, preserveFormants = true),
            EffectModule.Formant(false, 0f, intelligibilityAssist = false),
            EffectModule.AutoTune(false, MusicalKey.C, MusicalScale.MAJOR, 120f, 50f, 30f, true, 50f),
            EffectModule.Reverb(false, 10f, 10f, 0f, 5f),
            EffectModule.Mix(true, 50f, 0f)
        )
    )

    private fun defaultTelemetry(): TelemetrySnapshot = TelemetrySnapshot(
        totalCallbacks = 0,
        averageLatencyMs = 0f,
        averageCpuPercent = 0f,
        inputRms = -120f,
        outputRms = -120f,
        inputPeak = -120f,
        outputPeak = -120f,
        detectedPitchHz = 0f,
        targetPitchHz = 0f,
        formantShiftCents = 0f,
        formantWidth = 0f,
        xruns = 0,
        warnings = emptyList(),
        samples = emptyList(),
        hooks = emptyList()
    )

    private fun band(freq: Float, gain: Float, q: Float) = com.echidna.app.model.Band(freq, gain, q)

    private fun cloneModule(module: EffectModule): EffectModule = when (module) {
        is EffectModule.Gate -> module.copy()
        is EffectModule.Equalizer -> module.copy(bands = module.bands.map { it.copy() })
        is EffectModule.Compressor -> module.copy()
        is EffectModule.Pitch -> module.copy()
        is EffectModule.Formant -> module.copy()
        is EffectModule.AutoTune -> module.copy()
        is EffectModule.Reverb -> module.copy()
        is EffectModule.Mix -> module.copy()
    }
}
