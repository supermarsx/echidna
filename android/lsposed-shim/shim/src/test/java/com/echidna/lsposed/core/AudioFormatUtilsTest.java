package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;

import org.junit.Test;

public final class AudioFormatUtilsTest {

    @Test
    public void everySupportedPcmEncodingReportsItsExactSampleWidth() {
        assertEquals(1, AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_8BIT));
        assertEquals(2, AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_16BIT));
        assertEquals(3, AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_24BIT_PACKED));
        assertEquals(4, AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_32BIT));
        assertEquals(4, AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_FLOAT));
        // AudioRecord treats an unspecified encoding as 16-bit PCM, so the shim must agree rather
        // than fail closed here; a mismatch would mis-frame the most common capture configuration.
        assertEquals(2, AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_DEFAULT));
    }

    @Test
    public void compressedAndUnknownEncodingsFailClosedWithNegativeWidth() {
        int[] nonPcm = {
                AudioFormat.ENCODING_INVALID,
                AudioFormat.ENCODING_AC3,
                AudioFormat.ENCODING_E_AC3,
                AudioFormat.ENCODING_DTS,
                AudioFormat.ENCODING_DTS_HD,
                AudioFormat.ENCODING_MP3,
                AudioFormat.ENCODING_AAC_LC,
                AudioFormat.ENCODING_AAC_HE_V1,
                AudioFormat.ENCODING_IEC61937,
                AudioFormat.ENCODING_DOLBY_TRUEHD,
                AudioFormat.ENCODING_OPUS,
        };
        for (int encoding : nonPcm) {
            assertEquals("encoding " + encoding + " must not be framed as PCM",
                    -1, AudioFormatUtils.bytesPerSample(encoding));
        }

        assertEquals(-1, AudioFormatUtils.bytesPerSample(-1));
        assertEquals(-1, AudioFormatUtils.bytesPerSample(Integer.MIN_VALUE));
        assertEquals(-1, AudioFormatUtils.bytesPerSample(Integer.MAX_VALUE));
        assertEquals(-1, AudioFormatUtils.bytesPerSample(9999));
    }

    @Test
    public void floatDetectionDoesNotFollowSampleWidthAlone() {
        assertTrue(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_PCM_FLOAT));
        // 32-bit integer PCM shares the four-byte width but must never be handed to the float path.
        assertEquals(
                AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_32BIT),
                AudioFormatUtils.bytesPerSample(AudioFormat.ENCODING_PCM_FLOAT));
        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_PCM_32BIT));

        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_PCM_8BIT));
        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_PCM_16BIT));
        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED));
        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_DEFAULT));
        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_INVALID));
        assertFalse(AudioFormatUtils.isFloatEncoding(AudioFormat.ENCODING_OPUS));
    }
}
