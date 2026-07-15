package com.echidna.lsposed.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public final class ByteBufferProcessorTest {

    @Test
    public void successfulHeapTransformCommitsOnlyRequestedRegionAndPreservesCursor() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {10, 11, 12, 13, 14, 15});
        buffer.position(5);
        buffer.limit(6);

        boolean transformed = ByteBufferProcessor.processHeapRegion(
                buffer,
                2,
                3,
                scratch -> {
                    for (int i = 0; i < scratch.length; i++) {
                        scratch[i] = (byte) (scratch[i] + 20);
                    }
                    return true;
                });

        assertTrue(transformed);
        assertArrayEquals(new byte[] {10, 11, 32, 33, 34, 15}, buffer.array());
        assertEquals(5, buffer.position());
        assertEquals(6, buffer.limit());
    }

    @Test
    public void nativeFailureLeavesHeapRegionAndCursorUnchanged() {
        byte[] original = new byte[] {1, 2, 3, 4, 5};
        ByteBuffer buffer = ByteBuffer.wrap(original.clone());
        buffer.position(4);

        boolean transformed = ByteBufferProcessor.processHeapRegion(
                buffer,
                1,
                3,
                scratch -> {
                    scratch[0] = 99;
                    return false;
                });

        assertFalse(transformed);
        assertArrayEquals(original, buffer.array());
        assertEquals(4, buffer.position());
        assertEquals(5, buffer.limit());
    }

    @Test
    public void invalidPartialRangesAndReadOnlyBuffersFailClosed() {
        ByteBuffer heap = ByteBuffer.allocate(4);
        assertFalse(ByteBufferProcessor.processHeapRegion(heap, -1, 1, scratch -> true));
        assertFalse(ByteBufferProcessor.processHeapRegion(heap, 3, 2, scratch -> true));
        assertFalse(ByteBufferProcessor.processHeapRegion(heap, 0, 0, scratch -> true));

        ByteBuffer readOnlyHeap = heap.asReadOnlyBuffer();
        assertFalse(ByteBufferProcessor.isWritableRange(readOnlyHeap, 0, 1));
        assertFalse(ByteBufferProcessor.processHeapRegion(readOnlyHeap, 0, 1, scratch -> true));

        ByteBuffer readOnlyDirect = ByteBuffer.allocateDirect(4).asReadOnlyBuffer();
        assertFalse(ByteBufferProcessor.isWritableRange(readOnlyDirect, 0, 4));
    }

    @Test
    public void directWritableRangeUsesLimitNotCapacity() {
        ByteBuffer direct = ByteBuffer.allocateDirect(8);
        direct.limit(5);

        assertTrue(ByteBufferProcessor.isWritableRange(direct, 1, 4));
        assertFalse(ByteBufferProcessor.isWritableRange(direct, 1, 5));
    }

    @Test
    public void slicedHeapBufferUsesLogicalOffsetsAndCommitsOnlyPartialByteCount() {
        byte[] backing = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};
        ByteBuffer parent = ByteBuffer.wrap(backing);
        parent.position(2);
        parent.limit(7);
        ByteBuffer slice = parent.slice();
        slice.position(4);
        slice.limit(5);

        assertEquals(2, slice.arrayOffset());
        assertTrue(ByteBufferProcessor.processHeapRegion(
                slice,
                1,
                3,
                scratch -> {
                    assertArrayEquals(new byte[] {3, 4, 5}, scratch);
                    scratch[0] = 30;
                    scratch[1] = 40;
                    scratch[2] = 50;
                    return true;
                }));

        assertArrayEquals(new byte[] {0, 1, 2, 30, 40, 50, 6, 7}, backing);
        assertEquals(4, slice.position());
        assertEquals(5, slice.limit());
        assertEquals(2, parent.position());
        assertEquals(7, parent.limit());
    }

    @Test
    public void genericRangeValidationHasDirectAndHeapParity() {
        ByteBuffer heap = ByteBuffer.allocate(8);
        ByteBuffer direct = ByteBuffer.allocateDirect(8);
        heap.position(4);
        direct.position(4);
        heap.limit(6);
        direct.limit(6);

        assertEquals(
                ByteBufferProcessor.isWritableRange(heap, 2, 4),
                ByteBufferProcessor.isWritableRange(direct, 2, 4));
        assertEquals(
                ByteBufferProcessor.isWritableRange(heap, 3, 4),
                ByteBufferProcessor.isWritableRange(direct, 3, 4));
    }

    @Test
    public void audioRecordViewUsesDirectCapacityFromZeroAndPreservesCallerCursor() {
        ByteBuffer direct = ByteBuffer.allocateDirect(8);
        direct.position(6);
        direct.limit(6);
        direct.put(0, (byte) 10);
        direct.put(1, (byte) 11);
        direct.put(2, (byte) 12);

        ByteBuffer view = ByteBufferProcessor.audioRecordView(direct, 3);

        assertNotNull(view);
        assertEquals(0, view.position());
        assertEquals(3, view.limit());
        assertEquals(8, view.capacity());
        assertEquals(6, direct.position());
        assertEquals(6, direct.limit());
        assertArrayEquals(new byte[] {10, 11, 12}, new byte[] {view.get(0), view.get(1), view.get(2)});
        view.put(1, (byte) 42);
        assertEquals(42, direct.get(1));
    }

    @Test
    public void audioRecordViewRejectsHeapReadOnlyAndOversizedRequests() {
        assertNull(ByteBufferProcessor.audioRecordView(ByteBuffer.allocate(4), 4));
        assertNull(ByteBufferProcessor.audioRecordView(
                ByteBuffer.allocateDirect(4).asReadOnlyBuffer(), 4));
        assertNull(ByteBufferProcessor.audioRecordView(ByteBuffer.allocateDirect(4), 0));
        assertNull(ByteBufferProcessor.audioRecordView(ByteBuffer.allocateDirect(4), 5));
    }
}
