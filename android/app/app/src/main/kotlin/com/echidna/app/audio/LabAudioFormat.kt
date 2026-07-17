package com.echidna.app.audio

/** Shared audio format for the Lab: mono 48 kHz float PCM (matches the DSP block contract). */
object LabAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1

    /** Max seconds captured by "record -> listen back". */
    const val MAX_RECORD_SECONDS = 6

    /** Per-block frames for the realtime monitor (~5.3 ms at 48 kHz) — small but stable. */
    const val REALTIME_BLOCK_FRAMES = 256

    /** Upper bound on frames per DSP process call (offline uses the whole recording in chunks). */
    const val PROCESS_CHUNK_FRAMES = 1_024
}
