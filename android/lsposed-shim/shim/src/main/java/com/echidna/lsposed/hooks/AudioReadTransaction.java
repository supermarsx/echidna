package com.echidna.lsposed.hooks;

import com.echidna.lsposed.core.ModuleState;

import java.nio.ByteBuffer;

/**
 * Transactional guard for AudioRecord post-processing.
 *
 * <p>The original read result is always returned. The exact returned region is backed up before
 * native work and restored on native failure, exception, or a concurrent policy revocation.
 */
public final class AudioReadTransaction {

    private static final long MAX_BACKUP_BYTES = 8L * 1024L * 1024L;

    public interface PermitGate {
        long begin();
        boolean isCurrent(long permit);
    }

    public interface Transform {
        /** Returns the positive number of committed frames, or zero when native declined. */
        int apply(Object callbackContext, int returnedUnits) throws Throwable;
    }

    public interface CommitObserver {
        void onCommit(int frames);
    }

    public interface FailureObserver {
        void onFailure(Throwable throwable);
    }

    private static final ThreadLocal<RegionBackup> LOCAL_BACKUP =
            ThreadLocal.withInitial(RegionBackup::new);

    private AudioReadTransaction() {
    }

    public static int execute(
            int originalResult,
            Object buffer,
            int offset,
            Object callbackContext,
            PermitGate gate,
            Transform transform,
            CommitObserver commitObserver,
            FailureObserver failureObserver) {
        if (originalResult <= 0 || gate == null || transform == null) {
            return originalResult;
        }
        long permit = gate.begin();
        if (permit == ModuleState.INVALID_AUDIO_PROCESSING_PERMIT) {
            return originalResult;
        }
        RegionBackup backup = acquireBackup(buffer, offset, originalResult);
        if (backup == null) {
            return originalResult;
        }

        boolean commit = false;
        try {
            int frames = transform.apply(callbackContext, originalResult);
            if (frames > 0 && gate.isCurrent(permit)) {
                if (commitObserver != null) {
                    commitObserver.onCommit(frames);
                }
                commit = true;
            }
        } catch (Throwable throwable) {
            if (failureObserver != null) {
                try {
                    failureObserver.onFailure(throwable);
                } catch (Throwable ignored) {
                    // Failure reporting must never replace AudioRecord's successful return value.
                }
            }
        } finally {
            backup.finish(commit);
        }
        return originalResult;
    }

    private static RegionBackup acquireBackup(Object buffer, int offset, int length) {
        RegionBackup backup = LOCAL_BACKUP.get();
        if (backup.inUse) {
            // Defensive re-entrant path. Normal AudioRecord callbacks reuse the thread-local object.
            backup = new RegionBackup();
        }
        return backup.capture(buffer, offset, length) ? backup : null;
    }

    private static final class RegionBackup {
        private Object buffer;
        private int offset;
        private int length;
        private byte[] byteScratch = new byte[0];
        private short[] shortScratch = new short[0];
        private float[] floatScratch = new float[0];
        private boolean inUse;

        boolean capture(Object nextBuffer, int nextOffset, int nextLength) {
            if (inUse || nextBuffer == null || nextOffset < 0 || nextLength <= 0) {
                return false;
            }
            try {
                if (nextBuffer instanceof byte[]) {
                    byte[] bytes = (byte[]) nextBuffer;
                    if (!hasRange(bytes.length, nextOffset, nextLength)
                            || !withinBackupLimit(nextLength, Byte.BYTES)) return false;
                    if (byteScratch.length < nextLength) byteScratch = new byte[nextLength];
                    System.arraycopy(bytes, nextOffset, byteScratch, 0, nextLength);
                } else if (nextBuffer instanceof short[]) {
                    short[] samples = (short[]) nextBuffer;
                    if (!hasRange(samples.length, nextOffset, nextLength)
                            || !withinBackupLimit(nextLength, Short.BYTES)) return false;
                    if (shortScratch.length < nextLength) shortScratch = new short[nextLength];
                    System.arraycopy(samples, nextOffset, shortScratch, 0, nextLength);
                } else if (nextBuffer instanceof float[]) {
                    float[] samples = (float[]) nextBuffer;
                    if (!hasRange(samples.length, nextOffset, nextLength)
                            || !withinBackupLimit(nextLength, Float.BYTES)) return false;
                    if (floatScratch.length < nextLength) floatScratch = new float[nextLength];
                    System.arraycopy(samples, nextOffset, floatScratch, 0, nextLength);
                } else if (nextBuffer instanceof ByteBuffer) {
                    ByteBuffer bytes = (ByteBuffer) nextBuffer;
                    if (!bytes.isDirect()
                            || bytes.isReadOnly()
                            || !hasRange(bytes.capacity(), nextOffset, nextLength)
                            || !withinBackupLimit(nextLength, Byte.BYTES)) {
                        return false;
                    }
                    if (byteScratch.length < nextLength) byteScratch = new byte[nextLength];
                    ByteBuffer source = bytes.duplicate();
                    source.clear();
                    source.position(nextOffset);
                    source.limit(nextOffset + nextLength);
                    source.get(byteScratch, 0, nextLength);
                } else {
                    return false;
                }
            } catch (RuntimeException exception) {
                return false;
            }
            buffer = nextBuffer;
            offset = nextOffset;
            length = nextLength;
            inUse = true;
            return true;
        }

        void finish(boolean commit) {
            try {
                if (!commit) {
                    restore();
                }
            } finally {
                buffer = null;
                offset = 0;
                length = 0;
                inUse = false;
            }
        }

        private void restore() {
            if (buffer instanceof byte[]) {
                System.arraycopy(byteScratch, 0, (byte[]) buffer, offset, length);
            } else if (buffer instanceof short[]) {
                System.arraycopy(shortScratch, 0, (short[]) buffer, offset, length);
            } else if (buffer instanceof float[]) {
                System.arraycopy(floatScratch, 0, (float[]) buffer, offset, length);
            } else if (buffer instanceof ByteBuffer) {
                ByteBuffer destination = ((ByteBuffer) buffer).duplicate();
                destination.clear();
                destination.position(offset);
                destination.limit(offset + length);
                destination.put(byteScratch, 0, length);
            }
        }

        private static boolean hasRange(int capacity, int offset, int length) {
            return capacity >= 0
                    && offset >= 0
                    && length > 0
                    && (long) offset + (long) length <= (long) capacity;
        }

        private static boolean withinBackupLimit(int units, int bytesPerUnit) {
            return units > 0
                    && bytesPerUnit > 0
                    && (long) units * (long) bytesPerUnit <= MAX_BACKUP_BYTES;
        }
    }
}
