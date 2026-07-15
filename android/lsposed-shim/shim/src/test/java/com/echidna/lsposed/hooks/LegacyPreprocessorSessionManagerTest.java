package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

public final class LegacyPreprocessorSessionManagerTest {

    @Test
    public void lifecycleAndCapabilityCallbackStayOffHookAndBinderThreads() {
        Harness harness = new Harness();

        harness.manager.onInitialized(harness.record, 41, true);
        harness.manager.onStart(harness.record, 41);

        assertEquals(0, harness.factory.createCount);
        assertEquals(0, harness.capabilities.requests.size());
        harness.runDirectRead();
        assertEquals(1, harness.directTransforms);

        harness.scheduler.runReady();
        assertEquals(1, harness.factory.createCount);
        assertEquals(1, harness.capabilities.requests.size());
        FakeEffect effect = harness.factory.created.get(0);
        assertEquals(0, effect.authorizeCount);
        harness.runDirectRead();
        assertEquals(2, harness.directTransforms);

        harness.replySuccess(0);
        assertEquals(0, effect.authorizeCount);
        assertFalse(harness.manager.ownsRoute(harness.record));

        harness.scheduler.runReady();
        assertEquals(1, effect.authorizeCount);
        assertEquals(1, effect.enableCount);
        assertArrayEquals(
                LegacyPreprocessorSessionManager.AUTHORIZE_PARAMETER,
                effect.lastAuthorizeParameter);
        assertTrue(harness.manager.ownsRoute(harness.record));
        harness.runDirectRead();
        assertEquals(2, harness.directTransforms);

        harness.manager.onStop(harness.record);
        assertFalse(harness.manager.ownsRoute(harness.record));
        assertEquals(0, effect.releaseCount);
        harness.runDirectRead();
        assertEquals(3, harness.directTransforms);

        harness.scheduler.runReady();
        assertEquals(1, effect.revokeCount);
        assertEquals(1, effect.disableCount);
        assertEquals(1, effect.releaseCount);
    }

    @Test
    public void unsupportedProviderAndCreationFailuresKeepDirectRoute() {
        Harness unsupported = new Harness();
        unsupported.capabilities.acceptRequests = false;
        unsupported.start(42);
        unsupported.assertDirectFallback("provider_unsupported");
        assertEquals(1, unsupported.factory.created.get(0).releaseCount);

        Harness creation = new Harness();
        creation.factory.createError = new Exception("missing effect");
        creation.start(42);
        creation.assertDirectFallback("effect_create_failed");

        Harness descriptor = new Harness();
        descriptor.factory.next.descriptor = new LegacyPreprocessorSessionManager.Descriptor(
                UUID.randomUUID(),
                LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID,
                "Pre Processing");
        descriptor.start(42);
        descriptor.assertDirectFallback("effect_descriptor_mismatch");
        assertEquals(1, descriptor.factory.created.get(0).releaseCount);

        Harness noControl = new Harness();
        noControl.factory.next.hasControl = false;
        noControl.start(42);
        noControl.assertDirectFallback("effect_no_control");
    }

    @Test
    public void rejectedStaleInvalidAndMissingCapabilitiesKeepDirectRoute() {
        Harness rejected = new Harness();
        rejected.start(43);
        rejected.reply(0, -1, 1L, new byte[0], "policy_denied");
        rejected.scheduler.runReady();
        rejected.assertDirectFallback("capability_policy_denied");

        Harness invalid = new Harness();
        invalid.start(43);
        invalid.reply(0, 0, 1L, new byte[0], "accepted");
        invalid.scheduler.runReady();
        invalid.assertDirectFallback("capability_invalid");

        Harness stale = new Harness();
        stale.start(43);
        CapabilityRequest staleRequest = stale.capabilities.requests.get(0);
        stale.policy.generation = 2L;
        stale.reply(
                0,
                0,
                staleRequest.generation,
                envelope(
                        staleRequest.sessionId,
                        staleRequest.generation,
                        staleRequest.nonce,
                        stale.clock.nowMs,
                        stale.clock.nowMs + 5_000L),
                "accepted");
        stale.scheduler.runReady();
        stale.assertDirectFallback("capability_stale");

        Harness disconnected = new Harness();
        disconnected.start(43);
        disconnected.capabilities.requests.get(0).callback.onFailure("disconnected");
        disconnected.scheduler.runReady();
        disconnected.assertDirectFallback("provider_disconnected");
    }

    @Test
    public void effectApplyFailuresAlwaysRestoreDirectRoute() {
        Harness lostControl = new Harness();
        lostControl.start(44);
        lostControl.factory.created.get(0).hasControl = false;
        lostControl.replySuccess(0);
        lostControl.scheduler.runReady();
        lostControl.assertDirectFallback("effect_control_lost");

        Harness parameter = new Harness();
        parameter.factory.next.authorizeStatus = -22;
        parameter.start(44);
        parameter.replySuccess(0);
        parameter.scheduler.runReady();
        parameter.assertDirectFallback("effect_parameter_-22");

        Harness enable = new Harness();
        enable.factory.next.enableStatus = -38;
        enable.start(44);
        enable.replySuccess(0);
        enable.scheduler.runReady();
        enable.assertDirectFallback("effect_enable_-38");

        Harness full = new Harness();
        for (int i = 0; i < 64; i++) {
            assertTrue(full.leases.acquire(new Object(), 100 + i));
        }
        full.start(44);
        full.replySuccess(0);
        full.scheduler.runReady();
        full.assertDirectFallback("route_lease_full");
    }

    @Test
    public void renewalReusesEffectAndPolicyLossRevokesLease() {
        Harness harness = new Harness();
        harness.start(45);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        harness.scheduler.advance(3_500L);
        assertEquals(2, harness.capabilities.requests.size());
        assertEquals(1, harness.factory.createCount);
        assertTrue(harness.manager.ownsRoute(harness.record));

        harness.replySuccess(1);
        harness.scheduler.runReady();
        assertEquals(2, harness.factory.created.get(0).authorizeCount);
        assertEquals(1, harness.factory.created.get(0).enableCount);
        assertTrue(harness.manager.ownsRoute(harness.record));

        harness.policy.eligible = false;
        harness.scheduler.advance(250L);
        assertFalse(harness.manager.ownsRoute(harness.record));
        assertEquals(1, harness.factory.created.get(0).releaseCount);
        harness.runDirectRead();
        assertEquals(1, harness.directTransforms);
    }

    @Test
    public void controlLossAndTeardownFailuresStillRestoreDirectRoute() {
        Harness controlLoss = new Harness();
        controlLoss.start(51);
        controlLoss.replySuccess(0);
        controlLoss.scheduler.runReady();
        FakeEffect lost = controlLoss.factory.created.get(0);
        lost.hasControl = false;
        controlLoss.scheduler.advance(250L);
        controlLoss.assertDirectFallback("effect_control_lost");
        assertEquals(1, lost.releaseCount);

        Harness teardown = new Harness();
        teardown.start(52);
        teardown.replySuccess(0);
        teardown.scheduler.runReady();
        FakeEffect failing = teardown.factory.created.get(0);
        failing.revokeStatus = -5;
        failing.disableStatus = -6;
        failing.releaseError = new Exception("release failed");
        teardown.manager.onStop(teardown.record);
        assertFalse(teardown.manager.ownsRoute(teardown.record));
        teardown.runDirectRead();
        teardown.scheduler.runReady();
        assertTrue(teardown.diagnostics.codes.contains("effect_revoke_-5"));
        assertTrue(teardown.diagnostics.codes.contains("effect_disable_-6"));
        assertTrue(teardown.diagnostics.codes.contains("effect_release_failed"));
    }

    @Test
    public void generationChangeDuringRequestRecreatesEffectAndIgnoresOldCallback() {
        Harness harness = new Harness();
        harness.start(53);
        CapabilityRequest oldRequest = harness.capabilities.requests.get(0);

        harness.policy.generation = 2L;
        harness.scheduler.advance(250L);
        assertEquals(2, harness.capabilities.requests.size());
        assertEquals(2L, harness.capabilities.requests.get(1).generation);
        assertEquals(1, harness.factory.created.get(0).releaseCount);
        assertFalse(harness.manager.ownsRoute(harness.record));

        oldRequest.callback.onResult(
                0,
                oldRequest.generation,
                envelope(
                        oldRequest.sessionId,
                        oldRequest.generation,
                        oldRequest.nonce,
                        harness.clock.nowMs,
                        harness.clock.nowMs + 5_000L),
                "accepted");
        harness.scheduler.runReady();
        assertFalse(harness.manager.ownsRoute(harness.record));

        harness.replySuccess(1);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertEquals(1, harness.factory.created.get(1).authorizeCount);
    }

    @Test
    public void capabilityTimeoutAndSchedulerRejectionFailClosed() {
        Harness timeout = new Harness();
        timeout.start(46);
        timeout.scheduler.advance(1_000L);
        timeout.assertDirectFallback("capability_timeout");
        assertEquals(1, timeout.factory.created.get(0).releaseCount);

        Harness executeRejected = new Harness();
        executeRejected.scheduler.rejectExecute = true;
        executeRejected.manager.onStart(executeRejected.record, 46);
        executeRejected.assertDirectFallback("executor_saturated_start");
        assertEquals(0, executeRejected.factory.createCount);

        Harness scheduleRejected = new Harness();
        scheduleRejected.scheduler.rejectSchedule = true;
        scheduleRejected.start(46);
        scheduleRejected.assertDirectFallback("executor_saturated_health");
        assertEquals(1, scheduleRejected.factory.created.get(0).releaseCount);
    }

    @Test
    public void rejectedRenewalCallbackKeepsLeaseUntilSerialTeardown() {
        Harness harness = new Harness();
        harness.start(54);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        harness.scheduler.advance(3_500L);
        assertEquals(2, harness.capabilities.requests.size());
        assertTrue(harness.manager.ownsRoute(harness.record));

        harness.scheduler.rejectExecute = true;
        harness.replySuccess(1);
        assertTrue(harness.manager.ownsRoute(harness.record));
        harness.runDirectRead();
        assertEquals(0, harness.directTransforms);
        assertTrue(harness.diagnostics.codes.contains("executor_saturated_callback"));

        harness.scheduler.rejectExecute = false;
        harness.scheduler.advance(1_000L);
        assertFalse(harness.manager.ownsRoute(harness.record));
        harness.runDirectRead();
        assertEquals(1, harness.directTransforms);
        assertTrue(harness.diagnostics.codes.contains("capability_timeout"));
        assertEquals(1, harness.factory.created.get(0).releaseCount);
    }

    @Test
    public void restartSessionChangeReleaseAndShutdownDoNotLeakRoute() {
        Harness harness = new Harness();
        harness.start(47);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect first = harness.factory.created.get(0);

        harness.manager.onStop(harness.record);
        harness.manager.onStart(harness.record, 48);
        assertFalse(harness.manager.ownsRoute(harness.record));
        harness.scheduler.runReady();
        assertEquals(1, first.releaseCount);
        assertEquals(2, harness.factory.createCount);
        harness.replySuccess(1);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        FakeEffect second = harness.factory.created.get(1);
        harness.manager.onRelease(harness.record);
        assertFalse(harness.manager.ownsRoute(harness.record));
        harness.scheduler.runReady();
        assertEquals(1, second.releaseCount);

        Harness shutdown = new Harness();
        shutdown.start(49);
        shutdown.replySuccess(0);
        shutdown.scheduler.runReady();
        FakeEffect shutdownEffect = shutdown.factory.created.get(0);
        shutdown.manager.shutdown();
        assertFalse(shutdown.manager.ownsRoute(shutdown.record));
        assertEquals(0, shutdownEffect.releaseCount);
        shutdown.scheduler.runReady();
        assertEquals(1, shutdownEffect.releaseCount);
    }

    @Test
    public void envelopeValidationRejectsWrongBindingsAndFutureIssueTime() {
        byte[] nonce = new byte[16];
        Arrays.fill(nonce, (byte) 7);
        byte[] valid = envelope(50, 3L, nonce, 1_000L, 6_000L);

        assertEquals(
                6_000L,
                LegacyPreprocessorSessionManager.validateEnvelope(
                        valid, 50, 3L, nonce, 1_000L));
        assertEquals(
                -1L,
                LegacyPreprocessorSessionManager.validateEnvelope(
                        valid, 51, 3L, nonce, 1_000L));
        assertEquals(
                -1L,
                LegacyPreprocessorSessionManager.validateEnvelope(
                        valid, 50, 4L, nonce, 1_000L));
        byte[] wrongNonce = nonce.clone();
        wrongNonce[0]++;
        assertEquals(
                -1L,
                LegacyPreprocessorSessionManager.validateEnvelope(
                        valid, 50, 3L, wrongNonce, 1_000L));
        assertEquals(
                -1L,
                LegacyPreprocessorSessionManager.validateEnvelope(
                        envelope(50, 3L, nonce, 1_251L, 6_000L),
                        50,
                        3L,
                        nonce,
                        1_000L));
    }

    private static byte[] envelope(
            int sessionId,
            long generation,
            byte[] nonce,
            long issuedMs,
            long expiryMs) {
        byte[] process = "com.example.recorder".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] preset = "{\"engine\":{},\"modules\":[]}".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        int signedSize = 110 + process.length + preset.length;
        ByteBuffer buffer = ByteBuffer.allocate(signedSize + 2 + 64)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0x45434843);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        putUuid(buffer, LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID);
        buffer.putInt(sessionId);
        buffer.putInt(10_123);
        buffer.putLong(generation);
        buffer.putLong(issuedMs);
        buffer.putLong(expiryMs);
        buffer.put(nonce);
        buffer.put(new byte[32]);
        buffer.putShort((short) process.length);
        buffer.putInt(preset.length);
        buffer.put(process);
        buffer.put(preset);
        buffer.putShort((short) 64);
        buffer.put(new byte[64]);
        return buffer.array();
    }

    private static void putUuid(ByteBuffer buffer, UUID uuid) {
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
    }

    private static final class Harness {
        final Object record = new Object();
        final ManualClock clock = new ManualClock();
        final MutablePolicy policy = new MutablePolicy();
        final RecordingCapabilities capabilities = new RecordingCapabilities();
        final RecordingEffectFactory factory = new RecordingEffectFactory();
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        final ManualScheduler scheduler = new ManualScheduler(clock);
        final LegacyPreprocessorSessionManager.RouteLeases leases =
                new LegacyPreprocessorSessionManager.RouteLeases();
        final LegacyPreprocessorSessionManager manager = new LegacyPreprocessorSessionManager(
                policy,
                capabilities,
                factory,
                clock,
                diagnostics,
                scheduler,
                new FixedRandom(),
                leases);
        int directTransforms;

        void start(int sessionId) {
            manager.onInitialized(record, sessionId, true);
            manager.onStart(record, sessionId);
            scheduler.runReady();
        }

        void replySuccess(int requestIndex) {
            CapabilityRequest request = capabilities.requests.get(requestIndex);
            reply(
                    requestIndex,
                    0,
                    request.generation,
                    envelope(
                            request.sessionId,
                            request.generation,
                            request.nonce,
                            clock.nowMs,
                            clock.nowMs + 5_000L),
                    "accepted");
        }

        void reply(
                int requestIndex,
                int status,
                long generation,
                byte[] envelope,
                String diagnostic) {
            capabilities.requests.get(requestIndex).callback.onResult(
                    status, generation, envelope, diagnostic);
        }

        void runDirectRead() {
            if (!manager.ownsRoute(record)) {
                directTransforms++;
            }
        }

        void assertDirectFallback(String diagnostic) {
            assertFalse(manager.ownsRoute(record));
            runDirectRead();
            assertEquals(1, directTransforms);
            assertTrue(
                    "missing diagnostic " + diagnostic + " in " + diagnostics.codes,
                    diagnostics.codes.contains(diagnostic));
        }
    }

    private static final class MutablePolicy
            implements LegacyPreprocessorSessionManager.PolicyAccess {
        boolean eligible = true;
        long generation = 1L;
        int invalidations;

        @Override
        public LegacyPreprocessorSessionManager.Policy current() {
            return new LegacyPreprocessorSessionManager.Policy(eligible, generation);
        }

        @Override
        public void invalidateDirectPermits() {
            invalidations++;
        }
    }

    private static final class RecordingCapabilities
            implements LegacyPreprocessorSessionManager.CapabilityClient {
        final List<CapabilityRequest> requests = new ArrayList<>();
        boolean acceptRequests = true;

        @Override
        public boolean request(
                int sessionId,
                long generation,
                byte[] nonce,
                LegacyPreprocessorSessionManager.CapabilityCallback callback) {
            requests.add(new CapabilityRequest(sessionId, generation, nonce.clone(), callback));
            return acceptRequests;
        }
    }

    private static final class CapabilityRequest {
        final int sessionId;
        final long generation;
        final byte[] nonce;
        final LegacyPreprocessorSessionManager.CapabilityCallback callback;

        CapabilityRequest(
                int sessionId,
                long generation,
                byte[] nonce,
                LegacyPreprocessorSessionManager.CapabilityCallback callback) {
            this.sessionId = sessionId;
            this.generation = generation;
            this.nonce = nonce;
            this.callback = callback;
        }
    }

    private static final class RecordingEffectFactory
            implements LegacyPreprocessorSessionManager.EffectFactory {
        final List<FakeEffect> created = new ArrayList<>();
        FakeEffect next = new FakeEffect();
        Exception createError;
        int createCount;

        @Override
        public LegacyPreprocessorSessionManager.EffectHandle create(int sessionId)
                throws Exception {
            createCount++;
            if (createError != null) {
                throw createError;
            }
            FakeEffect effect = next;
            effect.sessionId = sessionId;
            created.add(effect);
            next = new FakeEffect();
            return effect;
        }
    }

    private static final class FakeEffect
            implements LegacyPreprocessorSessionManager.EffectHandle {
        LegacyPreprocessorSessionManager.Descriptor descriptor =
                new LegacyPreprocessorSessionManager.Descriptor(
                        LegacyPreprocessorSessionManager.TYPE_UUID,
                        LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID,
                        "Pre Processing");
        boolean hasControl = true;
        int authorizeStatus;
        int enableStatus;
        int revokeStatus;
        int disableStatus;
        int sessionId;
        int authorizeCount;
        int revokeCount;
        int enableCount;
        int disableCount;
        int releaseCount;
        byte[] lastAuthorizeParameter;
        Exception releaseError;

        @Override
        public LegacyPreprocessorSessionManager.Descriptor descriptor() {
            return descriptor;
        }

        @Override
        public boolean hasControl() {
            return hasControl;
        }

        @Override
        public int setParameter(byte[] parameter, byte[] value) {
            if (Arrays.equals(
                    parameter, LegacyPreprocessorSessionManager.AUTHORIZE_PARAMETER)) {
                authorizeCount++;
                lastAuthorizeParameter = parameter.clone();
                return authorizeStatus;
            }
            if (Arrays.equals(parameter, LegacyPreprocessorSessionManager.REVOKE_PARAMETER)) {
                revokeCount++;
                return revokeStatus;
            }
            throw new AssertionError("unexpected effect parameter");
        }

        @Override
        public int setEnabled(boolean enabled) {
            if (enabled) {
                enableCount++;
                return enableStatus;
            }
            disableCount++;
            return disableStatus;
        }

        @Override
        public void release() throws Exception {
            releaseCount++;
            if (releaseError != null) {
                throw releaseError;
            }
        }
    }

    private static final class RecordingDiagnostics
            implements LegacyPreprocessorSessionManager.Diagnostics {
        final List<String> codes = new ArrayList<>();

        @Override
        public void report(String code, Throwable error) {
            codes.add(code);
        }
    }

    private static final class ManualClock implements LegacyPreprocessorSessionManager.Clock {
        long nowMs = 1_000L;

        @Override
        public long boottimeMs() {
            return nowMs;
        }
    }

    private static final class FixedRandom extends SecureRandom {
        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i + 1);
            }
        }
    }

    private static final class ManualScheduler
            implements LegacyPreprocessorSessionManager.Scheduler {
        final ManualClock clock;
        final Deque<Runnable> ready = new ArrayDeque<>();
        final List<ManualFuture> delayed = new ArrayList<>();
        boolean rejectExecute;
        boolean rejectSchedule;
        boolean shutdown;

        ManualScheduler(ManualClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean execute(Runnable task) {
            if (rejectExecute) {
                return false;
            }
            ready.addLast(task);
            return true;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
            if (rejectSchedule) {
                return null;
            }
            ManualFuture future = new ManualFuture(
                    task, clock, clock.nowMs + Math.max(0L, delayMs));
            delayed.add(future);
            return future;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        void advance(long elapsedMs) {
            clock.nowMs += elapsedMs;
            runReady();
        }

        void runReady() {
            boolean progressed;
            do {
                progressed = false;
                for (int i = delayed.size() - 1; i >= 0; i--) {
                    ManualFuture future = delayed.get(i);
                    if (future.dueMs <= clock.nowMs) {
                        delayed.remove(i);
                        ready.addLast(future);
                        progressed = true;
                    }
                }
                Runnable task;
                while ((task = ready.pollFirst()) != null) {
                    task.run();
                    progressed = true;
                }
            } while (progressed && hasDueTask());
        }

        private boolean hasDueTask() {
            for (ManualFuture future : delayed) {
                if (future.dueMs <= clock.nowMs) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ManualFuture implements ScheduledFuture<Object>, Runnable {
        final Runnable task;
        final ManualClock clock;
        final long dueMs;
        boolean cancelled;
        boolean done;

        ManualFuture(Runnable task, ManualClock clock, long dueMs) {
            this.task = task;
            this.clock = clock;
            this.dueMs = dueMs;
        }

        @Override
        public void run() {
            if (!cancelled) {
                task.run();
            }
            done = true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(Math.max(0L, dueMs - clock.nowMs), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS),
                    other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            done = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}
