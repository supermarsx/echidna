package com.echidna.lsposed.core;

import android.media.AudioFormat;

/**
 * Utility helpers for translating {@link AudioFormat} encodings into actionable metadata.
 */
public final class AudioFormatUtils {

    private AudioFormatUtils() {
    }

    public static int bytesPerSample(int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return 1;
            case AudioFormat.ENCODING_PCM_16BIT:
            case AudioFormat.ENCODING_DEFAULT:
                return 2;
            case AudioFormat.ENCODING_PCM_FLOAT:
            case AudioFormat.ENCODING_PCM_32BIT:
                return 4;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                return 3;
            case AudioFormat.ENCODING_PCM_24BIT:
                return 4; // Up-sample to 32-bit container for DSP convenience.
            default:
                return -1;
        }
    }

    public static boolean isFloatEncoding(int encoding) {
        return encoding == AudioFormat.ENCODING_PCM_FLOAT;
    }
}
