package com.echidna.app.data

import android.content.Context
import com.echidna.app.model.AudioStackProbe
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.CpuHeatPoint
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.EffectModule
import com.echidna.app.model.FormantState
import com.echidna.app.model.LatencyBucket
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import com.echidna.app.model.PresetValidator
import com.echidna.app.model.PresetWarning
import com.echidna.app.model.TelemetrySample
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TunerState
import com.echidna.app.system.ControlServiceClient
import com.echidna.app.system.EchidnaWidgetProvider
import com.echidna.app.system.NotificationController
import com.echidna.app.ui.diagnostics.NoteUtils
import java.util.UUID
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

object ControlStateRepository {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var serviceClient: ControlServiceClient

    private val _masterEnabled = MutableStateFlow(true)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private val _sidetoneLevel = MutableStateFlow(-24f)
    val sidetoneLevel: StateFlow<Float> = _sidetoneLevel.asStateFlow()

    private val _latencyMode = MutableStateFlow(LatencyMode.LOW_LATENCY)
    val latencyMode: StateFlow<LatencyMode> = _latencyMode.asStateFlow()

    private val _engineStatus = MutableStateFlow(
        EngineStatus(nativeInstalled = true, active = true, selinuxMode = "Enforcing", latencyMs = 0)
    )
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

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
        list.first { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, _presets.value.first())

    private val _compatibilityState = MutableStateFlow<CompatibilityResult?>(null)
    val compatibilityState: StateFlow<CompatibilityResult?> = _compatibilityState.asStateFlow()

    private val _defaultPresetId = MutableStateFlow(_presets.value.first().id)
    val defaultPresetId: StateFlow<String> = _defaultPresetId.asStateFlow()

    private val _notificationEnabled = MutableStateFlow(true)
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled.asStateFlow()

    private var observersStarted = false

    fun initialize(appContext: Context) {
        if (!::context.isInitialized) {
            context = appContext.applicationContext
            NotificationController.ensureChannel(context)
            NotificationController.updateNotification(context)
            serviceClient = ControlServiceClient(context)
            serviceClient.bind()
            scope.launch {
                serviceClient.telemetryUpdates.collect { payload ->
                    TelemetryParser.parse(payload)?.let { applyTelemetry(it) }
                }
            }
            scope.launch {
                while (true) {
                    delay(2000)
                    serviceClient.fetchSnapshot()?.let { snapshot ->
                        TelemetryParser.parse(snapshot)?.let { applyTelemetry(it) }
                    }
                    if (serviceClient.isBound()) {
                        _telemetryOptIn.value = serviceClient.isTelemetryOptedIn()
                    }
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
        }
        updatePresetWarnings(activePreset.value)
    }

    fun toggleMaster() {
        _masterEnabled.value = !_masterEnabled.value
    }

    fun setMasterEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
    }

    fun updateSidetone(levelDb: Float) {
        _sidetoneLevel.value = levelDb
    }

    fun setLatencyMode(mode: LatencyMode) {
        _latencyMode.value = mode
        _engineStatus.value = _engineStatus.value.copy(latencyMs = mode.targetMs)
    }

    fun selectPreset(presetId: String) {
        if (_presets.value.any { it.id == presetId }) {
            _activePresetId.value = presetId
            _presets.value.firstOrNull { it.id == presetId }?.let { updatePresetWarnings(it) }
        }
    }

    fun cyclePreset() {
        val list = _presets.value
        val idx = list.indexOfFirst { it.id == _activePresetId.value }
        if (idx >= 0) {
            val next = list[(idx + 1) % list.size]
            _activePresetId.value = next.id
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
        return preset.id
    }

    fun deletePreset(id: String) {
        val list = _presets.value
        if (list.size == 1) return
        val filtered = list.filterNot { it.id == id }
        if (filtered.size != list.size) {
            _presets.value = filtered
            if (_activePresetId.value == id) {
                _activePresetId.value = filtered.first().id
                updatePresetWarnings(filtered.first())
            }
            if (_defaultPresetId.value == id) {
                _defaultPresetId.value = filtered.first().id
            }
        }
    }

    fun updatePreset(preset: Preset) {
        _presets.value = _presets.value.map { if (it.id == preset.id) preset else it }
        if (_activePresetId.value == preset.id) {
            updatePresetWarnings(preset)
        }
    }

    fun renamePreset(id: String, name: String) {
        _presets.value = _presets.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun setDefaultPreset(id: String) {
        if (_presets.value.any { it.id == id }) {
            _defaultPresetId.value = id
        }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        _notificationEnabled.value = enabled
        if (enabled) {
            NotificationController.updateNotification(context)
        } else {
            NotificationController.cancel(context)
        }
    }

    fun runCompatibilityProbe() {
        scope.launch {
            _compatibilityState.value = null
            delay(600)
            _compatibilityState.value = CompatibilityResult(
                selinuxStatus = "Enforcing (ok)",
                audioStack = listOf(
                    AudioStackProbe("AAudio", supported = true, latencyEstimateMs = 14, message = "Native engine hooked"),
                    AudioStackProbe("OpenSL ES", supported = true, latencyEstimateMs = 20, message = "Fallback available"),
                    AudioStackProbe("AudioRecord (Java)", supported = true, latencyEstimateMs = 28, message = "LSPosed shim active")
                ),
                notes = listOf(
                    "Vendor HAL: Qualcomm QSSI",
                    "XRuns guard active",
                    "SELinux policy allows Magisk module injection"
                )
            )
        }
    }

    fun refreshEngineStatus(active: Boolean, error: String? = null) {
        _engineStatus.value = _engineStatus.value.copy(active = active, lastError = error)
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        if (::serviceClient.isInitialized) {
            serviceClient.setTelemetryOptIn(enabled)
            _telemetryOptIn.value = enabled
        }
    }

    fun exportTelemetry(includeTrends: Boolean = true): String? {
        return if (::serviceClient.isInitialized) serviceClient.exportTelemetry(includeTrends) else null
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
            active = true
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
