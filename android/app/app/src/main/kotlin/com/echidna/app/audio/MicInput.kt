package com.echidna.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture for the Lab, in two modes over a mono 48 kHz float [AudioRecord]:
 *
 *  - [startMeter]: continuous live INPUT level (dBFS) with no storage — powers the
 *    "mic check" meter so the user can see the mic working.
 *  - [startRecording]: same live level PLUS accumulation into a bounded PCM buffer for
 *    "record -> listen back / process".
 *
 * Honest about the emulator caveat: emulators often expose a silent or synthetic mic,
 * so callers surface the measured level rather than claiming a capture succeeded.
 *
 * The caller MUST hold RECORD_AUDIO before calling; construction of [AudioRecord] is
 * deferred to start so an un-permissioned Lab screen allocates no recorder.
 */
class MicInput(
    private val sampleRate: Int = LabAudioFormat.SAMPLE_RATE,
    private val maxSeconds: Int = LabAudioFormat.MAX_RECORD_SECONDS
) {
    fun interface LevelListener {
        fun onLevel(dbfs: Float)
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    /** Continuously reports input level until [stop]. Never stores audio. */
    fun startMeter(level: LevelListener, onError: (String) -> Unit) {
        launch(store = false, level = level, onDone = {}, onError = onError)
    }

    /**
     * Records up to [maxSeconds] while reporting live level, then delivers the captured
     * mono float PCM via [onDone]. [onDone] also fires if [stop] is called early.
     */
    fun startRecording(
        level: LevelListener,
        onDone: (FloatArray) -> Unit,
        onError: (String) -> Unit
    ) {
        launch(store = true, level = level, onDone = onDone, onError = onError)
    }

    @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO before invoking.
    private fun launch(
        store: Boolean,
        level: LevelListener,
        onDone: (FloatArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running.getAndSet(true)) return
        thread = Thread {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                if (minBuf <= 0) {
                    onError("Microphone not available on this device")
                    return@Thread
                }
                // Double the min buffer for headroom against read stalls.
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    minBuf * 2
                )
                record = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    onError("Could not initialize the microphone")
                    return@Thread
                }

                val block = FloatArray(1_024)
                val capacity = sampleRate * maxSeconds
                val stored = if (store) FloatArray(capacity) else FloatArray(0)
                var storedCount = 0

                recorder.startRecording()
                while (running.get()) {
                    val read = recorder.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    level.onLevel(AudioAnalysis.rmsDbfs(block, read))
                    if (store) {
                        val room = capacity - storedCount
                        if (room <= 0) break
                        val n = minOf(room, read)
                        System.arraycopy(block, 0, stored, storedCount, n)
                        storedCount += n
                        if (storedCount >= capacity) break
                    }
                }
                recorder.stop()
                if (store) onDone(stored.copyOf(storedCount))
            } catch (error: Exception) {
                Log.w(TAG, "Mic capture failed", error)
                onError(error.message ?: "Microphone capture failed")
            } finally {
                running.set(false)
                runCatching { record?.release() }
            }
        }.also { it.isDaemon = true; it.name = "LabMicInput"; it.start() }
    }

    /** Stops capture/metering; a recording in progress is delivered with what was captured. */
    fun stop() {
        running.set(false)
        thread?.let { runCatching { it.join(500) } }
        thread = null
    }

    private companion object {
        const val TAG = "LabMicInput"
    }
}
