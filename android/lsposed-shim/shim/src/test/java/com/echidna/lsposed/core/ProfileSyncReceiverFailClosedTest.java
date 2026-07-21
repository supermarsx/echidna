package com.echidna.lsposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.RemoteException;

import com.echidna.control.service.IEchidnaCapabilityCallback;
import com.echidna.control.service.IEchidnaPolicyListener;
import com.echidna.control.service.IEchidnaPolicyProvider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Refusal behaviour of the Binder policy client. This class is a trust boundary: everything it
 * consumes arrives from another process, so the interesting assertions are the ones that prove it
 * REFUSES — a service that is not the exact policy component, a provider that lies about or
 * downgrades its API version, a provider that rejects or throws on a boundary call, and a bind
 * that never completes. Every one of those must drop policy back to the fail-closed sentinel.
 *
 * <p>{@link ProfileSyncReceiverCapabilityTest} covers the accepted paths; this covers the rest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ProfileSyncReceiverFailClosedTest {

    private static final ComponentName POLICY_COMPONENT = new ComponentName(
            "com.echidna.app",
            "com.echidna.control.service.PolicySnapshotService");

    private ProfileSnapshotStore store;

    @Before
    public void resetStore() {
        store = ProfileSnapshotStore.getInstance();
        store.resetForTests();
    }

    @After
    public void clearStore() {
        store.resetForTests();
    }

    // --- component identity -------------------------------------------------

    @Test
    public void serviceBoundUnderForeignComponentNameIsRefusedAndUnbound() throws Exception {
        Harness harness = authorizedHarness();
        try {
            // A rogue component that manages to satisfy the bind must never become the policy
            // source, regardless of the interface it exposes.
            ScriptedProvider rogue = new ScriptedProvider(7L);
            harness.connection().onServiceConnected(
                    new ComponentName("com.attacker", "com.attacker.PolicyService"),
                    rogue.asBinder());
            harness.awaitIdle();

            assertEquals(0, rogue.registrationCount.get());
            assertEquals(0, rogue.snapshotCount.get());
            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void nullBinderFromTheRealComponentIsRefusedAndUnbound() throws Exception {
        Harness harness = authorizedHarness();
        try {
            harness.connection().onServiceConnected(POLICY_COMPONENT, null);
            harness.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void nullBindingAndBindingDeathBothFailPolicyClosed() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 1L);
            assertEquals(1L, store.getSnapshot().generation());

            harness.connection().onBindingDied(POLICY_COMPONENT);
            assertFalse(store.getSnapshot().isValid());
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));

            Harness second = authorizedHarness();
            try {
                ScriptedProvider live = connectAuthorized(second, 2L);
                assertEquals(2L, store.getSnapshot().generation());
                second.connection().onNullBinding(POLICY_COMPONENT);
                assertFalse(store.getSnapshot().isValid());
                assertTrue(live.unregistered.await(3, TimeUnit.SECONDS));
            } finally {
                second.close();
            }
        } finally {
            harness.close();
        }
    }

    // --- registration handshake --------------------------------------------

    @Test
    public void providerRefusingCaptureOwnerRegistrationNeverBecomesAPolicySource()
            throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = new ScriptedProvider(7L);
            provider.registerResult = false;
            harness.connect(provider);
            assertTrue(provider.registrationAttempted.await(3, TimeUnit.SECONDS));
            harness.adapter.awaitUnbind();
            harness.awaitIdle();

            // Registration is the gate for everything else; refusing it must stop the fetch.
            assertEquals(0, provider.snapshotCount.get());
            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void registrationThrowingRemoteExceptionFailsClosedInsteadOfPropagating()
            throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = new ScriptedProvider(7L);
            provider.registerThrows = new RemoteException("provider died mid-handshake");
            harness.connect(provider);
            assertTrue(provider.registrationAttempted.await(3, TimeUnit.SECONDS));
            harness.adapter.awaitUnbind();
            harness.awaitIdle();

            assertEquals(0, provider.snapshotCount.get());
            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void registrationThrowingRuntimeExceptionFailsClosedInsteadOfPropagating()
            throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = new ScriptedProvider(7L);
            provider.apiVersionThrows = new IllegalStateException("hostile provider");
            harness.connect(provider);
            harness.adapter.awaitUnbind();
            harness.awaitIdle();

            assertEquals(0, provider.registrationCount.get());
            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    // --- policy payload -----------------------------------------------------

    @Test
    public void malformedPolicyPayloadIsRejectedAndRevokesTheStandingSnapshot() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 4L);
            assertEquals(4L, store.getSnapshot().generation());

            // A provider that starts emitting junk must revoke, not retain, the last good policy.
            provider.policyPayload = "{\"schemaVersion\":2,\"generation\":";
            CountDownLatch fetched = provider.expectFetch();
            provider.listener.onPolicyChanged(5L);
            assertTrue(fetched.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
        } finally {
            harness.close();
        }
    }

    @Test
    public void policyFetchThrowingFailsClosedAndTearsTheBindingDown() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 6L);
            assertEquals(6L, store.getSnapshot().generation());

            provider.snapshotThrows = new RemoteException("provider vanished");
            provider.listener.onPolicyChanged(7L);
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void policyChangeNotificationWithNonPositiveGenerationIsIgnored() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 8L);
            int fetchesAfterConnect = provider.snapshotCount.get();

            provider.listener.onPolicyChanged(0L);
            provider.listener.onPolicyChanged(-3L);
            harness.awaitIdle();

            assertEquals(fetchesAfterConnect, provider.snapshotCount.get());
            assertEquals(8L, store.getSnapshot().generation());
        } finally {
            harness.close();
        }
    }

    // --- drain acknowledgement ---------------------------------------------

    @Test
    public void providerRejectingTheDrainAcknowledgementLosesTheBinding() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 9L);
            provider.inactiveResult = false;

            provider.listener.onCaptureOwnerRevoked(9L, 51L);
            DrainRequest drain = harness.drainer.awaitRequest();
            assertEquals(51L, drain.handoffToken);
            assertNotNull(drain.completion);
            drain.completion.onDrained(9L, 51L);

            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();
            assertEquals(1, provider.inactiveCount.get());
            assertFalse(store.getSnapshot().isValid());
        } finally {
            harness.close();
        }
    }

    @Test
    public void drainAcknowledgementWithNonPositiveArgumentsNeverReachesTheProvider()
            throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 10L);

            reportCaptureOwnerInactive(harness.receiver, 0L, 5L);
            reportCaptureOwnerInactive(harness.receiver, 5L, 0L);
            reportCaptureOwnerInactive(harness.receiver, -1L, -1L);
            harness.awaitIdle();

            assertEquals(0, provider.inactiveCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void drainAcknowledgementOnDeadBinderTearsDownButOnLiveBinderKeepsTheBinding()
            throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 11L);

            // Live binder + RuntimeException: a transient provider bug must not drop policy.
            provider.inactiveThrows = new IllegalStateException("transient provider fault");
            reportCaptureOwnerInactive(harness.receiver, 11L, 61L);
            harness.awaitIdle();
            assertEquals(11L, store.getSnapshot().generation());
            assertEquals(0, harness.adapter.unbindCount.get());

            // Dead binder: the provider is gone, so the binding must be torn down.
            provider.inactiveThrows = new RemoteException("provider death");
            provider.binderAlive.set(false);
            reportCaptureOwnerInactive(harness.receiver, 11L, 62L);
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();
            assertFalse(store.getSnapshot().isValid());
        } finally {
            harness.close();
        }
    }

    // --- telemetry ----------------------------------------------------------

    @Test
    public void providerDowngradingItsApiVersionAfterRegistrationGetsNoTelemetry()
            throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 12L);

            // Registered at 7, then claims 6. Telemetry carries capability-bound material, so a
            // post-handshake downgrade must silence it rather than fall back to an older call.
            provider.apiVersion.set(6L);

            assertTrue(harness.receiver.reportLegacyPreprocessorTelemetry(
                    90, 12L, nonce(1), new byte[48]));
            assertTrue(harness.receiver.reportLegacyPreprocessorTelemetry(
                    91, 12L, nonce(2), new byte[112]));
            harness.awaitIdle();

            assertEquals(0, provider.telemetryCount.get());
            assertEquals(0, provider.proofCount.get());
            assertEquals(0, provider.legacyTelemetryCount.get());
            assertEquals(0, provider.legacyProofCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void providerRejectingEitherTelemetryBoundaryLosesTheBinding() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 13L);
            provider.telemetryResult = false;

            assertTrue(harness.receiver.reportLegacyPreprocessorTelemetry(
                    92, 13L, nonce(3), new byte[48]));
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();
            assertEquals(1, provider.telemetryCount.get());
            assertFalse(store.getSnapshot().isValid());
        } finally {
            harness.close();
        }

        Harness proofHarness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(proofHarness, 14L);
            provider.proofResult = false;

            assertTrue(proofHarness.receiver.reportLegacyPreprocessorTelemetry(
                    93, 14L, nonce(4), new byte[112]));
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            proofHarness.awaitIdle();
            assertEquals(1, proofHarness.adapter.unbindCount.get());
            assertFalse(store.getSnapshot().isValid());
        } finally {
            proofHarness.close();
        }
    }

    @Test
    public void telemetryOnDeadBinderTearsDownButRuntimeFaultKeepsTheBinding() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 15L);

            provider.telemetryThrows = new IllegalStateException("transient telemetry fault");
            assertTrue(harness.receiver.reportLegacyPreprocessorTelemetry(
                    94, 15L, nonce(5), new byte[48]));
            harness.awaitIdle();
            assertEquals(15L, store.getSnapshot().generation());

            provider.telemetryThrows = new RemoteException("provider death");
            provider.binderAlive.set(false);
            assertTrue(harness.receiver.reportLegacyPreprocessorTelemetry(
                    95, 15L, nonce(6), new byte[48]));
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();
            assertFalse(store.getSnapshot().isValid());
        } finally {
            harness.close();
        }
    }

    @Test
    public void telemetryArgumentsAreValidatedBeforeAnyBinderWork() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 16L);

            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    0, 16L, nonce(7), new byte[48]));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    96, 0L, nonce(7), new byte[48]));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    96, 16L, null, new byte[48]));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    96, 16L, new byte[15], new byte[48]));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    96, 16L, nonce(7), null));
            // Only the two fixed snapshot widths are transportable.
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    96, 16L, nonce(7), new byte[47]));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    96, 16L, nonce(7), new byte[111]));
            harness.awaitIdle();

            assertEquals(0, provider.telemetryCount.get());
            assertEquals(0, provider.proofCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void capabilityArgumentsAreValidatedBeforeAnyBinderWork() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 17L);
            RecordingCallback callback = new RecordingCallback();

            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    0, 17L, nonce(8), callback));
            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    97, 0L, nonce(8), callback));
            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    97, 17L, null, callback));
            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    97, 17L, new byte[17], callback));
            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    97, 17L, nonce(8), null));
            harness.awaitIdle();

            assertEquals(0, provider.capabilityCount.get());
            assertFalse(callback.completed.await(200, TimeUnit.MILLISECONDS));
        } finally {
            harness.close();
        }
    }

    @Test
    public void capabilityRequestFaultsAreReportedAsDistinctFailureDiagnostics() throws Exception {
        Harness liveHarness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(liveHarness, 18L);
            provider.capabilityThrows = new RemoteException("unimplemented boundary");

            RecordingCallback unsupported = new RecordingCallback();
            assertTrue(liveHarness.receiver.requestLegacyPreprocessorCapability(
                    98, 18L, nonce(9), unsupported));
            assertTrue(unsupported.completed.await(3, TimeUnit.SECONDS));
            // Binder still alive: the boundary is missing, not the process.
            assertEquals("unsupported", unsupported.failure.get());
            assertFalse(unsupported.resultDelivered.get());
        } finally {
            liveHarness.close();
        }

        Harness deadHarness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(deadHarness, 19L);
            provider.capabilityThrows = new RemoteException("provider death");
            provider.binderAlive.set(false);

            RecordingCallback disconnected = new RecordingCallback();
            assertTrue(deadHarness.receiver.requestLegacyPreprocessorCapability(
                    99, 19L, nonce(10), disconnected));
            assertTrue(disconnected.completed.await(3, TimeUnit.SECONDS));
            assertEquals("disconnected", disconnected.failure.get());
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            deadHarness.awaitIdle();
            assertFalse(store.getSnapshot().isValid());
        } finally {
            deadHarness.close();
        }

        Harness faultyHarness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(faultyHarness, 20L);
            provider.capabilityThrows = new IllegalStateException("hostile provider");

            RecordingCallback failed = new RecordingCallback();
            assertTrue(faultyHarness.receiver.requestLegacyPreprocessorCapability(
                    100, 20L, nonce(11), failed));
            assertTrue(failed.completed.await(3, TimeUnit.SECONDS));
            assertEquals("request_failed", failed.failure.get());
            // A RuntimeException from the provider is not evidence of death; policy survives.
            assertEquals(20L, store.getSnapshot().generation());
        } finally {
            faultyHarness.close();
        }
    }

    @Test
    public void capabilityCallbackDeliveredTwiceIsHonouredExactlyOnce() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 21L);
            provider.deferCapability = true;
            RecordingCallback callback = new RecordingCallback();

            assertTrue(harness.receiver.requestLegacyPreprocessorCapability(
                    101, 21L, nonce(12), callback));
            assertTrue(provider.capabilityRequested.await(3, TimeUnit.SECONDS));

            provider.pendingCallback.onCapabilityResult(0, 21L, new byte[] {1, 2}, "accepted");
            provider.pendingCallback.onCapabilityResult(0, 21L, new byte[] {3, 4}, "accepted");
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();

            assertEquals(1, callback.deliveries.get());
        } finally {
            harness.close();
        }
    }

    // --- bind lifecycle -----------------------------------------------------

    @Test
    public void bindThatIsRefusedByTheFrameworkFailsClosedAndSchedulesARetry() throws Exception {
        RefusingBindingAdapter adapter = new RefusingBindingAdapter();
        Harness harness = new Harness(adapter);
        try {
            harness.receiver.start();
            harness.awaitIdle();

            assertEquals(1, adapter.bindCount.get());
            assertFalse(store.getSnapshot().isValid());
            // A refused bind must queue a reconnect rather than abandon the process forever.
            assertTrue(harness.executor.hasScheduled());

            adapter.allow = true;
            harness.runScheduled();
            harness.awaitIdle();
            assertEquals(2, adapter.bindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void bindThrowingIsContainedAndStillSchedulesARetry() throws Exception {
        RefusingBindingAdapter adapter = new RefusingBindingAdapter();
        adapter.bindThrows = new SecurityException("caller not permitted to bind");
        Harness harness = new Harness(adapter);
        try {
            harness.receiver.start();
            harness.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
            assertTrue(harness.executor.hasScheduled());
        } finally {
            harness.close();
        }
    }

    @Test
    public void absentApplicationContextDefersBindingInsteadOfCrashing() throws Exception {
        RefusingBindingAdapter adapter = new RefusingBindingAdapter();
        adapter.context = null;
        Harness harness = new Harness(adapter);
        try {
            harness.receiver.start();
            harness.awaitIdle();

            assertEquals(0, adapter.bindCount.get());
            assertTrue(harness.executor.hasScheduled());
        } finally {
            harness.close();
        }
    }

    @Test
    public void bindThatNeverConnectsIsAbandonedWhenTheConnectTimeoutFires() throws Exception {
        Harness harness = authorizedHarness();
        try {
            // The framework accepted the bind but never called back. The timeout task must drop
            // the half-open binding so a later provider can take over.
            assertNotNull(currentBinding(harness.receiver));
            harness.runScheduled();
            harness.awaitIdle();

            assertNull(currentBinding(harness.receiver));
            assertEquals(1, harness.adapter.unbindCount.get());
            assertFalse(store.getSnapshot().isValid());
        } finally {
            harness.close();
        }
    }

    @Test
    public void connectTimeoutDoesNotDisturbAnAlreadyConnectedProvider() throws Exception {
        Harness harness = authorizedHarness();
        try {
            connectAuthorized(harness, 22L);
            assertEquals(22L, store.getSnapshot().generation());

            harness.runScheduled();
            harness.awaitIdle();

            assertEquals(22L, store.getSnapshot().generation());
            assertEquals(0, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void secondBindAttemptIsSuppressedWhileABindingIsAlreadyOutstanding() throws Exception {
        Harness harness = authorizedHarness();
        try {
            assertEquals(1, harness.adapter.bindCount.get());
            bindExplicitProvider(harness.receiver);
            harness.awaitIdle();

            assertEquals(1, harness.adapter.bindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void bindingEpochStaysPositiveAcrossOverflowSoStaleSessionsRemainDistinguishable()
            throws Exception {
        Harness harness = new Harness(new RefusingBindingAdapter());
        try {
            AtomicLong epoch = bindingEpoch(harness.receiver);
            epoch.set(Long.MAX_VALUE);

            long next = nextBindingEpoch(harness.receiver);

            // Wrapping to a negative epoch would make a stale binding compare as current.
            assertTrue(next > 0L);
            assertEquals(1L, next);
        } finally {
            harness.close();
        }
    }

    @Test
    public void unbindFaultsDuringCleanupAreContainedAndStillFailPolicyClosed() throws Exception {
        RefusingBindingAdapter adapter = new RefusingBindingAdapter();
        adapter.allow = true;
        adapter.unbindThrows = new IllegalArgumentException("service not registered");
        Harness harness = new Harness(adapter);
        try {
            harness.receiver.start();
            harness.awaitIdle();
            ScriptedProvider provider = connectAuthorized(harness, 23L);
            assertEquals(23L, store.getSnapshot().generation());

            harness.connection().onServiceDisconnected(POLICY_COMPONENT);
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, adapter.unbindCount.get());
        } finally {
            harness.close();
        }

        RefusingBindingAdapter faulty = new RefusingBindingAdapter();
        faulty.allow = true;
        faulty.unbindThrows = new SecurityException("unbind denied");
        Harness second = new Harness(faulty);
        try {
            second.receiver.start();
            second.awaitIdle();
            ScriptedProvider provider = connectAuthorized(second, 24L);

            second.connection().onServiceDisconnected(POLICY_COMPONENT);
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            second.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
        } finally {
            second.close();
        }
    }

    @Test
    public void unregisterFaultDuringCleanupStillCompletesTheUnbind() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 25L);
            provider.unregisterThrows = new RemoteException("provider already gone");

            harness.connection().onServiceDisconnected(POLICY_COMPONENT);
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();

            assertFalse(store.getSnapshot().isValid());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    @Test
    public void repeatedDisconnectsCleanUpOnceAndStillScheduleExactlyOneRebind() throws Exception {
        Harness harness = authorizedHarness();
        try {
            ScriptedProvider provider = connectAuthorized(harness, 26L);

            harness.connection().onServiceDisconnected(POLICY_COMPONENT);
            harness.connection().onServiceDisconnected(POLICY_COMPONENT);
            harness.connection().onBindingDied(POLICY_COMPONENT);
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
            harness.awaitIdle();

            assertEquals(1, provider.unregisterCount.get());
            assertEquals(1, harness.adapter.unbindCount.get());
        } finally {
            harness.close();
        }
    }

    // --- executor exhaustion ------------------------------------------------

    @Test
    public void deadExecutorMakesEveryBoundaryCallFailClosedRatherThanThrow() throws Exception {
        Harness harness = authorizedHarness();
        try {
            connectAuthorized(harness, 27L);
            harness.executor.shutdownNow();

            RecordingCallback callback = new RecordingCallback();
            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    102, 27L, nonce(13), callback));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    102, 27L, nonce(13), new byte[48]));
            // A dead worker must not surface RejectedExecutionException to a framework callback.
            harness.connection().onServiceDisconnected(POLICY_COMPONENT);
        } finally {
            harness.close();
        }
    }

    @Test
    public void boundaryCallsBeforeStartAreRefused() throws Exception {
        Harness harness = new Harness(new RefusingBindingAdapter());
        try {
            assertFalse(harness.receiver.requestLegacyPreprocessorCapability(
                    103, 1L, nonce(14), new RecordingCallback()));
            assertFalse(harness.receiver.reportLegacyPreprocessorTelemetry(
                    103, 1L, nonce(14), new byte[48]));
        } finally {
            harness.close();
        }
    }

    @Test
    public void unstartedReceiverNeitherBindsNorSchedulesAReconnect() throws Exception {
        RefusingBindingAdapter adapter = new RefusingBindingAdapter();
        adapter.allow = true;
        Harness harness = new Harness(adapter);
        try {
            // Nothing may reach the framework until start() has been called, even if an internal
            // path is re-entered — an unstarted receiver has no process identity to bind for.
            bindExplicitProvider(harness.receiver);
            scheduleRebind(harness.receiver);
            harness.awaitIdle();

            assertEquals(0, adapter.bindCount.get());
            assertFalse(harness.executor.hasScheduled());
            assertNull(currentBinding(harness.receiver));
        } finally {
            harness.close();
        }
    }

    @Test
    public void absentProcessNameIsNormalisedBeforeItReachesTheProvider() throws Exception {
        RecordingBindingAdapter adapter = new RecordingBindingAdapter();
        CapturingExecutor executor = new CapturingExecutor();
        ProfileSyncReceiver receiver = new ProfileSyncReceiver(
                store, null, adapter, new RecordingCaptureRouteDrainer(), executor);
        try {
            receiver.start();
            ScriptedProvider provider = new ScriptedProvider(7L);
            provider.policyPayload = policyPayload(30L);
            adapter.awaitConnection().onServiceConnected(POLICY_COMPONENT, provider.asBinder());
            assertTrue(provider.registrationAttempted.await(3, TimeUnit.SECONDS));

            // A null process name must become the empty string rather than travel over Binder as
            // null and be interpreted by the provider as "any process".
            assertEquals("", provider.registeredProcess);
        } finally {
            executor.shutdownNow();
        }
    }

    // --- production wiring --------------------------------------------------

    @Test
    public void storeStartsExactlyOneReceiverAndRoutesBoundaryCallsThroughIt() throws Exception {
        assertFalse(store.requestLegacyPreprocessorCapability(
                104, 1L, nonce(15), new RecordingCallback()));
        assertFalse(store.reportLegacyPreprocessorTelemetry(104, 1L, nonce(15), new byte[48]));

        store.ensureStarted("com.example.recorder", "com.example.recorder");
        ProfileSyncReceiver receiver = storeReceiver(store);
        assertNotNull(receiver);
        try {
            store.ensureStarted("com.other", "com.other");
            // One process, one receiver: a second attach must not create a second binder client.
            assertEquals(receiver, storeReceiver(store));

            // The production receiver is wired but unbound here, so it still fails closed.
            RecordingCallback callback = new RecordingCallback();
            assertTrue(store.requestLegacyPreprocessorCapability(105, 1L, nonce(16), callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));
            assertEquals("unavailable", callback.failure.get());
        } finally {
            executor(receiver).shutdownNow();
        }
    }

    @Test
    public void androidBindingAdapterDelegatesToTheHostContext() throws Exception {
        ProfileSyncReceiver.BindingAdapter adapter = androidBindingAdapter();
        RecordingContext context = new RecordingContext(
                RuntimeEnvironment.getApplication().getApplicationContext());
        ServiceConnection connection = new NoopServiceConnection();
        Intent intent = new Intent().setComponent(POLICY_COMPONENT);

        assertTrue(adapter.bind(context, intent, connection));
        assertEquals(Context.BIND_AUTO_CREATE, context.bindFlags.get());
        assertEquals(connection, context.boundConnection.get());

        adapter.unbind(context, connection);
        assertEquals(connection, context.unboundConnection.get());
    }

    // --- harness ------------------------------------------------------------

    private Harness authorizedHarness() throws Exception {
        RecordingBindingAdapter adapter = new RecordingBindingAdapter();
        Harness harness = new Harness(adapter);
        harness.receiver.start();
        harness.awaitIdle();
        assertEquals(1, adapter.bindCount.get());
        return harness;
    }

    private ScriptedProvider connectAuthorized(Harness harness, long generation)
            throws Exception {
        ScriptedProvider provider = new ScriptedProvider(7L);
        provider.policyPayload = policyPayload(generation);
        CountDownLatch fetched = provider.expectFetch();
        harness.connect(provider);
        assertTrue(fetched.await(3, TimeUnit.SECONDS));
        harness.awaitIdle();
        assertNotNull(provider.listener);
        return provider;
    }

    private final class Harness implements AutoCloseable {
        final CapturingExecutor executor = new CapturingExecutor();
        final RecordingCaptureRouteDrainer drainer = new RecordingCaptureRouteDrainer();
        final RecordingBindingAdapter adapter;
        final ProfileSyncReceiver receiver;

        Harness(RecordingBindingAdapter source) {
            this.adapter = source;
            this.receiver = new ProfileSyncReceiver(
                    store,
                    "com.example.recorder",
                    source,
                    drainer,
                    executor);
        }

        ServiceConnection connection() throws Exception {
            return adapter.awaitConnection();
        }

        void connect(ScriptedProvider provider) throws Exception {
            connection().onServiceConnected(POLICY_COMPONENT, provider.asBinder());
        }

        /**
         * A failing task enqueues its own cleanup, which in turn enqueues a rebind, so a single
         * barrier can return while the cascade it triggered is still queued behind it. Repeating
         * the barrier flushes one cascade level per pass.
         */
        void awaitIdle() throws Exception {
            for (int pass = 0; pass < 3; pass++) {
                CountDownLatch idle = new CountDownLatch(1);
                try {
                    executor.execute(idle::countDown);
                } catch (java.util.concurrent.RejectedExecutionException shutdown) {
                    return;
                }
                assertTrue(idle.await(3, TimeUnit.SECONDS));
            }
        }

        void runScheduled() throws Exception {
            for (Runnable task : executor.drainScheduled()) {
                executor.execute(task);
            }
            awaitIdle();
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    /**
     * Runs immediate work for real but parks delayed work, so connect timeouts and reconnect
     * backoff are driven by the test rather than by wall-clock sleeps.
     */
    private static final class CapturingExecutor extends ScheduledThreadPoolExecutor {
        private final List<Runnable> scheduled = new ArrayList<>();

        CapturingExecutor() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            // ScheduledThreadPoolExecutor implements execute() as schedule(cmd, 0, NANOSECONDS),
            // so only a genuinely deferred task is parked for the test to release.
            if (delay <= 0L) {
                return super.schedule(command, delay, unit);
            }
            synchronized (scheduled) {
                scheduled.add(command);
            }
            return super.schedule(() -> { }, 0L, TimeUnit.MILLISECONDS);
        }

        boolean hasScheduled() {
            synchronized (scheduled) {
                return !scheduled.isEmpty();
            }
        }

        List<Runnable> drainScheduled() {
            synchronized (scheduled) {
                List<Runnable> drained = new ArrayList<>(scheduled);
                scheduled.clear();
                return drained;
            }
        }
    }

    private static class RecordingBindingAdapter implements ProfileSyncReceiver.BindingAdapter {
        final LinkedBlockingQueue<ServiceConnection> connections = new LinkedBlockingQueue<>();
        final AtomicInteger bindCount = new AtomicInteger();
        final AtomicInteger unbindCount = new AtomicInteger();
        final CountDownLatch unbound = new CountDownLatch(1);
        volatile ServiceConnection last;

        @Override
        public Context context() {
            return RuntimeEnvironment.getApplication().getApplicationContext();
        }

        @Override
        public boolean bind(Context context, Intent intent, ServiceConnection connection) {
            bindCount.incrementAndGet();
            last = connection;
            connections.add(connection);
            return true;
        }

        @Override
        public void unbind(Context context, ServiceConnection connection) {
            unbindCount.incrementAndGet();
            unbound.countDown();
        }

        /** Cleanup is enqueued by the task that failed, so it outlives a plain idle barrier. */
        void awaitUnbind() throws InterruptedException {
            assertTrue(unbound.await(3, TimeUnit.SECONDS));
        }

        ServiceConnection awaitConnection() throws InterruptedException {
            if (last != null) {
                return last;
            }
            ServiceConnection connection = connections.poll(3, TimeUnit.SECONDS);
            assertNotNull(connection);
            return connection;
        }
    }

    private static final class RefusingBindingAdapter extends RecordingBindingAdapter {
        volatile boolean allow;
        volatile RuntimeException bindThrows;
        volatile RuntimeException unbindThrows;
        volatile Context context = RuntimeEnvironment.getApplication().getApplicationContext();

        @Override
        public Context context() {
            return context;
        }

        @Override
        public boolean bind(Context hostContext, Intent intent, ServiceConnection connection) {
            if (bindThrows != null) {
                bindCount.incrementAndGet();
                throw bindThrows;
            }
            super.bind(hostContext, intent, connection);
            return allow;
        }

        @Override
        public void unbind(Context hostContext, ServiceConnection connection) {
            super.unbind(hostContext, connection);
            if (unbindThrows != null) {
                throw unbindThrows;
            }
        }
    }

    private static final class RecordingCaptureRouteDrainer
            implements ProfileSyncReceiver.CaptureRouteDrainer {
        final LinkedBlockingQueue<DrainRequest> requests = new LinkedBlockingQueue<>();

        @Override
        public void request(
                long generation,
                long handoffToken,
                ProfileSyncReceiver.DrainCompletion completion) {
            requests.add(new DrainRequest(generation, handoffToken, completion));
        }

        DrainRequest awaitRequest() throws InterruptedException {
            DrainRequest request = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull(request);
            return request;
        }
    }

    private static final class DrainRequest {
        final long generation;
        final long handoffToken;
        final ProfileSyncReceiver.DrainCompletion completion;

        DrainRequest(
                long generation,
                long handoffToken,
                ProfileSyncReceiver.DrainCompletion completion) {
            this.generation = generation;
            this.handoffToken = handoffToken;
            this.completion = completion;
        }
    }

    /** A provider whose every boundary answer can be scripted to refuse, throw, or lie. */
    private static final class ScriptedProvider extends IEchidnaPolicyProvider.Stub {
        final AtomicLong apiVersion;
        final AtomicBoolean binderAlive = new AtomicBoolean(true);
        final AtomicInteger registrationCount = new AtomicInteger();
        final AtomicInteger snapshotCount = new AtomicInteger();
        final AtomicInteger inactiveCount = new AtomicInteger();
        final AtomicInteger telemetryCount = new AtomicInteger();
        final AtomicInteger proofCount = new AtomicInteger();
        final AtomicInteger capabilityCount = new AtomicInteger();
        final AtomicInteger legacyTelemetryCount = new AtomicInteger();
        final AtomicInteger legacyProofCount = new AtomicInteger();
        final AtomicInteger unregisterCount = new AtomicInteger();
        final CountDownLatch registrationAttempted = new CountDownLatch(1);
        final CountDownLatch capabilityRequested = new CountDownLatch(1);
        final CountDownLatch unregistered = new CountDownLatch(1);
        volatile CountDownLatch fetched = new CountDownLatch(1);
        volatile String policyPayload = "";
        volatile IEchidnaPolicyListener listener;
        volatile String registeredProcess;
        volatile IEchidnaCapabilityCallback pendingCallback;
        volatile boolean registerResult = true;
        volatile boolean inactiveResult = true;
        volatile boolean telemetryResult = true;
        volatile boolean proofResult = true;
        volatile boolean deferCapability;
        volatile Exception registerThrows;
        volatile RuntimeException apiVersionThrows;
        volatile Exception snapshotThrows;
        volatile Exception inactiveThrows;
        volatile Exception telemetryThrows;
        volatile Exception capabilityThrows;
        volatile Exception unregisterThrows;

        ScriptedProvider(long version) {
            this.apiVersion = new AtomicLong(version);
        }

        @Override
        public boolean isBinderAlive() {
            return binderAlive.get();
        }

        CountDownLatch expectFetch() {
            CountDownLatch expected = new CountDownLatch(1);
            fetched = expected;
            return expected;
        }

        @Override
        public long getApiVersion() {
            if (apiVersionThrows != null) {
                throw apiVersionThrows;
            }
            return apiVersion.get();
        }

        @Override
        public String getPolicySnapshot(String processName) throws RemoteException {
            snapshotCount.incrementAndGet();
            fetched.countDown();
            rethrow(snapshotThrows);
            return policyPayload;
        }

        @Override
        public void registerListener(String processName, IEchidnaPolicyListener target) {
        }

        @Override
        public void unregisterListener(IEchidnaPolicyListener target) throws RemoteException {
            unregisterCount.incrementAndGet();
            unregistered.countDown();
            rethrow(unregisterThrows);
        }

        @Override
        public boolean registerCaptureOwnerClient(
                String processName, long clientApiVersion, IEchidnaPolicyListener target)
                throws RemoteException {
            registrationCount.incrementAndGet();
            registeredProcess = processName;
            registrationAttempted.countDown();
            rethrow(registerThrows);
            listener = target;
            return registerResult;
        }

        @Override
        public void reportCaptureOwnerInactive(
                String processName, long generation, long handoffToken) {
        }

        @Override
        public boolean reportCaptureOwnerInactiveV7(
                String processName, long generation, long handoffToken) throws RemoteException {
            inactiveCount.incrementAndGet();
            rethrow(inactiveThrows);
            return inactiveResult;
        }

        @Override
        public void requestLegacyPreprocessorCapability(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] requestedNonce,
                IEchidnaCapabilityCallback callback) {
        }

        @Override
        public boolean requestLegacyPreprocessorCapabilityV7(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] requestedNonce,
                IEchidnaCapabilityCallback callback) throws RemoteException {
            capabilityCount.incrementAndGet();
            rethrow(capabilityThrows);
            pendingCallback = callback;
            capabilityRequested.countDown();
            if (!deferCapability) {
                callback.onCapabilityResult(0, requestedGeneration, new byte[] {7}, "accepted");
            }
            return true;
        }

        @Override
        public void reportLegacyPreprocessorTelemetry(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] snapshot) {
            legacyTelemetryCount.incrementAndGet();
        }

        @Override
        public void reportLegacyPreprocessorTelemetryV4(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] capabilityNonce,
                byte[] snapshot) {
            legacyTelemetryCount.incrementAndGet();
        }

        @Override
        public boolean reportLegacyPreprocessorTelemetryV7(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] capabilityNonce,
                byte[] snapshot) throws RemoteException {
            telemetryCount.incrementAndGet();
            rethrow(telemetryThrows);
            return telemetryResult;
        }

        @Override
        public void reportLegacyPreprocessorTelemetryProofV5(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] proof) {
            legacyProofCount.incrementAndGet();
        }

        @Override
        public boolean reportLegacyPreprocessorTelemetryProofV7(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] proof) throws RemoteException {
            proofCount.incrementAndGet();
            rethrow(telemetryThrows);
            return proofResult;
        }

        private static void rethrow(Exception error) throws RemoteException {
            if (error instanceof RemoteException) {
                throw (RemoteException) error;
            }
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
        }
    }

    private static final class RecordingCallback
            implements ProfileSnapshotStore.LegacyCapabilityCallback {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean resultDelivered = new AtomicBoolean(false);
        final AtomicInteger deliveries = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public void onResult(int status, long generation, byte[] envelope, String diagnostic) {
            resultDelivered.set(true);
            deliveries.incrementAndGet();
            completed.countDown();
        }

        @Override
        public void onFailure(String diagnostic) {
            deliveries.incrementAndGet();
            failure.set(diagnostic);
            completed.countDown();
        }
    }

    private static final class RecordingContext extends android.content.ContextWrapper {
        final AtomicInteger bindFlags = new AtomicInteger(-1);
        final AtomicReference<ServiceConnection> boundConnection = new AtomicReference<>();
        final AtomicReference<ServiceConnection> unboundConnection = new AtomicReference<>();

        RecordingContext(Context base) {
            super(base);
        }

        @Override
        public boolean bindService(Intent service, ServiceConnection connection, int flags) {
            bindFlags.set(flags);
            boundConnection.set(connection);
            return true;
        }

        @Override
        public void unbindService(ServiceConnection connection) {
            unboundConnection.set(connection);
        }
    }

    private static final class NoopServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, android.os.IBinder service) {
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    // --- reflection helpers -------------------------------------------------

    private static ScheduledExecutorService executor(ProfileSyncReceiver receiver)
            throws Exception {
        return (ScheduledExecutorService) field(ProfileSyncReceiver.class, "executor")
                .get(receiver);
    }

    private static Object currentBinding(ProfileSyncReceiver receiver) throws Exception {
        return field(ProfileSyncReceiver.class, "currentBinding").get(receiver);
    }

    private static AtomicLong bindingEpoch(ProfileSyncReceiver receiver) throws Exception {
        return (AtomicLong) field(ProfileSyncReceiver.class, "bindingEpoch").get(receiver);
    }

    private static long nextBindingEpoch(ProfileSyncReceiver receiver) throws Exception {
        java.lang.reflect.Method next =
                ProfileSyncReceiver.class.getDeclaredMethod("nextBindingEpoch");
        next.setAccessible(true);
        return (Long) next.invoke(receiver);
    }

    private static void scheduleRebind(ProfileSyncReceiver receiver) throws Exception {
        java.lang.reflect.Method rebind =
                ProfileSyncReceiver.class.getDeclaredMethod("scheduleRebind");
        rebind.setAccessible(true);
        rebind.invoke(receiver);
    }

    private static void bindExplicitProvider(ProfileSyncReceiver receiver) throws Exception {
        java.lang.reflect.Method bind =
                ProfileSyncReceiver.class.getDeclaredMethod("bindExplicitProvider");
        bind.setAccessible(true);
        bind.invoke(receiver);
    }

    private static void reportCaptureOwnerInactive(
            ProfileSyncReceiver receiver, long generation, long handoffToken) throws Exception {
        Object binding = currentBinding(receiver);
        assertNotNull(binding);
        java.lang.reflect.Method report = ProfileSyncReceiver.class.getDeclaredMethod(
                "reportCaptureOwnerInactive", binding.getClass(), long.class, long.class);
        report.setAccessible(true);
        report.invoke(receiver, binding, generation, handoffToken);
    }

    private static ProfileSyncReceiver storeReceiver(ProfileSnapshotStore store) throws Exception {
        return (ProfileSyncReceiver) field(ProfileSnapshotStore.class, "receiver").get(store);
    }

    private static ProfileSyncReceiver.BindingAdapter androidBindingAdapter() throws Exception {
        Class<?> type = Class.forName(
                "com.echidna.lsposed.core.ProfileSyncReceiver$AndroidBindingAdapter");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (ProfileSyncReceiver.BindingAdapter) constructor.newInstance();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static byte[] nonce(int seed) {
        byte[] value = new byte[16];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    private static String policyPayload(long generation) throws Exception {
        JSONObject preset = new JSONObject()
                .put("engine", new JSONObject().put("latencyMode", "LL"))
                .put("modules", new JSONArray());
        return new JSONObject()
                .put("schemaVersion", 2)
                .put("generation", generation)
                .put("profiles", new JSONObject().put("default", preset))
                .put("defaultProfileId", "default")
                .put("appBindings", new JSONObject())
                .put("whitelist", new JSONObject().put("com.example.recorder", true))
                .put("captureOwners", new JSONObject().put("com.example.recorder", "lsposed"))
                .put(
                        "control",
                        new JSONObject()
                                .put("masterEnabled", true)
                                .put("bypass", false)
                                .put("panicUntilEpochMs", 0L)
                                .put("sidetoneEnabled", false)
                                .put("sidetoneGainDb", 0.0)
                                .put("engineMode", "compatibility"))
                .toString();
    }
}
