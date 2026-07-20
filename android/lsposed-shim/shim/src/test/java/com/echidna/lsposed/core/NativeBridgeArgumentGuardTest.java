package com.echidna.lsposed.core;

import static org.junit.Assert.assertFalse;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * The JNI entry points cannot be exercised off-device, but their argument guards deliberately run
 * <em>before</em> {@code initialize()} so a malformed request never reaches native code. These
 * tests pin that ordering: on a host JVM with no {@code libechidna_shim_jni}, every call below must
 * return false purely from validation.
 */
public final class NativeBridgeArgumentGuardTest {

    private static final int MAX_PROCESS_BUFFER_BYTES = 8 * 1024 * 1024;

    @Test
    public void nullBuffersAreRejected() {
        assertFalse(NativeBridge.processByteArray(null, 0, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processShortArray(null, 0, 16, 48000, 1));
        assertFalse(NativeBridge.processFloatArray(null, 0, 16, 48000, 1));
        assertFalse(NativeBridge.processByteBuffer(null, 0, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processAudioRecordByteBuffer(null, 16, 2, 48000, 1));
    }

    @Test
    public void outOfRangeAndOverflowingRegionsAreRejected() {
        byte[] bytes = new byte[64];
        assertFalse(NativeBridge.processByteArray(bytes, -1, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteArray(bytes, 0, 0, 2, 48000, 1));
        assertFalse(NativeBridge.processByteArray(bytes, 0, -16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteArray(bytes, 60, 16, 2, 48000, 1));
        // offset + length must be summed as a long; a 32-bit sum here would wrap to a valid range.
        assertFalse(NativeBridge.processByteArray(bytes, Integer.MAX_VALUE, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteArray(bytes, 16, Integer.MAX_VALUE, 2, 48000, 1));

        short[] shorts = new short[64];
        assertFalse(NativeBridge.processShortArray(shorts, -1, 16, 48000, 1));
        assertFalse(NativeBridge.processShortArray(shorts, 60, 16, 48000, 1));
        assertFalse(NativeBridge.processShortArray(shorts, 0, 0, 48000, 1));

        float[] floats = new float[64];
        assertFalse(NativeBridge.processFloatArray(floats, -1, 16, 48000, 1));
        assertFalse(NativeBridge.processFloatArray(floats, 60, 16, 48000, 1));
        assertFalse(NativeBridge.processFloatArray(floats, 0, 0, 48000, 1));
    }

    @Test
    public void requestsAboveTheEightMegabyteCeilingAreRejected() {
        byte[] oversized = new byte[MAX_PROCESS_BUFFER_BYTES + 1];
        assertFalse(NativeBridge.processByteArray(
                oversized, 0, MAX_PROCESS_BUFFER_BYTES + 1, 2, 48000, 1));

        // The ceiling is a byte budget, so element-width scaling must be applied before comparing.
        short[] shorts = new short[(MAX_PROCESS_BUFFER_BYTES / Short.BYTES) + 1];
        assertFalse(NativeBridge.processShortArray(shorts, 0, shorts.length, 48000, 1));
    }

    @Test
    public void implausibleSampleRatesAndChannelCountsAreRejected() {
        byte[] bytes = new byte[64];
        assertFalse(NativeBridge.processByteArray(bytes, 0, 16, 2, 0, 1));
        assertFalse(NativeBridge.processByteArray(bytes, 0, 16, 2, -48000, 1));
        assertFalse(NativeBridge.processByteArray(bytes, 0, 16, 2, 48000, 0));
        assertFalse(NativeBridge.processByteArray(bytes, 0, 16, 2, 48000, -1));
        assertFalse(NativeBridge.processByteArray(bytes, 0, 16, 2, 48000, 9));
        assertFalse(NativeBridge.processShortArray(new short[64], 0, 16, 48000, 9));
        assertFalse(NativeBridge.processFloatArray(new float[64], 0, 16, 48000, 9));
    }

    @Test
    public void byteBufferEntryPointsRejectUnwritableAndMisdescribedViews() {
        assertFalse(NativeBridge.processByteBuffer(
                ByteBuffer.allocate(64).asReadOnlyBuffer(), 0, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteBuffer(
                ByteBuffer.allocateDirect(64).asReadOnlyBuffer(), 0, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteBuffer(ByteBuffer.allocate(64), -1, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteBuffer(ByteBuffer.allocate(64), 60, 16, 2, 48000, 1));
        assertFalse(NativeBridge.processByteBuffer(ByteBuffer.allocate(64), 0, 0, 2, 48000, 1));
        assertFalse(NativeBridge.processByteBuffer(ByteBuffer.allocate(64), 0, 16, 2, 48000, 9));

        // AudioRecord's ByteBuffer overload only ever hands back a writable direct buffer, and the
        // reported length may never exceed its capacity.
        assertFalse(NativeBridge.processAudioRecordByteBuffer(ByteBuffer.allocate(64), 16, 2, 48000, 1));
        assertFalse(NativeBridge.processAudioRecordByteBuffer(
                ByteBuffer.allocateDirect(64).asReadOnlyBuffer(), 16, 2, 48000, 1));
        assertFalse(NativeBridge.processAudioRecordByteBuffer(
                ByteBuffer.allocateDirect(64), 65, 2, 48000, 1));
        assertFalse(NativeBridge.processAudioRecordByteBuffer(
                ByteBuffer.allocateDirect(64), 0, 2, 48000, 1));
    }
}
