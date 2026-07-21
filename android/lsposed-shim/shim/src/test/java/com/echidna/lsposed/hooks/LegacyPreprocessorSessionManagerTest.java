package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
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
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertEquals(0, effect.releaseCount);
        harness.runDirectRead();
        assertEquals(2, harness.directTransforms);

        harness.scheduler.runReady();
        assertEquals(1, effect.revokeCount);
        assertEquals(1, effect.disableCount);
        assertEquals(1, effect.releaseCount);
        assertFalse(harness.manager.ownsRoute(harness.record));
        harness.runDirectRead();
        assertEquals(3, harness.directTransforms);
    }

    @Test
    public void routeSuppressionPrecedesEnableAndOutlivesEveryTeardownStep() {
        Harness harness = new Harness();
        harness.start(40);
        FakeEffect effect = harness.factory.created.get(0);
        List<String> teardownOrder = new ArrayList<>();
        effect.beforeAuthorize = () -> {
            assertTrue(harness.manager.ownsRoute(harness.record));
            assertTrue(harness.policy.invalidations > 0);
            harness.runDirectRead();
        };
        effect.beforeEnable = () -> {
            assertTrue(harness.manager.ownsRoute(harness.record));
            harness.runDirectRead();
        };

        harness.replySuccess(0);
        harness.scheduler.runReady();

        assertEquals(0, harness.directTransforms);
        effect.beforeRevoke = () -> {
            assertTrue(harness.manager.ownsRoute(harness.record));
            teardownOrder.add("revoke");
        };
        effect.beforeDisable = () -> {
            assertTrue(harness.manager.ownsRoute(harness.record));
            teardownOrder.add("disable");
        };
        effect.beforeRelease = () -> {
            assertTrue(harness.manager.ownsRoute(harness.record));
            teardownOrder.add("release");
        };

        harness.manager.onStop(harness.record);
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertEquals(0, effect.revokeCount);
        assertEquals(0, effect.disableCount);
        assertEquals(0, effect.releaseCount);
        harness.scheduler.runReady();

        assertEquals(Arrays.asList("revoke", "disable", "release"), teardownOrder);
        assertFalse(harness.manager.ownsRoute(harness.record));
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
    public void api34RegisteredLegacyEffectUsesRuntimeDescriptorAndControlEvidence() {
        FakeEffect effect = new FakeEffect();
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        34,
                        (type, implementation, sessionId) -> {
                            assertEquals(LegacyPreprocessorSessionManager.TYPE_UUID, type);
                            assertEquals(
                                    LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID,
                                    implementation);
                            effect.sessionId = sessionId;
                            return effect;
                        });
        Harness harness = new Harness(factory);

        harness.start(84);
        assertEquals(1, harness.capabilities.requests.size());
        harness.replySuccess(0);
        harness.scheduler.runReady();

        assertTrue(harness.manager.ownsRoute(harness.record));
        assertEquals(84, effect.sessionId);
        assertEquals(1, effect.authorizeCount);
        assertEquals(1, effect.enableCount);
    }

    @Test
    public void api34StableAidlOnlyEffectUnavailabilityKeepsDirectRoute() {
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        34,
                        (type, implementation, sessionId) -> {
                            throw new InvocationTargetException(
                                    new IllegalArgumentException("legacy effect is unregistered"));
                        });
        Harness harness = new Harness(factory);

        harness.start(85);

        harness.assertDirectFallback("effect_unregistered");
        assertTrue(harness.capabilities.requests.isEmpty());
    }

    @Test
    public void futureSdkReflectionFailureKeepsDirectRouteWithoutUpperSdkCutoff() {
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        10_000,
                        (type, implementation, sessionId) -> {
                            throw new NoSuchMethodException("AudioEffect constructor changed");
                        });
        Harness harness = new Harness(factory);

        harness.start(86);

        harness.assertDirectFallback("effect_create_failed");
        assertTrue(harness.capabilities.requests.isEmpty());
    }

    @Test
    public void preApi26StillFailsClosedBeforeReflection() {
        int[] reflectionCalls = {0};
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        25,
                        (type, implementation, sessionId) -> {
                            reflectionCalls[0]++;
                            return new FakeEffect();
                        });
        Harness harness = new Harness(factory);

        harness.start(87);

        harness.assertDirectFallback("effect_api_unsupported");
        assertEquals(0, reflectionCalls[0]);
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
            assertTrue(full.leases.acquire(new Object(), 100 + i, 1L));
        }
        full.start(44);
        full.replySuccess(0);
        full.scheduler.runReady();
        full.assertDirectFallback("route_lease_full");
        assertEquals(0, full.factory.created.get(0).authorizeCount);
        assertEquals(0, full.factory.created.get(0).enableCount);
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
    public void controlLossRestoresDirectRouteButUncertainTeardownQuarantinesIt() {
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
        assertTrue(teardown.manager.ownsRoute(teardown.record));
        teardown.runDirectRead();
        teardown.scheduler.runReady();
        assertTrue(teardown.manager.ownsRoute(teardown.record));
        assertTrue(teardown.leases.isQuarantined(teardown.record));
        assertEquals(0, teardown.directTransforms);
        assertTrue(teardown.diagnostics.codes.contains("effect_revoke_-5"));
        assertTrue(teardown.diagnostics.codes.contains("effect_disable_-6"));
        assertTrue(teardown.diagnostics.codes.contains("effect_release_failed"));
        assertTrue(teardown.diagnostics.codes.contains("route_quarantined"));
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

        Harness stopRejected = new Harness();
        stopRejected.start(46);
        stopRejected.replySuccess(0);
        stopRejected.scheduler.runReady();
        FakeEffect liveEffect = stopRejected.factory.created.get(0);
        stopRejected.scheduler.rejectExecute = true;
        stopRejected.manager.onStop(stopRejected.record);
        assertTrue(stopRejected.manager.ownsRoute(stopRejected.record));
        assertTrue(stopRejected.leases.isQuarantined(stopRejected.record));
        assertEquals(0, liveEffect.releaseCount);
        stopRejected.runDirectRead();
        assertEquals(0, stopRejected.directTransforms);
        assertTrue(stopRejected.diagnostics.codes.contains("executor_saturated_stop"));

        stopRejected.scheduler.rejectExecute = false;
        stopRejected.scheduler.advance(250L);
        assertEquals(1, liveEffect.releaseCount);
        assertFalse(stopRejected.manager.ownsRoute(stopRejected.record));
    }

    @Test
    public void captureOwnerDrainAcknowledgesOnlyAfterEverySessionIsReleased() {
        Harness harness = new Harness();
        Object secondRecord = new Object();

        harness.start(71);
        harness.manager.onInitialized(secondRecord, 72, true);
        harness.manager.onStart(secondRecord, 72);
        harness.scheduler.runReady();
        assertEquals(2, harness.capabilities.requests.size());

        harness.replySuccess(0);
        harness.replySuccess(1);
        harness.scheduler.runReady();
        FakeEffect firstEffect = harness.factory.created.get(0);
        FakeEffect secondEffect = harness.factory.created.get(1);
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertTrue(harness.manager.ownsRoute(secondRecord));

        List<Long> acknowledged = new ArrayList<>();
        List<Long> acknowledgedTokens = new ArrayList<>();
        harness.manager.requestDrain(21L, 101L, (generation, handoffToken) -> {
            assertFalse(harness.manager.ownsRoute(harness.record));
            assertFalse(harness.manager.ownsRoute(secondRecord));
            assertEquals(1, firstEffect.revokeCount);
            assertEquals(1, firstEffect.disableCount);
            assertEquals(1, firstEffect.releaseCount);
            assertEquals(1, secondEffect.revokeCount);
            assertEquals(1, secondEffect.disableCount);
            assertEquals(1, secondEffect.releaseCount);
            acknowledged.add(generation);
            acknowledgedTokens.add(handoffToken);
        });

        assertTrue(acknowledged.isEmpty());
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertTrue(harness.manager.ownsRoute(secondRecord));
        harness.scheduler.runReady();

        assertEquals(Arrays.asList(21L), acknowledged);
        assertEquals(Arrays.asList(101L), acknowledgedTokens);
        harness.scheduler.advance(250L);
        assertEquals(2, harness.capabilities.requests.size());
    }

    @Test
    public void captureOwnerDrainSchedulerRejectionQuarantinesWithoutAcknowledging() {
        Harness harness = new Harness();
        harness.start(73);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);
        List<Long> acknowledged = new ArrayList<>();

        harness.scheduler.rejectExecute = true;
        harness.manager.requestDrain(22L, 102L, (generation, token) ->
                acknowledged.add(generation));

        assertTrue(acknowledged.isEmpty());
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertTrue(harness.leases.isQuarantined(harness.record));
        assertEquals(0, effect.releaseCount);
        assertTrue(harness.diagnostics.codes.contains("executor_saturated_drain"));
    }

    @Test
    public void captureOwnerDrainFailedTeardownQuarantinesWithoutAcknowledging() {
        Harness harness = new Harness();
        harness.start(74);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);
        effect.revokeStatus = -5;
        effect.disableStatus = -6;
        effect.releaseError = new Exception("release failed");
        List<Long> acknowledged = new ArrayList<>();

        harness.manager.requestDrain(23L, 103L, (generation, token) ->
                acknowledged.add(generation));
        harness.scheduler.runReady();

        assertTrue(acknowledged.isEmpty());
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertTrue(harness.leases.isQuarantined(harness.record));
        assertEquals(1, effect.revokeCount);
        assertEquals(1, effect.disableCount);
        assertEquals(1, effect.releaseCount);
        assertTrue(harness.diagnostics.codes.contains("effect_revoke_-5"));
        assertTrue(harness.diagnostics.codes.contains("effect_disable_-6"));
        assertTrue(harness.diagnostics.codes.contains("effect_release_failed"));
        assertTrue(harness.diagnostics.codes.contains("route_quarantined"));
    }

    @Test
    public void rapidCaptureOwnerRevokesKeepNewestTokenBoundRequest() {
        Harness harness = new Harness();
        List<Long> acknowledged = new ArrayList<>();
        List<Long> acknowledgedTokens = new ArrayList<>();

        LegacyPreprocessorSessionManager.DrainCallback callback = (generation, handoffToken) -> {
            acknowledged.add(generation);
            acknowledgedTokens.add(handoffToken);
        };
        harness.manager.requestDrain(30L, 110L, callback);
        harness.manager.requestDrain(32L, 111L, callback);
        harness.manager.requestDrain(31L, 112L, callback);
        assertTrue(acknowledged.isEmpty());
        assertEquals(1, harness.scheduler.ready.size());

        harness.scheduler.runReady();
        assertEquals(Arrays.asList(31L), acknowledged);
        assertEquals(Arrays.asList(112L), acknowledgedTokens);

        harness.manager.requestDrain(33L, 113L, callback);
        harness.manager.requestDrain(35L, 114L, callback);
        harness.manager.requestDrain(34L, 115L, callback);
        harness.scheduler.runReady();
        assertEquals(Arrays.asList(31L, 34L), acknowledged);
        assertEquals(Arrays.asList(112L, 115L), acknowledgedTokens);
    }

    @Test
    public void disconnectDrainCannotOverwriteTokenBoundAcknowledgement() {
        Harness harness = new Harness();
        List<Long> generations = new ArrayList<>();
        List<Long> tokens = new ArrayList<>();

        harness.manager.requestDrain(40L, 120L, (generation, handoffToken) -> {
            generations.add(generation);
            tokens.add(handoffToken);
        });
        harness.manager.requestDrain(40L, 0L, null);
        harness.manager.requestDrain(41L, 0L, null);
        harness.scheduler.runReady();

        assertEquals(Arrays.asList(40L), generations);
        assertEquals(Arrays.asList(120L), tokens);
    }

    @Test
    public void newBindingCallbackReplacesQueuedSameGenerationIncarnation() {
        Harness harness = new Harness();
        List<Long> tokens = new ArrayList<>();

        harness.manager.requestDrain(42L, 900L, (generation, token) -> tokens.add(token));
        // A restarted service owns a new Binder epoch and may restart its local transition token.
        harness.manager.requestDrain(42L, 1L, (generation, token) -> tokens.add(token));
        harness.scheduler.runReady();

        assertEquals(Arrays.asList(1L), tokens);
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
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertEquals(0, first.releaseCount);
        harness.scheduler.runReady();
        assertEquals(1, first.releaseCount);
        assertEquals(2, harness.factory.createCount);
        harness.replySuccess(1);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        FakeEffect second = harness.factory.created.get(1);
        harness.manager.onRelease(harness.record);
        assertTrue(harness.manager.ownsRoute(harness.record));
        assertEquals(0, second.releaseCount);
        harness.scheduler.runReady();
        assertEquals(1, second.releaseCount);
        assertFalse(harness.manager.ownsRoute(harness.record));

        Harness shutdown = new Harness();
        shutdown.start(49);
        shutdown.replySuccess(0);
        shutdown.scheduler.runReady();
        FakeEffect shutdownEffect = shutdown.factory.created.get(0);
        shutdown.manager.shutdown();
        assertTrue(shutdown.manager.ownsRoute(shutdown.record));
        assertEquals(0, shutdownEffect.releaseCount);
        shutdown.scheduler.runReady();
        assertEquals(1, shutdownEffect.releaseCount);
        assertFalse(shutdown.manager.ownsRoute(shutdown.record));
    }

    @Test
    public void telemetryPollsAtFourHertzOnlyForActiveCapabilityLease() {
        Harness harness = new Harness();
        harness.start(61);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);

        harness.scheduler.advance(249L);
        assertEquals(0, effect.telemetryReadCount);
        harness.scheduler.advance(1L);
        assertEquals(1, effect.telemetryReadCount);
        assertEquals(1, harness.telemetry.snapshots.size());
        assertArrayEquals(
                harness.capabilities.requests.get(0).nonce,
                harness.telemetry.capabilityNonces.get(0));
        LegacyPreprocessorSessionManager.TelemetrySnapshot reported =
                LegacyPreprocessorSessionManager.decodeTelemetry(
                        harness.telemetry.snapshots.get(0));
        assertNotNull(reported);
        assertEquals(61, reported.sessionId);
        assertEquals(1L, reported.generation);

        harness.manager.onStop(harness.record);
        harness.scheduler.advance(1_000L);
        assertEquals(1, effect.telemetryReadCount);
        assertEquals(1, effect.releaseCount);

        Harness revoked = new Harness();
        revoked.start(62);
        revoked.replySuccess(0);
        revoked.scheduler.runReady();
        FakeEffect revokedEffect = revoked.factory.created.get(0);
        revoked.policy.eligible = false;
        revoked.scheduler.advance(250L);
        assertEquals(0, revokedEffect.telemetryReadCount);
        assertEquals(1, revokedEffect.revokeCount);

        Harness expired = new Harness();
        expired.start(63);
        expired.replySuccess(0);
        expired.scheduler.runReady();
        FakeEffect expiredEffect = expired.factory.created.get(0);
        expired.clock.nowMs += 5_000L;
        expired.scheduler.runReady();
        assertEquals(0, expiredEffect.telemetryReadCount);
        assertTrue(expired.diagnostics.codes.contains("capability_expired"));
        assertEquals(1, expiredEffect.releaseCount);
    }

    @Test
    public void telemetryQueriesNonceBoundV2FirstAndForwardsTheRawProof() {
        Harness harness = new Harness();
        harness.start(69);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);
        byte[] proof = new byte[112];
        for (int index = 0; index < proof.length; index++) {
            proof[index] = (byte) (index + 1);
        }
        effect.proofStatus = 112;
        effect.proofValues.add(proof);

        harness.scheduler.advance(250L);

        assertEquals(1, effect.proofReadCount);
        assertEquals(0, effect.telemetryReadCount);
        assertEquals(Arrays.asList("proof"), effect.parameterQueries);
        assertArrayEquals(
                LegacyPreprocessorSessionManager.TELEMETRY_PROOF_PARAMETER,
                Arrays.copyOf(effect.lastProofParameter, 8));
        assertArrayEquals(
                harness.capabilities.requests.get(0).nonce,
                Arrays.copyOfRange(effect.lastProofParameter, 8, 24));
        assertEquals(1, harness.telemetry.snapshots.size());
        assertArrayEquals(proof, harness.telemetry.snapshots.get(0));
    }

    @Test
    public void unavailableV2FallsBackOnlyToCallerAttestedDiagnostics() {
        Harness harness = new Harness();
        harness.start(70);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);
        effect.proofStatus = -126;
        effect.telemetryOverride = telemetry(70, 1L, 1L, 3, 1L, 4L, 0L, 1L);

        harness.scheduler.advance(250L);

        assertEquals(Arrays.asList("proof", "diagnostic"), effect.parameterQueries);
        assertEquals(1, effect.proofReadCount);
        assertEquals(1, effect.telemetryReadCount);
        assertEquals(48, harness.telemetry.snapshots.get(0).length);
        assertTrue(harness.diagnostics.codes.contains("telemetry_proof_parameter_-126"));

        effect.proofError = new Exception("proof read failed");
        harness.scheduler.advance(250L);
        assertTrue(harness.diagnostics.codes.contains("telemetry_proof_read_failed"));
        assertEquals(2, effect.telemetryReadCount);
    }

    @Test
    public void sameSessionEffectRecreationReportsTheNewAcceptedCapabilityNonce() {
        Harness harness = new Harness();
        harness.start(68);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        harness.scheduler.advance(250L);
        byte[] firstNonce = harness.telemetry.capabilityNonces.get(0);

        harness.manager.onStop(harness.record);
        harness.scheduler.runReady();
        harness.manager.onStart(harness.record, 68);
        harness.scheduler.runReady();
        assertEquals(2, harness.capabilities.requests.size());
        harness.replySuccess(1);
        harness.scheduler.runReady();
        harness.scheduler.advance(250L);

        assertEquals(2, harness.telemetry.capabilityNonces.size());
        byte[] secondNonce = harness.telemetry.capabilityNonces.get(1);
        assertFalse(Arrays.equals(firstNonce, secondNonce));
        assertArrayEquals(harness.capabilities.requests.get(1).nonce, secondNonce);
    }

    @Test
    public void telemetryRejectsReadFailuresWrongBindingsAndMalformedValues() {
        Harness harness = new Harness();
        harness.start(64);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);

        effect.telemetryStatus = -5;
        harness.scheduler.advance(250L);
        effect.telemetryStatus = 48;
        effect.telemetryError = new Exception("getParameter failed");
        harness.scheduler.advance(250L);
        effect.telemetryError = null;
        effect.telemetryValues.add(new byte[48]);
        effect.telemetryValues.add(telemetry(65, 1L, 1L, 3, 0, 0, 0, 0));
        effect.telemetryValues.add(telemetry(64, 2L, 1L, 3, 0, 0, 0, 0));
        effect.telemetryValues.add(telemetry(64, 1L, 1L, 3, 0, 0, 0, 0));
        harness.scheduler.advance(250L);
        harness.scheduler.advance(250L);
        harness.scheduler.advance(250L);
        harness.scheduler.advance(250L);

        assertEquals(1, harness.telemetry.snapshots.size());
        assertTrue(harness.diagnostics.codes.contains("telemetry_parameter_-5"));
        assertTrue(harness.diagnostics.codes.contains("telemetry_read_failed"));
        assertTrue(harness.diagnostics.codes.contains("telemetry_invalid"));
        assertTrue(harness.diagnostics.codes.contains("telemetry_wrong_session"));
        assertTrue(harness.diagnostics.codes.contains("telemetry_wrong_generation"));
        assertTrue(harness.manager.ownsRoute(harness.record));
    }

    @Test
    public void telemetrySequenceAndCountersAreMonotonicAcrossUint32Wrap() {
        Harness harness = new Harness();
        harness.start(66);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        FakeEffect effect = harness.factory.created.get(0);
        effect.telemetryValues.add(telemetry(
                66, 1L, 0xffff_ffffL, 3,
                0xffff_ffffL, 0xffff_ffffL, 0xffff_ffffL, 0xffff_ffffL));
        effect.telemetryValues.add(telemetry(66, 1L, 0L, 3, 0L, 0L, 0L, 0L));
        effect.telemetryValues.add(telemetry(66, 1L, 0L, 3, 0L, 0L, 0L, 0L));
        effect.telemetryValues.add(telemetry(
                66, 1L, 1L, 3, 0xffff_ffffL, 0L, 0L, 0L));

        harness.scheduler.advance(250L);
        harness.scheduler.advance(250L);
        assertEquals(2, harness.telemetry.snapshots.size());
        harness.scheduler.advance(250L);
        harness.scheduler.advance(250L);
        assertEquals(2, harness.telemetry.snapshots.size());
        assertTrue(harness.diagnostics.codes.contains("telemetry_stale_sequence"));
        assertTrue(harness.diagnostics.codes.contains("telemetry_counter_rollback"));

        harness.telemetry.accept = false;
        effect.telemetryValues.add(telemetry(66, 1L, 2L, 3, 1L, 1L, 0L, 0L));
        harness.scheduler.advance(250L);
        assertTrue(harness.diagnostics.codes.contains("telemetry_provider_unavailable"));
        assertTrue(harness.manager.ownsRoute(harness.record));
    }

    @Test
    public void telemetryDecoderRequiresExactCommittedEchtSchema() {
        byte[] valid = telemetry(67, 9L, 4L, 7, 8L, 9L, 10L, 11L);
        LegacyPreprocessorSessionManager.TelemetrySnapshot snapshot =
                LegacyPreprocessorSessionManager.decodeTelemetry(valid);
        assertNotNull(snapshot);
        assertEquals(67, snapshot.sessionId);
        assertEquals(9L, snapshot.generation);
        assertEquals(4L, snapshot.sequence);
        assertEquals(7, snapshot.flags);
        assertEquals(11L, snapshot.mutations);

        for (int offset : new int[] {0, 4, 6, 8, 44}) {
            byte[] malformed = valid.clone();
            malformed[offset] ^= 1;
            assertEquals(null, LegacyPreprocessorSessionManager.decodeTelemetry(malformed));
        }
        byte[] badFlags = valid.clone();
        badFlags[11] |= 8;
        assertEquals(null, LegacyPreprocessorSessionManager.decodeTelemetry(badFlags));
        byte[] zeroGeneration = valid.clone();
        Arrays.fill(zeroGeneration, 16, 24, (byte) 0);
        assertEquals(null, LegacyPreprocessorSessionManager.decodeTelemetry(zeroGeneration));
        assertEquals(null, LegacyPreprocessorSessionManager.decodeTelemetry(new byte[47]));
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

    @Test
    public void closedManagerRefusesEveryLifecycleCallbackAndDrainRequest() {
        Harness harness = new Harness();
        harness.start(60);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        harness.manager.shutdown();
        harness.scheduler.runReady();
        assertTrue(harness.scheduler.shutdown);
        assertFalse(harness.manager.ownsRoute(harness.record));

        int capabilityRequests = harness.capabilities.requests.size();
        int effectsCreated = harness.factory.createCount;
        Object late = new Object();
        harness.manager.onInitialized(late, 61, true);
        harness.manager.onStart(late, 61);
        harness.manager.onStop(late);
        harness.manager.onRelease(late);
        harness.manager.requestDrain(9L, 9L, (generation, token) -> {
            throw new AssertionError("a closed manager must not acknowledge a drain");
        });
        harness.scheduler.runReady();

        // Nothing is scheduled, no route is taken, and no new capability is ever requested.
        assertFalse(harness.manager.ownsRoute(late));
        assertEquals(capabilityRequests, harness.capabilities.requests.size());
        assertEquals(effectsCreated, harness.factory.createCount);

        // A second shutdown is a no-op rather than a second teardown.
        harness.manager.shutdown();
        assertEquals(effectsCreated, harness.factory.createCount);
    }

    @Test
    public void shutdownWithASaturatedSchedulerQuarantinesRoutesInsteadOfLeakingThem() {
        Harness harness = new Harness();
        harness.start(62);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        // Teardown cannot be dispatched, so the route must be poisoned rather than left owned by
        // a preprocessor that will never be revoked.
        harness.scheduler.rejectExecute = true;
        harness.manager.shutdown();

        assertTrue(harness.leases.isQuarantined(harness.record));
        assertTrue(harness.diagnostics.codes.contains("executor_saturated_shutdown"));
        assertTrue(harness.scheduler.shutdown);
    }

    @Test
    public void drainWithASaturatedSchedulerQuarantinesEveryRoute() {
        Harness harness = new Harness();
        harness.start(63);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        harness.scheduler.rejectExecute = true;
        harness.manager.requestDrain(1L, 5L, (generation, token) -> {
            throw new AssertionError("an undispatched drain must not be acknowledged");
        });

        assertTrue(harness.leases.isQuarantined(harness.record));
        assertTrue(harness.diagnostics.codes.contains("executor_saturated_drain"));
    }

    @Test
    public void lifecycleCallbacksWithUnusableArgumentsAreIgnored() {
        Harness harness = new Harness();

        harness.manager.onInitialized(null, 64, true);
        harness.manager.onInitialized(harness.record, 0, true);
        harness.manager.onInitialized(harness.record, -1, true);
        // An AudioRecord that failed to initialise never becomes a session.
        harness.manager.onInitialized(harness.record, 64, false);
        harness.manager.onStart(null, 64);
        harness.manager.onStart(harness.record, 0);
        harness.manager.onStop(null);
        harness.manager.onRelease(null);
        harness.scheduler.runReady();

        assertEquals(0, harness.factory.createCount);
        assertEquals(0, harness.capabilities.requests.size());
        assertFalse(harness.manager.ownsRoute(harness.record));
        assertFalse(harness.manager.ownsRoute(null));
    }

    @Test
    public void drainRequestsWithUnusableArgumentsAreIgnored() {
        Harness harness = new Harness();
        harness.start(65);
        harness.replySuccess(0);
        harness.scheduler.runReady();
        assertTrue(harness.manager.ownsRoute(harness.record));

        LegacyPreprocessorSessionManager.DrainCallback rejectAcknowledgement =
                (generation, token) -> {
                    throw new AssertionError("an invalid drain must not be acknowledged");
                };
        harness.manager.requestDrain(0L, 5L, rejectAcknowledgement);
        harness.manager.requestDrain(-1L, 5L, rejectAcknowledgement);
        // An acknowledgeable drain without a handoff token has nothing to acknowledge.
        harness.manager.requestDrain(5L, 0L, rejectAcknowledgement);
        harness.scheduler.runReady();

        // The route is untouched because no drain was ever accepted.
        assertTrue(harness.manager.ownsRoute(harness.record));
    }

    @Test
    public void sessionRegistryRefusesToGrowWithoutBoundAndDropsTheOverflowingRecord() {
        Harness harness = new Harness();
        List<Object> records = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Object record = new Object();
            records.add(record);
            harness.manager.onInitialized(record, 100 + i, true);
        }
        harness.scheduler.runReady();
        assertFalse(harness.diagnostics.codes.contains("session_registry_full"));

        Object overflow = new Object();
        harness.manager.onInitialized(overflow, 200, true);
        harness.manager.onStart(overflow, 200);
        harness.scheduler.runReady();

        // A process that churns AudioRecord instances must not be able to grow the registry
        // without bound; the overflowing record is refused and stays on the direct path.
        assertTrue(harness.diagnostics.codes.contains("session_registry_full"));
        assertFalse(harness.manager.ownsRoute(overflow));
        assertEquals(0, harness.factory.createCount);
    }

    @Test
    public void routeLeasesRefuseNullAndNonPositiveIdentityAndSaturate() {
        LegacyPreprocessorSessionManager.RouteLeases leases =
                new LegacyPreprocessorSessionManager.RouteLeases();
        Object record = new Object();

        assertTrue(leases.isEmpty());
        assertFalse(leases.acquire(null, 1, 1L));
        assertFalse(leases.acquire(record, 0, 1L));
        assertFalse(leases.acquire(record, -1, 1L));
        assertFalse(leases.acquire(record, 1, 0L));
        assertFalse(leases.acquire(record, 1, -1L));
        assertTrue(leases.isEmpty());

        assertTrue(leases.acquire(record, 7, 3L));
        assertFalse(leases.isEmpty());
        assertTrue(leases.contains(record));
        assertEquals(7, leases.session(record));
        // Re-acquiring is idempotent only for the exact session and generation it was taken for.
        assertTrue(leases.acquire(record, 7, 3L));
        assertFalse(leases.acquire(record, 8, 3L));
        assertFalse(leases.acquire(record, 7, 4L));

        leases.quarantine(record);
        assertTrue(leases.isQuarantined(record));
        // A quarantined route can never be re-acquired under its own identity.
        assertFalse(leases.acquire(record, 7, 3L));

        leases.release(record);
        assertTrue(leases.isEmpty());
        assertFalse(leases.contains(record));
        assertEquals(0, leases.session(record));
        assertFalse(leases.isQuarantined(record));
    }

    @Test
    public void routeLeaseTableIsBoundedAndIgnoresUnknownRecords() {
        LegacyPreprocessorSessionManager.RouteLeases leases =
                new LegacyPreprocessorSessionManager.RouteLeases();
        List<Object> records = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Object record = new Object();
            records.add(record);
            assertTrue(leases.acquire(record, i + 1, 1L));
        }

        // The 65th distinct record finds no free slot and must be refused, not silently evict one.
        assertFalse(leases.acquire(new Object(), 999, 1L));
        assertTrue(leases.contains(records.get(0)));

        // Unknown records are inert on every accessor rather than throwing.
        Object unknown = new Object();
        leases.quarantine(null);
        leases.quarantine(unknown);
        leases.release(null);
        leases.release(unknown);
        assertFalse(leases.isQuarantined(unknown));
        assertFalse(leases.contains(unknown));
        assertEquals(0, leases.session(unknown));

        leases.quarantineAll();
        for (Object record : records) {
            assertTrue(leases.isQuarantined(record));
        }
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

    private static byte[] telemetry(
            int sessionId,
            long generation,
            long sequence,
            int flags,
            long blocks,
            long frames,
            long failures,
            long mutations) {
        return ByteBuffer.allocate(48)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(0x45434854)
                .putShort((short) 1)
                .putShort((short) 1)
                .putShort((short) 48)
                .putShort((short) flags)
                .putInt(sessionId)
                .putLong(generation)
                .putInt((int) sequence)
                .putInt((int) blocks)
                .putInt((int) frames)
                .putInt((int) failures)
                .putInt((int) mutations)
                .putInt(0)
                .array();
    }

    private static final class Harness {
        final Object record = new Object();
        final ManualClock clock = new ManualClock();
        final MutablePolicy policy = new MutablePolicy();
        final RecordingCapabilities capabilities = new RecordingCapabilities();
        final RecordingTelemetry telemetry = new RecordingTelemetry();
        final RecordingEffectFactory factory = new RecordingEffectFactory();
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        final ManualScheduler scheduler = new ManualScheduler(clock);
        final LegacyPreprocessorSessionManager.RouteLeases leases =
                new LegacyPreprocessorSessionManager.RouteLeases();
        final LegacyPreprocessorSessionManager manager;
        int directTransforms;

        Harness() {
            this(null);
        }

        Harness(LegacyPreprocessorSessionManager.EffectFactory effectFactory) {
            manager = new LegacyPreprocessorSessionManager(
                    policy,
                    capabilities,
                    telemetry,
                    effectFactory != null ? effectFactory : factory,
                    clock,
                    diagnostics,
                    scheduler,
                    new FixedRandom(),
                    leases);
        }

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

    private static final class RecordingTelemetry
            implements LegacyPreprocessorSessionManager.TelemetryClient {
        final List<byte[]> snapshots = new ArrayList<>();
        final List<byte[]> capabilityNonces = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean report(
                int sessionId, long generation, byte[] capabilityNonce, byte[] snapshot) {
            capabilityNonces.add(capabilityNonce.clone());
            snapshots.add(snapshot.clone());
            return accept;
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
        Exception telemetryError;
        Exception proofError;
        int telemetryStatus = 48;
        int proofStatus = -22;
        byte[] telemetryOverride;
        final Deque<byte[]> telemetryValues = new ArrayDeque<>();
        final Deque<byte[]> proofValues = new ArrayDeque<>();
        final List<String> parameterQueries = new ArrayList<>();
        byte[] lastProofParameter;
        long generation;
        long telemetrySequence;
        int telemetryReadCount;
        int proofReadCount;
        Runnable beforeAuthorize;
        Runnable beforeRevoke;
        Runnable beforeEnable;
        Runnable beforeDisable;
        Runnable beforeRelease;

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
                if (beforeAuthorize != null) beforeAuthorize.run();
                authorizeCount++;
                lastAuthorizeParameter = parameter.clone();
                generation = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong(32);
                return authorizeStatus;
            }
            if (Arrays.equals(parameter, LegacyPreprocessorSessionManager.REVOKE_PARAMETER)) {
                if (beforeRevoke != null) beforeRevoke.run();
                revokeCount++;
                return revokeStatus;
            }
            throw new AssertionError("unexpected effect parameter");
        }

        @Override
        public int getParameter(byte[] parameter, byte[] value) throws Exception {
            if (parameter.length == 24
                    && Arrays.equals(
                            LegacyPreprocessorSessionManager.TELEMETRY_PROOF_PARAMETER,
                            Arrays.copyOf(parameter, 8))) {
                parameterQueries.add("proof");
                proofReadCount++;
                lastProofParameter = parameter.clone();
                if (proofError != null) throw proofError;
                if (proofStatus == 112) {
                    byte[] proof = !proofValues.isEmpty()
                            ? proofValues.removeFirst()
                            : new byte[112];
                    System.arraycopy(proof, 0, value, 0, Math.min(proof.length, value.length));
                }
                return proofStatus;
            }
            assertArrayEquals(LegacyPreprocessorSessionManager.TELEMETRY_PARAMETER, parameter);
            parameterQueries.add("diagnostic");
            telemetryReadCount++;
            if (telemetryError != null) throw telemetryError;
            if (telemetryStatus == 48) {
                byte[] snapshot = !telemetryValues.isEmpty()
                        ? telemetryValues.removeFirst()
                        : telemetryOverride != null
                                ? telemetryOverride
                                : telemetry(
                                sessionId,
                                generation,
                                ++telemetrySequence,
                                3,
                                0,
                                0,
                                0,
                                0);
                System.arraycopy(snapshot, 0, value, 0, Math.min(snapshot.length, value.length));
            }
            return telemetryStatus;
        }

        @Override
        public int setEnabled(boolean enabled) {
            if (enabled) {
                if (beforeEnable != null) beforeEnable.run();
                enableCount++;
                return enableStatus;
            }
            if (beforeDisable != null) beforeDisable.run();
            disableCount++;
            return disableStatus;
        }

        @Override
        public void release() throws Exception {
            if (beforeRelease != null) beforeRelease.run();
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
        private int seed = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (seed + i);
            }
            seed++;
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
