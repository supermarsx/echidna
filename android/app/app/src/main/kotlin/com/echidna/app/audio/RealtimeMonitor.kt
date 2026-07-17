package com.echidna.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live mic -> DSP -> output monitor for the Lab's realtime voice transform.
 *
 * Runs on a dedicated audio thread with small, PREALLOCATED direct buffers; the audio
 * loop performs no allocation and takes no locks (the native process call is lock-free
 * once the engine is created). Latency is capture buffer + one DSP block + output buffer.
 *
 * FEEDBACK SAFETY is the caller's responsibility to gate: this creates an uncontrolled
 * speaker->mic path if used without headphones. The Lab requires the user to confirm
 * headphones before starting and warns loudly. As a second layer, [outputGain] attenuates
 * output and defaults below unity.
 *
 * Caller MUST hold RECORD_AUDIO before [start].
 */
class RealtimeMonitor(
    private val sampleRate: Int = LabAudioFormat.SAMPLE_RATE,
    private val blockFrames: Int = LabAudioFormat.REALTIME_BLOCK_FRAMES
) {
    fun interface LevelListener {
        /** Reports input and output RMS level (dBFS) once per processed block. */
        fun onLevels(inputDbfs: Float, outputDbfs: Float)
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    var outputGain: Float = 0.7f

    val isRunning: Boolean get() = running.get()

    /** Estimated round-trip added latency in ms (capture + one block + output), for honest UI copy. */
    fun estimatedLatencyMs(): Int = ((blockFrames.toDouble() * 3.0 / sampleRate) * 1000.0).toInt()

    /**
     * Starts monitoring using [processor] (created with maxFrames >= [blockFrames]). Returns false
     * if the engine is unavailable; [onError] reports device/audio init failures.
     */
    fun start(processor: DspProcessor, level: LevelListener, onError: (String) -> Unit): Boolean {
        if (!processor.available) return false
        if (running.getAndSet(true)) return true
        thread = Thread { runLoop(processor, level, onError) }
            .also { it.isDaemon = true; it.name = "LabRealtimeMonitor"; it.start() }
        return true
    }

    @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO before invoking.
    private fun runLoop(processor: DspProcessor, level: LevelListener, onError: (String) -> Unit) {
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val recMin = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val trkMin = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (recMin <= 0 || trkMin <= 0) {
                onError("Realtime audio not available on this device")
                return
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(recMin, blockFrames * Float.SIZE_BYTES * 2)
            )
            record = recorder
            val player = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
                maxOf(trkMin, blockFrames * Float.SIZE_BYTES * 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track = player
            if (recorder.state != AudioRecord.STATE_INITIALIZED ||
                player.state != AudioTrack.STATE_INITIALIZED
            ) {
                onError("Could not initialize realtime audio")
                return
            }

            // Preallocated once — nothing below allocates in the loop.
            val buffers = DirectFloatBuffers(blockFrames)
            val scratchIn = FloatArray(blockFrames)
            val scratchOut = FloatArray(blockFrames)
            val bytes = blockFrames * Float.SIZE_BYTES

            recorder.startRecording()
            player.play()
            while (running.get()) {
                buffers.inputBytes.rewind()
                val readBytes = recorder.read(buffers.inputBytes, bytes, AudioRecord.READ_BLOCKING)
                if (readBytes <= 0) continue
                val frames = readBytes / Float.SIZE_BYTES

                buffers.inputBytes.rewind()
                buffers.outputBytes.rewind()
                val status = EchidnaLabDsp.nativeProcessDirect(
                    handleOf(processor), buffers.inputBytes, buffers.outputBytes, frames
                )
                if (status != EchidnaLabDsp.STATUS_OK) {
                    // Engine refused this block: pass input through so the loop stays alive.
                    buffers.inputBytes.rewind()
                    buffers.outputBytes.rewind()
                    buffers.outputBytes.put(buffers.inputBytes)
                }

                // Apply output attenuation and meter, using preallocated scratch.
                buffers.input.rewind(); buffers.input.get(scratchIn, 0, frames)
                buffers.output.rewind(); buffers.output.get(scratchOut, 0, frames)
                val gain = outputGain
                for (i in 0 until frames) scratchOut[i] *= gain

                level.onLevels(
                    AudioAnalysis.rmsDbfs(scratchIn, frames),
                    AudioAnalysis.rmsDbfs(scratchOut, frames)
                )
                player.write(scratchOut, 0, frames, AudioTrack.WRITE_BLOCKING)
            }
            runCatching { recorder.stop() }
            runCatching { player.stop() }
        } catch (error: Exception) {
            Log.w(TAG, "Realtime monitor failed", error)
            onError(error.message ?: "Realtime monitor failed")
        } finally {
            running.set(false)
            runCatching { record?.release() }
            runCatching { track?.release() }
        }
    }

    // The realtime direct path calls the native process by handle. Only LabDspEngine carries a
    // native handle; a non-native processor (tests) never reaches here because start() runs on a
    // device and the fake's process() is used via OfflineDsp instead.
    private fun handleOf(processor: DspProcessor): Long =
        (processor as? LabDspEngine)?.nativeHandle ?: 0L

    fun stop() {
        running.set(false)
        thread?.let { runCatching { it.join(700) } }
        thread = null
    }

    private companion object {
        const val TAG = "LabRealtimeMonitor"
    }
}
