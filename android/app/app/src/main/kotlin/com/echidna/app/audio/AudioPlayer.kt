package com.echidna.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a mono float PCM buffer through an [AudioTrack] for the Lab's "listen back"
 * and A/B compare. One playback at a time; [play] stops any in-flight playback first.
 */
class AudioPlayer(private val sampleRate: Int = LabAudioFormat.SAMPLE_RATE) {

    private val playing = AtomicBoolean(false)
    private var thread: Thread? = null

    val isPlaying: Boolean get() = playing.get()

    /** Plays [samples] to completion (or until [stop]); [onComplete] runs on finish either way. */
    fun play(samples: FloatArray, onComplete: () -> Unit = {}) {
        stop()
        if (samples.isEmpty()) {
            onComplete()
            return
        }
        playing.set(true)
        thread = Thread {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                if (minBuf <= 0) return@Thread
                val bufBytes = maxOf(minBuf, samples.size * Float.SIZE_BYTES).coerceAtMost(minBuf * 8)
                val newTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build(),
                    bufBytes,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track = newTrack
                if (newTrack.state != AudioTrack.STATE_INITIALIZED) return@Thread
                newTrack.play()
                var offset = 0
                while (playing.get() && offset < samples.size) {
                    val n = newTrack.write(
                        samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING
                    )
                    if (n <= 0) break
                    offset += n
                }
                // Drain the last block before releasing so the tail is audible.
                if (playing.get()) {
                    runCatching {
                        newTrack.stop()
                        Thread.sleep(20)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Playback failed", error)
            } finally {
                playing.set(false)
                runCatching { track?.release() }
                onComplete()
            }
        }.also { it.isDaemon = true; it.name = "LabAudioPlayer"; it.start() }
    }

    fun stop() {
        playing.set(false)
        thread?.let { runCatching { it.join(500) } }
        thread = null
    }

    private companion object {
        const val TAG = "LabAudioPlayer"
    }
}
