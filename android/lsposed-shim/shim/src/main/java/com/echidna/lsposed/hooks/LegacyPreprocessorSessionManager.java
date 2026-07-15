package com.echidna.lsposed.hooks;

import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Serial, fail-closed lifecycle for one explicitly attached legacy input preprocessor per record. */
final class LegacyPreprocessorSessionManager {

    static final UUID TYPE_UUID = UUID.fromString("c83e3db3-d4f5-5f2c-a095-8775c1edfc6d");
    static final UUID IMPLEMENTATION_UUID =
            UUID.fromString("3e66a36e-dee9-5d81-a0d6-49fc3b863530");
    static final byte[] AUTHORIZE_PARAMETER = {
            0x45, 0x43, 0x48, 0x50, 0x00, 0x02, 0x00, 0x01};
    static final byte[] REVOKE_PARAMETER = {
            0x45, 0x43, 0x48, 0x50, 0x00, 0x02, 0x00, 0x02};
    static final byte[] TELEMETRY_PARAMETER = {
            0x45, 0x43, 0x48, 0x54, 0x00, 0x01, 0x00, 0x01};
    static final byte[] REVOKE_VALUE = {0};

    private static final long HEALTH_CHECK_MS = 250L;
    private static final long CAPABILITY_TIMEOUT_MS = 1_000L;
    private static final long RENEWAL_HEADROOM_MS = 1_500L;
    private static final int MAX_SESSIONS = 64;
    private static final int MAX_PROCESS_BYTES = 255;
    private static final long MAX_PRESET_BYTES = 60L * 1024L;
    private static final int TELEMETRY_VALUE_BYTES = 48;
    private static final long UINT32_MASK = 0xffff_ffffL;

    interface PolicyAccess {
        Policy current();
        void invalidateDirectPermits();
    }

    static final class Policy {
        final boolean eligible;
        final long generation;

        Policy(boolean eligible, long generation) {
            this.eligible = eligible;
            this.generation = generation;
        }
    }

    interface CapabilityClient {
        boolean request(int sessionId, long generation, byte[] nonce, CapabilityCallback callback);
    }

    interface CapabilityCallback {
        void onResult(int status, long generation, byte[] envelope, String diagnostic);
        void onFailure(String diagnostic);
    }

    interface TelemetryClient {
        boolean report(
                int sessionId, long generation, byte[] capabilityNonce, byte[] snapshot);
    }

    interface EffectFactory {
        EffectHandle create(int sessionId) throws Exception;
    }

    interface EffectHandle {
        Descriptor descriptor() throws Exception;
        boolean hasControl() throws Exception;
        int setParameter(byte[] parameter, byte[] value) throws Exception;
        int getParameter(byte[] parameter, byte[] value) throws Exception;
        int setEnabled(boolean enabled) throws Exception;
        void release() throws Exception;
    }

    static final class Descriptor {
        final UUID type;
        final UUID implementation;
        final String connectMode;

        Descriptor(UUID type, UUID implementation, String connectMode) {
            this.type = type;
            this.implementation = implementation;
            this.connectMode = connectMode;
        }
    }

    interface Clock {
        long boottimeMs();
    }

    interface Diagnostics {
        void report(String code, Throwable error);
    }

    interface Scheduler {
        boolean execute(Runnable task);
        ScheduledFuture<?> schedule(Runnable task, long delayMs);
        void shutdown();
    }

    static final class RouteLeases {
        private final AtomicReferenceArray<Lease> entries =
                new AtomicReferenceArray<>(MAX_SESSIONS);

        boolean acquire(Object record, int sessionId) {
            for (int i = 0; i < entries.length(); i++) {
                Lease current = entries.get(i);
                if (current != null && current.record == record
                        && current.sessionId == sessionId) {
                    return true;
                }
            }
            release(record);
            Lease lease = new Lease(record, sessionId);
            for (int i = 0; i < entries.length(); i++) {
                if (entries.compareAndSet(i, null, lease)) {
                    return true;
                }
            }
            return false;
        }

        void release(Object record) {
            if (record == null) {
                return;
            }
            for (int i = 0; i < entries.length(); i++) {
                Lease lease = entries.get(i);
                if (lease != null && lease.record == record) {
                    entries.compareAndSet(i, lease, null);
                }
            }
        }

        boolean contains(Object record) {
            for (int i = 0; i < entries.length(); i++) {
                Lease lease = entries.get(i);
                if (lease != null && lease.record == record) {
                    return true;
                }
            }
            return false;
        }

        int session(Object record) {
            for (int i = 0; i < entries.length(); i++) {
                Lease lease = entries.get(i);
                if (lease != null && lease.record == record) {
                    return lease.sessionId;
                }
            }
            return 0;
        }

        private static final class Lease {
            final Object record;
            final int sessionId;

            Lease(Object record, int sessionId) {
                this.record = record;
                this.sessionId = sessionId;
            }
        }
    }

    private enum DesiredState { STARTED, STOPPED, RELEASED }

    private static final class Session {
        final Object record;
        int sessionId;
        long requestId;
        long generation;
        long expiryMs;
        EffectHandle effect;
        boolean enabled;
        boolean capabilityPending;
        boolean healthScheduled;
        long healthToken;
        long pendingGeneration;
        long requestDeadlineMs;
        TelemetrySnapshot telemetry;
        byte[] capabilityNonce;

        Session(Object record, int sessionId) {
            this.record = record;
            this.sessionId = sessionId;
        }
    }

    private final PolicyAccess policyAccess;
    private final CapabilityClient capabilityClient;
    private final TelemetryClient telemetryClient;
    private final EffectFactory effectFactory;
    private final Clock clock;
    private final Diagnostics diagnostics;
    private final Scheduler scheduler;
    private final SecureRandom random;
    private final RouteLeases leases;
    private final Map<Object, Session> sessions = new IdentityHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Object, DesiredState> desired =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closed;

    LegacyPreprocessorSessionManager(
            PolicyAccess policyAccess,
            CapabilityClient capabilityClient,
            TelemetryClient telemetryClient,
            EffectFactory effectFactory,
            Clock clock,
            Diagnostics diagnostics,
            Scheduler scheduler,
            SecureRandom random,
            RouteLeases leases) {
        this.policyAccess = policyAccess;
        this.capabilityClient = capabilityClient;
        this.telemetryClient = telemetryClient;
        this.effectFactory = effectFactory;
        this.clock = clock;
        this.diagnostics = diagnostics;
        this.scheduler = scheduler;
        this.random = random;
        this.leases = leases;
    }

    void onInitialized(Object record, int sessionId, boolean initialized) {
        if (closed || record == null || !initialized || sessionId <= 0) {
            return;
        }
        desired.putIfAbsent(record, DesiredState.STOPPED);
        submit(() -> observeSession(record, sessionId), "executor_saturated_init");
    }

    void onStart(Object record, int sessionId) {
        if (closed || record == null || sessionId <= 0) {
            return;
        }
        int leasedSession = leases.session(record);
        if (leasedSession > 0 && leasedSession != sessionId) {
            clearLease(record);
        }
        desired.put(record, DesiredState.STARTED);
        submit(() -> start(record, sessionId), "executor_saturated_start");
    }

    void onStop(Object record) {
        if (closed || record == null) {
            return;
        }
        desired.put(record, DesiredState.STOPPED);
        clearLease(record);
        submit(() -> stop(record, false), "executor_saturated_stop");
    }

    void onRelease(Object record) {
        if (closed || record == null) {
            return;
        }
        desired.put(record, DesiredState.RELEASED);
        clearLease(record);
        submit(() -> stop(record, true), "executor_saturated_release");
    }

    boolean ownsRoute(Object record) {
        return !closed && record != null && leases.contains(record);
    }

    void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        for (Object record : desired.keySet()) {
            clearLease(record);
        }
        if (!scheduler.execute(() -> {
            for (Session session : sessions.values()) {
                teardown(session);
            }
            sessions.clear();
            desired.clear();
        })) {
            diagnostics.report("executor_saturated_shutdown", null);
        }
        scheduler.shutdown();
    }

    private void observeSession(Object record, int sessionId) {
        Session current = sessions.get(record);
        if (current != null && current.sessionId != sessionId) {
            clearLease(record);
            teardown(current);
            sessions.remove(record);
        }
        if (sessions.size() < MAX_SESSIONS) {
            sessions.computeIfAbsent(record, ignored -> new Session(record, sessionId));
        } else if (!sessions.containsKey(record)) {
            desired.remove(record);
            diagnostics.report("session_registry_full", null);
        }
    }

    private void start(Object record, int sessionId) {
        if (desired.get(record) != DesiredState.STARTED) {
            return;
        }
        observeSession(record, sessionId);
        Session session = sessions.get(record);
        if (session == null) {
            return;
        }
        Policy policy = policyAccess.current();
        if (!policy.eligible || policy.generation <= 0L) {
            fallback(session, "policy_ineligible", null);
            return;
        }
        if (session.enabled && session.generation == policy.generation
                && session.expiryMs - clock.boottimeMs() > RENEWAL_HEADROOM_MS) {
            scheduleHealth(session);
            return;
        }
        if (session.enabled && session.generation != policy.generation) {
            clearLease(record);
            teardown(session);
        }
        try {
            if (session.effect == null) {
                session.effect = effectFactory.create(sessionId);
                validateEffect(session.effect);
            } else if (!session.effect.hasControl()) {
                throw new EffectFailure("effect_control_lost");
            }
        } catch (Throwable error) {
            fallback(session, codeFor(error, "effect_create_failed"), error);
            return;
        }
        requestCapability(session, policy.generation);
    }

    private void requestCapability(Session session, long generation) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        if (allZero(nonce)) {
            nonce[0] = 1;
        }
        long requestId = ++session.requestId;
        session.capabilityPending = true;
        session.pendingGeneration = generation;
        session.requestDeadlineMs = clock.boottimeMs() + CAPABILITY_TIMEOUT_MS;
        boolean requested = capabilityClient.request(
                session.sessionId,
                generation,
                nonce.clone(),
                new CapabilityCallback() {
                    @Override
                    public void onResult(
                            int status,
                            long callbackGeneration,
                            byte[] envelope,
                            String diagnostic) {
                        submit(
                                () -> applyCapability(
                                        session.record,
                                        requestId,
                                        generation,
                                        nonce,
                                        status,
                                        callbackGeneration,
                                        envelope,
                                        diagnostic),
                                "executor_saturated_callback");
                    }

                    @Override
                    public void onFailure(String diagnostic) {
                        submit(
                                () -> callbackFailed(session.record, requestId, diagnostic),
                                "executor_saturated_callback_failure");
                    }
                });
        if (!requested) {
            session.capabilityPending = false;
            fallback(session, "provider_unsupported", null);
        } else {
            scheduleHealth(session);
        }
    }

    private void applyCapability(
            Object record,
            long requestId,
            long requestedGeneration,
            byte[] nonce,
            int status,
            long callbackGeneration,
            byte[] envelope,
            String diagnostic) {
        Session session = sessions.get(record);
        if (session == null || session.requestId != requestId
                || desired.get(record) != DesiredState.STARTED) {
            return;
        }
        session.capabilityPending = false;
        Policy policy = policyAccess.current();
        if (!policy.eligible) {
            fallback(session, "capability_stale", null);
            return;
        }
        if (policy.generation != requestedGeneration) {
            fallback(session, "capability_stale", null);
            start(record, session.sessionId);
            return;
        }
        if (callbackGeneration != requestedGeneration) {
            fallback(session, "capability_stale", null);
            return;
        }
        if (status != 0) {
            fallback(session, "capability_" + safeDiagnostic(diagnostic), null);
            return;
        }
        long expiry = validateEnvelope(
                envelope,
                session.sessionId,
                requestedGeneration,
                nonce,
                clock.boottimeMs());
        if (expiry <= 0L) {
            fallback(session, "capability_invalid", null);
            return;
        }
        try {
            if (!session.effect.hasControl()) {
                throw new EffectFailure("effect_control_lost");
            }
            int parameterStatus = session.effect.setParameter(AUTHORIZE_PARAMETER, envelope);
            if (parameterStatus != 0) {
                throw new EffectFailure("effect_parameter_" + parameterStatus);
            }
            if (!session.enabled) {
                int enableStatus = session.effect.setEnabled(true);
                if (enableStatus != 0) {
                    throw new EffectFailure("effect_enable_" + enableStatus);
                }
                session.enabled = true;
            }
            if (!leases.acquire(record, session.sessionId)) {
                throw new EffectFailure("route_lease_full");
            }
            policyAccess.invalidateDirectPermits();
            session.generation = requestedGeneration;
            session.expiryMs = expiry;
            session.capabilityNonce = nonce.clone();
            session.pendingGeneration = 0L;
            session.requestDeadlineMs = 0L;
            scheduleHealth(session);
        } catch (Throwable error) {
            fallback(session, codeFor(error, "effect_apply_failed"), error);
        }
    }

    private void callbackFailed(Object record, long requestId, String diagnostic) {
        Session session = sessions.get(record);
        if (session != null && session.requestId == requestId) {
            session.capabilityPending = false;
            fallback(session, "provider_" + safeDiagnostic(diagnostic), null);
        }
    }

    private void scheduleHealth(Session session) {
        if (session.healthScheduled) {
            return;
        }
        long token = session.requestId;
        session.healthScheduled = true;
        session.healthToken = token;
        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            if (session.healthToken == token) {
                session.healthScheduled = false;
            }
            health(session.record, token);
        }, HEALTH_CHECK_MS);
        if (scheduled == null) {
            session.healthScheduled = false;
            session.healthToken = 0L;
            fallback(session, "executor_saturated_health", null);
        }
    }

    private void health(Object record, long token) {
        Session session = sessions.get(record);
        if (session == null || session.requestId != token) {
            return;
        }
        if (desired.get(record) != DesiredState.STARTED) {
            clearLease(record);
            stop(record, desired.get(record) == DesiredState.RELEASED);
            return;
        }
        Policy policy = policyAccess.current();
        long nowMs = clock.boottimeMs();
        try {
            if (session.effect == null || !session.effect.hasControl()) {
                throw new EffectFailure("effect_control_lost");
            }
        } catch (Throwable error) {
            fallback(session, codeFor(error, "effect_control_check_failed"), error);
            return;
        }
        if (session.enabled && leases.contains(record)
                && policy.eligible && policy.generation == session.generation) {
            if (nowMs >= session.expiryMs) {
                fallback(session, "capability_expired", null);
                return;
            }
            pollTelemetry(session);
        }
        if (session.capabilityPending) {
            if (!policy.eligible) {
                fallback(session, "capability_stale", null);
            } else if (policy.generation != session.pendingGeneration) {
                fallback(session, "capability_stale", null);
                start(record, session.sessionId);
            } else if (clock.boottimeMs() >= session.requestDeadlineMs) {
                fallback(session, "capability_timeout", null);
            } else {
                scheduleHealth(session);
            }
            return;
        }
        if (!policy.eligible || policy.generation != session.generation) {
            clearLease(record);
            teardown(session);
            if (policy.eligible && policy.generation > 0L) {
                start(record, session.sessionId);
            }
            return;
        }
        if (session.expiryMs - clock.boottimeMs() <= RENEWAL_HEADROOM_MS) {
            requestCapability(session, policy.generation);
        } else {
            scheduleHealth(session);
        }
    }

    private void stop(Object record, boolean release) {
        Session session = sessions.get(record);
        if (session != null) {
            teardown(session);
            if (release) {
                sessions.remove(record);
            }
        }
        if (release) {
            desired.remove(record);
        }
    }

    private void fallback(Session session, String code, Throwable error) {
        clearLease(session.record);
        teardown(session);
        diagnostics.report(code, error);
    }

    private void teardown(Session session) {
        EffectHandle effect = session.effect;
        session.requestId++;
        session.generation = 0L;
        session.expiryMs = 0L;
        session.enabled = false;
        session.capabilityPending = false;
        session.healthScheduled = false;
        session.healthToken = 0L;
        session.pendingGeneration = 0L;
        session.requestDeadlineMs = 0L;
        session.telemetry = null;
        session.capabilityNonce = null;
        session.effect = null;
        if (effect == null) {
            return;
        }
        try {
            int status = effect.setParameter(REVOKE_PARAMETER, REVOKE_VALUE);
            if (status != 0) {
                diagnostics.report("effect_revoke_" + status, null);
            }
        } catch (Throwable error) {
            diagnostics.report("effect_revoke_failed", error);
        }
        try {
            int status = effect.setEnabled(false);
            if (status != 0) {
                diagnostics.report("effect_disable_" + status, null);
            }
        } catch (Throwable error) {
            diagnostics.report("effect_disable_failed", error);
        }
        try {
            effect.release();
        } catch (Throwable error) {
            diagnostics.report("effect_release_failed", error);
        }
    }

    private void clearLease(Object record) {
        if (leases.contains(record)) {
            leases.release(record);
            policyAccess.invalidateDirectPermits();
        }
    }

    private void validateEffect(EffectHandle effect) throws Exception {
        Descriptor descriptor = effect.descriptor();
        if (descriptor == null || !TYPE_UUID.equals(descriptor.type)
                || !IMPLEMENTATION_UUID.equals(descriptor.implementation)) {
            throw new EffectFailure("effect_descriptor_mismatch");
        }
        if (!"Pre Processing".equals(descriptor.connectMode)) {
            throw new EffectFailure("effect_not_preprocessing");
        }
        if (!effect.hasControl()) {
            throw new EffectFailure("effect_no_control");
        }
    }

    private void pollTelemetry(Session session) {
        byte[] raw = new byte[TELEMETRY_VALUE_BYTES];
        try {
            int status = session.effect.getParameter(TELEMETRY_PARAMETER, raw);
            if (status != TELEMETRY_VALUE_BYTES) {
                diagnostics.report("telemetry_parameter_" + status, null);
                return;
            }
        } catch (Throwable error) {
            diagnostics.report("telemetry_read_failed", error);
            return;
        }
        TelemetrySnapshot snapshot = decodeTelemetry(raw);
        if (snapshot == null) {
            diagnostics.report("telemetry_invalid", null);
            return;
        }
        if (snapshot.sessionId != session.sessionId) {
            diagnostics.report("telemetry_wrong_session", null);
            return;
        }
        if (snapshot.generation != session.generation) {
            diagnostics.report("telemetry_wrong_generation", null);
            return;
        }
        TelemetrySnapshot previous = session.telemetry;
        if (previous != null) {
            if (!newerUint32(snapshot.sequence, previous.sequence)) {
                diagnostics.report("telemetry_stale_sequence", null);
                return;
            }
            if (!forwardUint32(snapshot.blocks, previous.blocks)
                    || !forwardUint32(snapshot.frames, previous.frames)
                    || !forwardUint32(snapshot.failures, previous.failures)
                    || !forwardUint32(snapshot.mutations, previous.mutations)) {
                diagnostics.report("telemetry_counter_rollback", null);
                return;
            }
        }
        byte[] capabilityNonce = session.capabilityNonce;
        if (capabilityNonce == null || capabilityNonce.length != 16) {
            diagnostics.report("telemetry_capability_missing", null);
            return;
        }
        if (!telemetryClient.report(
                session.sessionId, session.generation, capabilityNonce, raw)) {
            diagnostics.report("telemetry_provider_unavailable", null);
            return;
        }
        session.telemetry = snapshot;
    }

    static TelemetrySnapshot decodeTelemetry(byte[] raw) {
        if (raw == null || raw.length != TELEMETRY_VALUE_BYTES) {
            return null;
        }
        ByteBuffer value = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        if (value.getInt() != 0x45434854 || value.getShort() != 1 || value.getShort() != 1
                || value.getShort() != TELEMETRY_VALUE_BYTES) {
            return null;
        }
        int flags = Short.toUnsignedInt(value.getShort());
        int sessionId = value.getInt();
        long generation = value.getLong();
        long sequence = Integer.toUnsignedLong(value.getInt());
        long blocks = Integer.toUnsignedLong(value.getInt());
        long frames = Integer.toUnsignedLong(value.getInt());
        long failures = Integer.toUnsignedLong(value.getInt());
        long mutations = Integer.toUnsignedLong(value.getInt());
        if ((flags & ~7) != 0 || generation <= 0L || value.getInt() != 0) {
            return null;
        }
        return new TelemetrySnapshot(
                sessionId, generation, sequence, flags, blocks, frames, failures, mutations);
    }

    private static boolean newerUint32(long next, long previous) {
        long distance = (next - previous) & UINT32_MASK;
        return distance >= 1L && distance <= 0x7fff_ffffL;
    }

    private static boolean forwardUint32(long next, long previous) {
        return ((next - previous) & UINT32_MASK) <= 0x7fff_ffffL;
    }

    static final class TelemetrySnapshot {
        final int sessionId;
        final long generation;
        final long sequence;
        final int flags;
        final long blocks;
        final long frames;
        final long failures;
        final long mutations;

        TelemetrySnapshot(
                int sessionId,
                long generation,
                long sequence,
                int flags,
                long blocks,
                long frames,
                long failures,
                long mutations) {
            this.sessionId = sessionId;
            this.generation = generation;
            this.sequence = sequence;
            this.flags = flags;
            this.blocks = blocks;
            this.frames = frames;
            this.failures = failures;
            this.mutations = mutations;
        }
    }

    private boolean submit(Runnable task, String failureCode) {
        if (closed || !scheduler.execute(task)) {
            // A live lease must remain authoritative until the serial worker revokes/disables the
            // effect. Clearing it here would let the Java fallback overlap an enabled effect.
            diagnostics.report(failureCode, null);
            return false;
        }
        return true;
    }

    static long validateEnvelope(
            byte[] envelope,
            int expectedSession,
            long expectedGeneration,
            byte[] expectedNonce,
            long nowMs) {
        if (envelope == null || envelope.length < 178 || expectedNonce == null
                || expectedNonce.length != 16) {
            return -1L;
        }
        ByteBuffer body = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN);
        if (body.getInt(0) != 0x45434843 || body.getShort(4) != 1 || body.getShort(6) != 1
                || !uuidMatches(envelope, 8, IMPLEMENTATION_UUID)
                || body.getInt(24) != expectedSession
                || body.getLong(32) != expectedGeneration
                || !Arrays.equals(expectedNonce, Arrays.copyOfRange(envelope, 56, 72))) {
            return -1L;
        }
        long issued = body.getLong(40);
        long expiry = body.getLong(48);
        int processSize = Short.toUnsignedInt(body.getShort(104));
        long presetSize = Integer.toUnsignedLong(body.getInt(106));
        long signedSize = 110L + processSize + presetSize;
        if (issued < 0L || issued > nowMs + HEALTH_CHECK_MS
                || expiry <= issued || expiry - issued > 5_000L
                || expiry <= nowMs || processSize < 1 || processSize > MAX_PROCESS_BYTES
                || presetSize < 1L || presetSize > MAX_PRESET_BYTES
                || signedSize + 2L > envelope.length) {
            return -1L;
        }
        int signatureSize = Short.toUnsignedInt(body.getShort((int) signedSize));
        if (signatureSize < 64 || signatureSize > 80
                || signedSize + 2L + signatureSize != envelope.length) {
            return -1L;
        }
        return expiry;
    }

    private static boolean uuidMatches(byte[] bytes, int offset, UUID uuid) {
        ByteBuffer value = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.BIG_ENDIAN);
        return value.getLong() == uuid.getMostSignificantBits()
                && value.getLong() == uuid.getLeastSignificantBits();
    }

    private static boolean allZero(byte[] value) {
        int combined = 0;
        for (byte item : value) {
            combined |= item;
        }
        return combined == 0;
    }

    private static String safeDiagnostic(String value) {
        return value != null && value.matches("[a-z0-9_]{1,64}") ? value : "failed";
    }

    private static String codeFor(Throwable error, String fallback) {
        return error instanceof EffectFailure ? error.getMessage() : fallback;
    }

    static final class EffectFailure extends Exception {
        EffectFailure(String code) {
            super(code);
        }

        EffectFailure(String code, Throwable cause) {
            super(code, cause);
        }
    }

    static final class DefaultScheduler implements Scheduler {
        private final ScheduledThreadPoolExecutor executor;
        private final AtomicInteger pending = new AtomicInteger();
        private final int maximumPending;

        DefaultScheduler(int maximumPending) {
            this.maximumPending = maximumPending;
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "echidna-preprocessor-session");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        @Override
        public boolean execute(Runnable task) {
            if (!reserve()) {
                return false;
            }
            try {
                executor.execute(wrap(task));
                return true;
            } catch (RejectedExecutionException error) {
                pending.decrementAndGet();
                return false;
            }
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
            if (!reserve()) {
                return null;
            }
            try {
                return executor.schedule(wrap(task), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException error) {
                pending.decrementAndGet();
                return null;
            }
        }

        @Override
        public void shutdown() {
            executor.shutdown();
        }

        private boolean reserve() {
            while (true) {
                int current = pending.get();
                if (current >= maximumPending) {
                    return false;
                }
                if (pending.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private Runnable wrap(Runnable task) {
            return () -> {
                try {
                    task.run();
                } finally {
                    pending.decrementAndGet();
                }
            };
        }
    }

    interface EffectReflection {
        EffectHandle create(UUID type, UUID implementation, int sessionId) throws Exception;
    }

    /** API26+ hidden AudioEffect boundary. No framework object is touched on hook threads. */
    static final class ReflectionEffectFactory implements EffectFactory {
        private final int sdkInt;
        private final EffectReflection reflection;

        ReflectionEffectFactory() {
            this(Build.VERSION.SDK_INT, new AndroidEffectReflection());
        }

        ReflectionEffectFactory(int sdkInt, EffectReflection reflection) {
            this.sdkInt = sdkInt;
            this.reflection = reflection;
        }

        @Override
        public EffectHandle create(int sessionId) throws Exception {
            if (sdkInt < 26) {
                throw new EffectFailure("effect_api_unsupported");
            }
            try {
                return reflection.create(TYPE_UUID, IMPLEMENTATION_UUID, sessionId);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof IllegalArgumentException
                        || cause instanceof UnsupportedOperationException) {
                    throw new EffectFailure("effect_unregistered", cause);
                }
                throw error;
            }
        }
    }

    private static final class AndroidEffectReflection implements EffectReflection {
        @Override
        public EffectHandle create(UUID type, UUID implementation, int sessionId) throws Exception {
            Class<?> effectClass = Class.forName("android.media.audiofx.AudioEffect");
            Constructor<?> constructor = effectClass.getDeclaredConstructor(
                    UUID.class, UUID.class, int.class, int.class);
            constructor.setAccessible(true);
            Object effect = constructor.newInstance(type, implementation, 0, sessionId);
            return new ReflectionEffectHandle(effectClass, effect);
        }
    }

    private static final class ReflectionEffectHandle implements EffectHandle {
        private final Object effect;
        private final Method getDescriptor;
        private final Method hasControl;
        private final Method setParameter;
        private final Method getParameter;
        private final Method setEnabled;
        private final Method release;

        ReflectionEffectHandle(Class<?> effectClass, Object effect) throws Exception {
            this.effect = effect;
            getDescriptor = effectClass.getMethod("getDescriptor");
            hasControl = effectClass.getMethod("hasControl");
            setParameter = effectClass.getDeclaredMethod("setParameter", byte[].class, byte[].class);
            setParameter.setAccessible(true);
            getParameter = effectClass.getDeclaredMethod("getParameter", byte[].class, byte[].class);
            getParameter.setAccessible(true);
            setEnabled = effectClass.getMethod("setEnabled", boolean.class);
            release = effectClass.getMethod("release");
        }

        @Override
        public Descriptor descriptor() throws Exception {
            Object descriptor = getDescriptor.invoke(effect);
            Class<?> type = descriptor.getClass();
            Field typeField = type.getField("type");
            Field uuidField = type.getField("uuid");
            Field modeField = type.getField("connectMode");
            return new Descriptor(
                    (UUID) typeField.get(descriptor),
                    (UUID) uuidField.get(descriptor),
                    (String) modeField.get(descriptor));
        }

        @Override
        public boolean hasControl() throws Exception {
            return (Boolean) hasControl.invoke(effect);
        }

        @Override
        public int setParameter(byte[] parameter, byte[] value) throws Exception {
            return (Integer) setParameter.invoke(effect, parameter, value);
        }

        @Override
        public int getParameter(byte[] parameter, byte[] value) throws Exception {
            return (Integer) getParameter.invoke(effect, parameter, value);
        }

        @Override
        public int setEnabled(boolean enabled) throws Exception {
            return (Integer) setEnabled.invoke(effect, enabled);
        }

        @Override
        public void release() throws Exception {
            release.invoke(effect);
        }
    }
}
