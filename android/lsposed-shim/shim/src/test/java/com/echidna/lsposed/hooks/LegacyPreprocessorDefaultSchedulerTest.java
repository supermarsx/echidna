package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * The production scheduler behind the session manager. Every hook callback that defers effect work
 * passes through here, so its bounded budget is the module's backpressure guard rather than a
 * detail: a saturated or dead scheduler must refuse work instead of queueing it without limit.
 */
public final class LegacyPreprocessorDefaultSchedulerTest {

    @Test
    public void saturatedSchedulerRefusesFurtherWorkInsteadOfQueueingItUnbounded()
            throws Exception {
        LegacyPreprocessorSessionManager.DefaultScheduler scheduler =
                new LegacyPreprocessorSessionManager.DefaultScheduler(2);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(scheduler.execute(() -> {
                occupied.countDown();
                awaitQuietly(release);
            }));
            assertTrue(occupied.await(3, TimeUnit.SECONDS));
            assertTrue(scheduler.execute(() -> { }));

            // Both budgeted slots are held; execute and schedule share one budget.
            assertFalse(scheduler.execute(() -> { }));
            assertNull(scheduler.schedule(() -> { }, 0L));

            release.countDown();
            assertTrue(awaitCapacity(scheduler));
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    public void throwingTaskReleasesItsSlotSoTheSchedulerCannotWedgeShut() throws Exception {
        LegacyPreprocessorSessionManager.DefaultScheduler scheduler =
                new LegacyPreprocessorSessionManager.DefaultScheduler(1);
        CountDownLatch ran = new CountDownLatch(1);
        try {
            assertTrue(scheduler.execute(() -> {
                ran.countDown();
                throw new IllegalStateException("injected effect failure");
            }));
            assertTrue(ran.await(3, TimeUnit.SECONDS));

            // The single slot must come back even though the task died inside the executor.
            assertTrue(awaitCapacity(scheduler));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void shutdownSchedulerFailsClosedOnEverySubmissionFormWithoutThrowing() {
        LegacyPreprocessorSessionManager.DefaultScheduler scheduler =
                new LegacyPreprocessorSessionManager.DefaultScheduler(4);
        scheduler.shutdown();

        // More attempts than the budget: a rejected submission must not consume a slot in a way
        // that changes the answer, and must never surface RejectedExecutionException to a hook.
        for (int attempt = 0; attempt < 12; attempt++) {
            assertFalse(scheduler.execute(() -> { }));
            assertNull(scheduler.schedule(() -> { }, 5L));
        }
    }

    @Test
    public void deferredWorkRunsSeriallyOnOneDedicatedDaemonThreadOffTheSubmitter()
            throws Exception {
        LegacyPreprocessorSessionManager.DefaultScheduler scheduler =
                new LegacyPreprocessorSessionManager.DefaultScheduler(16);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicLong workerThread = new AtomicLong(-1L);
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(4);
        long submitterThread = Thread.currentThread().getId();
        try {
            for (int index = 0; index < 4; index++) {
                int position = index;
                assertTrue(scheduler.execute(() -> {
                    Thread current = Thread.currentThread();
                    threadName.set(current.getName());
                    workerThread.set(current.getId());
                    daemon.set(current.isDaemon());
                    order.add(position);
                    done.countDown();
                }));
            }
            assertTrue(done.await(3, TimeUnit.SECONDS));

            assertEquals(Arrays.asList(0, 1, 2, 3), new ArrayList<>(order));
            assertNotEquals(submitterThread, workerThread.get());
            assertEquals("echidna-preprocessor-session", threadName.get());
            // A non-daemon worker would keep a hooked host process alive after it detaches.
            assertEquals(Boolean.TRUE, daemon.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void delayedWorkIsBudgetedAndStillDispatchedOffTheSubmitter() throws Exception {
        LegacyPreprocessorSessionManager.DefaultScheduler scheduler =
                new LegacyPreprocessorSessionManager.DefaultScheduler(1);
        CountDownLatch ran = new CountDownLatch(1);
        try {
            ScheduledFuture<?> future = scheduler.schedule(ran::countDown, 5L);
            assertNotNull(future);

            // The pending delayed task holds the only slot until it has actually run.
            assertFalse(scheduler.execute(() -> { }));
            assertTrue(ran.await(3, TimeUnit.SECONDS));
            assertTrue(awaitCapacity(scheduler));
        } finally {
            scheduler.shutdown();
        }
    }

    /** Polls until the scheduler admits work again, so a reclaimed slot is proven, not assumed. */
    private static boolean awaitCapacity(
            LegacyPreprocessorSessionManager.DefaultScheduler scheduler) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            CountDownLatch admitted = new CountDownLatch(1);
            if (scheduler.execute(admitted::countDown)) {
                return admitted.await(3, TimeUnit.SECONDS);
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
