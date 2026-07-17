package com.echidna.app.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.audio.AudioAnalysis
import com.echidna.app.audio.AudioPlayer
import com.echidna.app.audio.DspProcessorFactory
import com.echidna.app.audio.EchidnaLabDsp
import com.echidna.app.audio.LabAudioFormat
import com.echidna.app.audio.LabDspEngine
import com.echidna.app.audio.MicInput
import com.echidna.app.audio.OfflineDsp
import com.echidna.app.audio.RealtimeMonitor
import com.echidna.app.audio.TestTone
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.Preset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which stage the mic is in (mutually exclusive uses of the single [AudioRecord]). */
enum class MicMode { IDLE, METERING, RECORDING }

/** Which buffer is currently auditioning. */
enum class PlaybackTarget { NONE, DRY, WET }

/** Realtime monitor lifecycle. */
enum class RealtimeState { OFF, RUNNING }

/** Compact waveform + level summary for a captured/processed buffer. */
data class WaveformData(
    val peaks: FloatArray,
    val peakDbfs: Float,
    val rmsDbfs: Float,
    val durationSeconds: Float
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is WaveformData &&
            peaks.contentEquals(other.peaks) && peakDbfs == other.peakDbfs &&
            rmsDbfs == other.rmsDbfs && durationSeconds == other.durationSeconds)

    override fun hashCode(): Int = peaks.contentHashCode()
}

data class LabUiState(
    val engineAvailable: Boolean = false,
    val apiVersion: String = "",
    val permissionGranted: Boolean = false,
    val micMode: MicMode = MicMode.IDLE,
    val inputLevelDbfs: Float = AudioAnalysis.SILENCE_DBFS,
    val dryLabel: String = "",
    val dryWaveform: WaveformData? = null,
    val presets: List<Preset> = emptyList(),
    val selectedPresetId: String? = null,
    val processing: Boolean = false,
    val wetWaveform: WaveformData? = null,
    val playback: PlaybackTarget = PlaybackTarget.NONE,
    val realtime: RealtimeState = RealtimeState.OFF,
    val headphonesConfirmed: Boolean = false,
    val realtimeInputDbfs: Float = AudioAnalysis.SILENCE_DBFS,
    val realtimeOutputDbfs: Float = AudioAnalysis.SILENCE_DBFS,
    val realtimeLatencyMs: Int = 0,
    val testToneKind: TestTone.Kind = TestTone.Kind.SINE_440,
    val message: String? = null
) {
    val hasDrySource: Boolean get() = dryWaveform != null
    val hasWet: Boolean get() = wetWaveform != null
    val selectedPreset: Preset? get() = presets.firstOrNull { it.id == selectedPresetId }
}

/**
 * Drives the Lab testbench: mic check, record -> listen, process + A/B, realtime, test tone.
 *
 * Honest by construction: every audible/visible result comes from either the device mic or a
 * generated signal run through the REAL native DSP engine ([dspFactory]); nothing is faked, and
 * when the engine is unavailable the UI says so. The DSP factory, preset source, and dispatcher
 * are injectable so the state machine and the transform are unit-testable without a device.
 */
class LabViewModel internal constructor(
    private val dspFactory: DspProcessorFactory,
    presetsOverride: List<Preset>?,
    private val ioDispatcher: CoroutineDispatcher,
    private val engineAvailable: () -> Boolean
) : ViewModel() {

    // Public no-arg constructor for androidx viewModel(): real engine + live preset catalog.
    constructor() : this(LabDspEngine, null, Dispatchers.Default, { EchidnaLabDsp.engineAvailable() })

    private val presetsSource: StateFlow<List<Preset>> =
        if (presetsOverride != null) MutableStateFlow(presetsOverride) else ControlStateRepository.presets

    private val _state = MutableStateFlow(
        LabUiState(
            engineAvailable = engineAvailable(),
            apiVersion = formatApiVersion(EchidnaLabDsp.apiVersion()),
            presets = presetsSource.value,
            selectedPresetId = presetsSource.value.firstOrNull()?.id,
            realtimeLatencyMs = RealtimeMonitor().estimatedLatencyMs()
        )
    )
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    private val mic = MicInput()
    private val player = AudioPlayer()
    private val realtimeMonitor = RealtimeMonitor()

    private var drySource: FloatArray? = null
    private var wetSource: FloatArray? = null
    private var realtimeEngine: LabDspEngine? = null

    init {
        // Keep the preset list live when backed by the repository.
        viewModelScope.launch {
            presetsSource.collect { presets ->
                _state.value = _state.value.copy(
                    presets = presets,
                    selectedPresetId = _state.value.selectedPresetId?.takeIf { id -> presets.any { it.id == id } }
                        ?: presets.firstOrNull()?.id
                )
            }
        }
    }

    // ---- Permission ---------------------------------------------------------

    fun onPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(
            permissionGranted = granted,
            message = if (granted) _state.value.message else "Microphone permission is required for the mic tests."
        )
        if (!granted) stopMic()
    }

    // ---- Mic check (live input meter) --------------------------------------

    fun startMeter() {
        if (!_state.value.permissionGranted || _state.value.micMode != MicMode.IDLE) return
        _state.value = _state.value.copy(micMode = MicMode.METERING, message = null)
        mic.startMeter(
            level = { dbfs -> _state.value = _state.value.copy(inputLevelDbfs = dbfs) },
            onError = ::onMicError
        )
    }

    fun stopMeter() {
        if (_state.value.micMode != MicMode.METERING) return
        mic.stop()
        _state.value = _state.value.copy(micMode = MicMode.IDLE, inputLevelDbfs = AudioAnalysis.SILENCE_DBFS)
    }

    // ---- Record -> listen back ---------------------------------------------

    fun startRecording() {
        if (!_state.value.permissionGranted || _state.value.micMode != MicMode.IDLE) return
        _state.value = _state.value.copy(micMode = MicMode.RECORDING, message = null)
        mic.startRecording(
            level = { dbfs -> _state.value = _state.value.copy(inputLevelDbfs = dbfs) },
            onDone = { pcm ->
                val label = "Recording (${"%.1f".format(pcm.size.toFloat() / LabAudioFormat.SAMPLE_RATE)} s)"
                setDrySource(pcm, label)
                _state.value = _state.value.copy(
                    micMode = MicMode.IDLE,
                    inputLevelDbfs = AudioAnalysis.SILENCE_DBFS,
                    message = if (pcm.isEmpty()) "No audio captured." else null
                )
            },
            onError = ::onMicError
        )
    }

    fun stopRecording() {
        if (_state.value.micMode != MicMode.RECORDING) return
        mic.stop()
    }

    // ---- Test tone ----------------------------------------------------------

    fun selectTestTone(kind: TestTone.Kind) {
        _state.value = _state.value.copy(testToneKind = kind)
    }

    fun generateTestTone() {
        val kind = _state.value.testToneKind
        val tone = TestTone.generate(kind, LabAudioFormat.SAMPLE_RATE)
        setDrySource(tone, "Test tone: ${kind.label}")
        _state.value = _state.value.copy(message = null)
    }

    // ---- Preset + processing ------------------------------------------------

    fun selectPreset(preset: Preset) {
        _state.value = _state.value.copy(selectedPresetId = preset.id)
    }

    /** Runs the current dry source through the selected preset on the REAL engine. */
    fun processWithPreset() {
        val source = drySource ?: return
        val preset = _state.value.selectedPreset ?: return
        if (_state.value.processing) return
        if (!engineAvailable()) {
            _state.value = _state.value.copy(message = "DSP engine unavailable (lite build). Rebuild with the native engine to hear transforms.")
            return
        }
        _state.value = _state.value.copy(processing = true, message = null)
        viewModelScope.launch {
            val wet = withContext(ioDispatcher) {
                val engine = dspFactory.create(
                    LabAudioFormat.SAMPLE_RATE, LabAudioFormat.CHANNELS, LabAudioFormat.PROCESS_CHUNK_FRAMES, preset
                )
                try {
                    if (!engine.available) null else OfflineDsp.process(engine, source)
                } finally {
                    engine.close()
                }
            }
            if (wet == null) {
                _state.value = _state.value.copy(processing = false, message = "Processing failed — engine unavailable.")
            } else {
                wetSource = wet
                _state.value = _state.value.copy(
                    processing = false,
                    wetWaveform = summarize(wet)
                )
            }
        }
    }

    // ---- Playback / A-B -----------------------------------------------------

    fun playDry() = play(PlaybackTarget.DRY, drySource)
    fun playWet() = play(PlaybackTarget.WET, wetSource)

    fun stopPlayback() {
        player.stop()
        _state.value = _state.value.copy(playback = PlaybackTarget.NONE)
    }

    private fun play(target: PlaybackTarget, samples: FloatArray?) {
        if (samples == null || samples.isEmpty()) return
        _state.value = _state.value.copy(playback = target)
        player.play(samples) {
            _state.value = _state.value.copy(playback = PlaybackTarget.NONE)
        }
    }

    // ---- Realtime monitor ---------------------------------------------------

    fun confirmHeadphones(confirmed: Boolean) {
        _state.value = _state.value.copy(headphonesConfirmed = confirmed)
        if (!confirmed) stopRealtime()
    }

    fun startRealtime() {
        val s = _state.value
        if (s.realtime != RealtimeState.OFF) return
        if (!s.permissionGranted) {
            _state.value = s.copy(message = "Microphone permission is required.")
            return
        }
        if (!s.headphonesConfirmed) {
            _state.value = s.copy(message = "Confirm you are using headphones before starting realtime monitoring.")
            return
        }
        val preset = s.selectedPreset
        if (preset == null || !engineAvailable()) {
            _state.value = s.copy(message = "DSP engine unavailable — cannot start realtime.")
            return
        }
        val engine = dspFactory.create(
            LabAudioFormat.SAMPLE_RATE, LabAudioFormat.CHANNELS, LabAudioFormat.REALTIME_BLOCK_FRAMES, preset
        ) as? LabDspEngine
        if (engine == null || !engine.available) {
            engine?.close()
            _state.value = s.copy(message = "Could not create the realtime engine.")
            return
        }
        realtimeEngine = engine
        val started = realtimeMonitor.start(
            processor = engine,
            level = { input, output ->
                _state.value = _state.value.copy(realtimeInputDbfs = input, realtimeOutputDbfs = output)
            },
            onError = { msg ->
                stopRealtime()
                _state.value = _state.value.copy(message = msg)
            }
        )
        if (started) {
            _state.value = s.copy(realtime = RealtimeState.RUNNING, message = null)
        } else {
            engine.close(); realtimeEngine = null
            _state.value = s.copy(message = "Could not start realtime monitoring.")
        }
    }

    fun stopRealtime() {
        realtimeMonitor.stop()
        realtimeEngine?.close()
        realtimeEngine = null
        if (_state.value.realtime != RealtimeState.OFF) {
            _state.value = _state.value.copy(
                realtime = RealtimeState.OFF,
                realtimeInputDbfs = AudioAnalysis.SILENCE_DBFS,
                realtimeOutputDbfs = AudioAnalysis.SILENCE_DBFS
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- Internals ----------------------------------------------------------

    private fun setDrySource(pcm: FloatArray, label: String) {
        drySource = pcm
        wetSource = null
        _state.value = _state.value.copy(
            dryLabel = label,
            dryWaveform = if (pcm.isEmpty()) null else summarize(pcm),
            wetWaveform = null
        )
    }

    private fun summarize(pcm: FloatArray): WaveformData = WaveformData(
        peaks = AudioAnalysis.waveform(pcm, WAVEFORM_BUCKETS),
        peakDbfs = AudioAnalysis.toDbfs(AudioAnalysis.peak(pcm)),
        rmsDbfs = AudioAnalysis.rmsDbfs(pcm),
        durationSeconds = pcm.size.toFloat() / LabAudioFormat.SAMPLE_RATE
    )

    private fun onMicError(message: String) {
        stopMic()
        _state.value = _state.value.copy(micMode = MicMode.IDLE, message = message)
    }

    private fun stopMic() {
        mic.stop()
        if (_state.value.micMode != MicMode.IDLE) {
            _state.value = _state.value.copy(micMode = MicMode.IDLE)
        }
    }

    override fun onCleared() {
        mic.stop()
        player.stop()
        stopRealtime()
    }

    private companion object {
        const val WAVEFORM_BUCKETS = 256

        fun formatApiVersion(packed: Long): String {
            if (packed <= 0L) return ""
            val major = (packed ushr 16) and 0xFFFF
            val minor = (packed ushr 8) and 0xFF
            val patch = packed and 0xFF
            return "$major.$minor.$patch"
        }
    }
}
