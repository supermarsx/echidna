package com.echidna.lsposed.core;

import java.nio.ByteBuffer;

/** Copies heap ByteBuffer regions through native scratch without changing caller cursor state. */
final class ByteBufferProcessor {

    interface ScratchProcessor {
        /** Returns true only when {@code scratch} contains committed transformed bytes. */
        boolean process(byte[] scratch);
    }

    private ByteBufferProcessor() {
    }

    static boolean isWritableRange(ByteBuffer buffer, int position, int length) {
        return buffer != null
                && !buffer.isReadOnly()
                && position >= 0
                && length > 0
                && (long) position + (long) length <= (long) buffer.limit();
    }

    static boolean processHeapRegion(
            ByteBuffer buffer,
            int position,
            int length,
            ScratchProcessor processor) {
        if (buffer == null || buffer.isDirect() || processor == null
                || !isWritableRange(buffer, position, length)) {
            return false;
        }
        try {
            byte[] scratch = new byte[length];
            ByteBuffer source = buffer.duplicate();
            source.position(position);
            source.limit(position + length);
            source.get(scratch);
            if (!processor.process(scratch)) {
                return false;
            }
            ByteBuffer destination = buffer.duplicate();
            destination.position(position);
            destination.limit(position + length);
            destination.put(scratch);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Returns a direct view of the bytes AudioRecord wrote, or null for an invalid buffer. */
    static ByteBuffer audioRecordView(ByteBuffer buffer, int length) {
        if (buffer == null
                || !buffer.isDirect()
                || buffer.isReadOnly()
                || length <= 0
                || length > buffer.capacity()) {
            return null;
        }
        ByteBuffer view = buffer.duplicate();
        view.clear();
        view.limit(length);
        return view;
    }
}
