package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.echidna.lsposed.core.ModuleState;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class AudioReadTransactionTest {

    @Test
    public void unauthorizedReadPreservesReturnAndByteAndSampleIdentity() {
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};
        short[] samples = new short[] {10, 20, 30, 40, 50};
        AtomicBoolean called = new AtomicBoolean(false);

        int byteResult = AudioReadTransaction.execute(
                3,
                bytes,
                1,
                null,
                deniedGate(),
                (context, returned) -> {
                    called.set(true);
                    return 1;
                },
                frames -> { },
                throwable -> { });
        int sampleResult = AudioReadTransaction.execute(
                2,
                samples,
                2,
                null,
                deniedGate(),
                (context, returned) -> {
                    called.set(true);
                    return 1;
                },
                frames -> { },
                throwable -> { });

        assertEquals(3, byteResult);
        assertEquals(2, sampleResult);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, bytes);
        assertArrayEquals(new short[] {10, 20, 30, 40, 50}, samples);
        assertFalse(called.get());
    }

    @Test
    public void authorizedPartialReadCommitsOnlyActualReturnedRegion() {
        byte[] bytes = new byte[] {0, 1, 2, 3, 4, 5, 6};
        AtomicBoolean committed = new AtomicBoolean(false);

        int returned = AudioReadTransaction.execute(
                3,
                bytes,
                2,
                null,
                allowedGate(),
                (context, actual) -> {
                    assertEquals(3, actual);
                    bytes[2] = 20;
                    bytes[3] = 30;
                    bytes[4] = 40;
                    return 1;
                },
                frames -> committed.set(true),
                throwable -> { });

        assertEquals(3, returned);
        assertTrue(committed.get());
        assertArrayEquals(new byte[] {0, 1, 20, 30, 40, 5, 6}, bytes);
    }

    @Test
    public void nativeUnavailableOrFalsePreservesReturnAndRollsBackAnyMutation() {
        byte[] unavailable = new byte[] {1, 2, 3, 4};
        byte[] declined = new byte[] {5, 6, 7, 8};

        int unavailableResult = AudioReadTransaction.execute(
                2,
                unavailable,
                1,
                null,
                allowedGate(),
                (context, actual) -> 0,
                frames -> { },
                throwable -> { });
        int declinedResult = AudioReadTransaction.execute(
                2,
                declined,
                1,
                null,
                allowedGate(),
                (context, actual) -> {
                    declined[1] = 60;
                    declined[2] = 70;
                    return 0;
                },
                frames -> { },
                throwable -> { });

        assertEquals(2, unavailableResult);
        assertEquals(2, declinedResult);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, unavailable);
        assertArrayEquals(new byte[] {5, 6, 7, 8}, declined);
    }

    @Test
    public void nativeThrowPreservesReturnRollsBackAndReportsFailure() {
        float[] samples = new float[] {1f, 2f, 3f, 4f};
        AtomicBoolean failureReported = new AtomicBoolean(false);

        int returned = AudioReadTransaction.execute(
                2,
                samples,
                1,
                null,
                allowedGate(),
                (context, actual) -> {
                    samples[1] = 20f;
                    samples[2] = 30f;
                    throw new IllegalStateException("injected native failure");
                },
                frames -> { },
                throwable -> failureReported.set(true));

        assertEquals(2, returned);
        assertArrayEquals(new float[] {1f, 2f, 3f, 4f}, samples, 0f);
        assertTrue(failureReported.get());
    }

    @Test
    public void revokedPermitDuringInFlightReadRollsBackTransformation() {
        byte[] bytes = new byte[] {1, 2, 3, 4};
        AudioReadTransaction.PermitGate racingGate = new AudioReadTransaction.PermitGate() {
            @Override
            public long begin() {
                return 7L;
            }

            @Override
            public boolean isCurrent(long permit) {
                return false;
            }
        };

        int returned = AudioReadTransaction.execute(
                2,
                bytes,
                1,
                null,
                racingGate,
                (context, actual) -> {
                    bytes[1] = 20;
                    bytes[2] = 30;
                    return 1;
                },
                frames -> { },
                throwable -> { });

        assertEquals(2, returned);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes);
    }

    @Test
    public void directBufferTransactionPreservesCursorAndBytesBeyondReturnedCount() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(6);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, (byte) i);
        }
        buffer.position(5);
        buffer.limit(5);

        int returned = AudioReadTransaction.execute(
                3,
                buffer,
                0,
                null,
                allowedGate(),
                (context, actual) -> {
                    buffer.put(0, (byte) 10);
                    buffer.put(1, (byte) 11);
                    buffer.put(2, (byte) 12);
                    return 1;
                },
                frames -> { },
                throwable -> { });

        assertEquals(3, returned);
        assertEquals(5, buffer.position());
        assertEquals(5, buffer.limit());
        ByteBuffer inspection = buffer.duplicate();
        inspection.clear();
        assertArrayEquals(
                new byte[] {10, 11, 12, 3, 4, 5},
                new byte[] {
                    inspection.get(0), inspection.get(1), inspection.get(2),
                    inspection.get(3), inspection.get(4), inspection.get(5)
                });
    }

    @Test
    public void oversizedFallbackRegionIsRejectedBeforeBackupOrNativeWork() {
        byte[] oversized = new byte[8 * 1024 * 1024 + 1];
        oversized[0] = 7;
        oversized[oversized.length - 1] = 9;
        AtomicBoolean transformed = new AtomicBoolean(false);

        int returned = AudioReadTransaction.execute(
                oversized.length,
                oversized,
                0,
                null,
                allowedGate(),
                (context, actual) -> {
                    transformed.set(true);
                    return 1;
                },
                frames -> { },
                throwable -> { });

        assertEquals(oversized.length, returned);
        assertEquals(7, oversized[0]);
        assertEquals(9, oversized[oversized.length - 1]);
        assertFalse(transformed.get());
    }

    @Test
    public void delegatedReadOverloadsApplyNonlinearTransformExactlyOnce() {
        int[] delegatedSample = {2};
        AtomicInteger delegatedTransforms = new AtomicInteger();

        AudioRecordHook.ReadNestingGuard.enter();
        try {
            AudioRecordHook.ReadNestingGuard.enter();
            try {
                applyNonlinearIfOutermost(delegatedSample, delegatedTransforms);
            } finally {
                AudioRecordHook.ReadNestingGuard.exit();
            }
            applyNonlinearIfOutermost(delegatedSample, delegatedTransforms);
        } finally {
            AudioRecordHook.ReadNestingGuard.exit();
        }

        assertEquals(4, delegatedSample[0]);
        assertEquals(1, delegatedTransforms.get());
        assertFalse(AudioRecordHook.ReadNestingGuard.isOutermost());

        int[] directSample = {3};
        AtomicInteger directTransforms = new AtomicInteger();
        AudioRecordHook.ReadNestingGuard.enter();
        try {
            applyNonlinearIfOutermost(directSample, directTransforms);
        } finally {
            AudioRecordHook.ReadNestingGuard.exit();
        }
        assertEquals(9, directSample[0]);
        assertEquals(1, directTransforms.get());
    }

    @Test
    public void readNestingGuardIsIndependentAcrossConcurrentThreads() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger outermostReads = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean(false);
        Runnable read = () -> {
            AudioRecordHook.ReadNestingGuard.enter();
            try {
                if (AudioRecordHook.ReadNestingGuard.isOutermost()) {
                    outermostReads.incrementAndGet();
                } else {
                    failed.set(true);
                }
                bothEntered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    failed.set(true);
                }
                AudioRecordHook.ReadNestingGuard.enter();
                try {
                    if (AudioRecordHook.ReadNestingGuard.isOutermost()) {
                        failed.set(true);
                    }
                } finally {
                    AudioRecordHook.ReadNestingGuard.exit();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failed.set(true);
            } finally {
                AudioRecordHook.ReadNestingGuard.exit();
            }
        };
        Thread first = new Thread(read, "audio-read-1");
        Thread second = new Thread(read, "audio-read-2");

        first.start();
        second.start();
        assertTrue(bothEntered.await(2, TimeUnit.SECONDS));
        release.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertFalse(failed.get());
        assertEquals(2, outermostReads.get());
    }

    private static void applyNonlinearIfOutermost(
            int[] sample, AtomicInteger transforms) {
        if (AudioRecordHook.ReadNestingGuard.isOutermost()) {
            sample[0] *= sample[0];
            transforms.incrementAndGet();
        }
    }

    private static AudioReadTransaction.PermitGate deniedGate() {
        return new AudioReadTransaction.PermitGate() {
            @Override
            public long begin() {
                return ModuleState.INVALID_AUDIO_PROCESSING_PERMIT;
            }

            @Override
            public boolean isCurrent(long permit) {
                return false;
            }
        };
    }

    private static AudioReadTransaction.PermitGate allowedGate() {
        return new AudioReadTransaction.PermitGate() {
            @Override
            public long begin() {
                return 1L;
            }

            @Override
            public boolean isCurrent(long permit) {
                return permit == 1L;
            }
        };
    }
}
