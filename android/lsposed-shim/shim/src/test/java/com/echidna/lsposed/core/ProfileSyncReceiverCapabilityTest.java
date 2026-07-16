package com.echidna.lsposed.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.ServiceConnection;

import com.echidna.control.service.IEchidnaCapabilityCallback;
import com.echidna.control.service.IEchidnaPolicyListener;
import com.echidna.control.service.IEchidnaPolicyProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ProfileSyncReceiverCapabilityTest {

    private static final ComponentName POLICY_COMPONENT = new ComponentName(
            "com.echidna.app",
            "com.echidna.control.service.PolicySnapshotService");

    @Test
    public void supportedProviderRunsOffCallerAndRedispatchesOneShotResult() throws Exception {
        RecordingProvider provider = new RecordingProvider(7L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            long callerThread = Thread.currentThread().getId();
            byte[] nonce = nonce(1);
            RecordingCallback callback = new RecordingCallback();

            assertTrue(receiver.requestLegacyPreprocessorCapability(77, 9L, nonce, callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));

            assertEquals(1, provider.requestCount.get());
            assertEquals(0, provider.legacyCapabilityCount.get());
            assertEquals(77, provider.sessionId);
            assertEquals(9L, provider.generation);
            assertEquals("com.example.recorder", provider.processName);
            assertArrayEquals(nonce, provider.nonce);
            assertNotEquals(callerThread, provider.requestThread.get());
            assertNotEquals(callerThread, callback.callbackThread.get());
            assertEquals(0, callback.status.get());
            assertEquals("accepted", callback.diagnostic.get());
            assertArrayEquals(new byte[] {4, 5, 6}, callback.envelope.get());
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void rejectedV7CapabilityBoundaryFailsClosedWithoutLegacyFallback() throws Exception {
        RecordingProvider provider = new RecordingProvider(7L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            provider.acceptV7Boundary = false;
            RecordingCallback callback = new RecordingCallback();

            assertTrue(receiver.requestLegacyPreprocessorCapability(
                    177, 19L, nonce(10), callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));
            assertEquals("request_rejected", callback.failure.get());
            assertFalse(callback.resultDelivered.get());
            assertEquals(0, provider.requestCount.get());
            assertEquals(0, provider.legacyCapabilityCount.get());
            assertTrue(provider.unregistered.await(3, TimeUnit.SECONDS));
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void providerBelowHandoffVersionNeverBecomesCapabilitySource() throws Exception {
        RecordingProvider provider = new RecordingProvider(6L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            RecordingCallback callback = new RecordingCallback();

            assertTrue(receiver.requestLegacyPreprocessorCapability(
                    78, 10L, nonce(2), callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));

            assertEquals(0, provider.requestCount.get());
            assertEquals("unavailable", callback.failure.get());
            assertFalse(callback.resultDelivered.get());
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void versionFourProviderReceivesOwnedNonceBoundTelemetryOffCallerThread()
            throws Exception {
        RecordingProvider provider = new RecordingProvider(7L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            long callerThread = Thread.currentThread().getId();
            byte[] capabilityNonce = nonce(3);
            byte[] snapshot = new byte[48];
            snapshot[0] = 0x45;
            assertTrue(receiver.reportLegacyPreprocessorTelemetry(
                    79, 11L, capabilityNonce, snapshot));
            capabilityNonce[0] = 0;
            snapshot[0] = 0;
            assertTrue(provider.telemetryReported.await(3, TimeUnit.SECONDS));

            assertEquals(1, provider.telemetryCount.get());
            assertEquals(0, provider.legacyV4TelemetryCount.get());
            assertEquals(0, provider.legacyTelemetryCount.get());
            assertEquals(79, provider.sessionId);
            assertEquals(11L, provider.generation);
            assertEquals("com.example.recorder", provider.processName);
            assertEquals(3, provider.telemetryNonce[0] & 0xff);
            assertEquals(0x45, provider.telemetrySnapshot[0]);
            assertNotEquals(callerThread, provider.telemetryThread.get());
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void providerBelowHandoffVersionCannotSendLegacyTelemetry()
            throws Exception {
        RecordingProvider provider = new RecordingProvider(6L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            assertTrue(receiver.reportLegacyPreprocessorTelemetry(
                    80, 12L, nonce(4), new byte[48]));
            assertFalse(provider.telemetryReported.await(200, TimeUnit.MILLISECONDS));
            assertEquals(0, provider.telemetryCount.get());
            assertEquals(0, provider.legacyTelemetryCount.get());
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void versionFiveProviderReceivesOwnedProofWithoutNonceDowngrade() throws Exception {
        RecordingProvider provider = new RecordingProvider(7L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            byte[] proof = new byte[112];
            proof[0] = 0x45;
            assertTrue(receiver.reportLegacyPreprocessorTelemetry(
                    81, 13L, nonce(5), proof));
            proof[0] = 0;
            assertTrue(provider.proofReported.await(3, TimeUnit.SECONDS));

            assertEquals(1, provider.proofCount.get());
            assertEquals(0, provider.telemetryCount.get());
            assertEquals(0, provider.legacyTelemetryCount.get());
            assertEquals(0, provider.legacyV4TelemetryCount.get());
            assertEquals(0, provider.legacyV5ProofCount.get());
            assertEquals(0x45, provider.telemetrySnapshot[0]);
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void providerBelowHandoffVersionNeverReceivesV2Proof() throws Exception {
        RecordingProvider provider = new RecordingProvider(6L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            assertTrue(receiver.reportLegacyPreprocessorTelemetry(
                    82, 14L, nonce(6), new byte[112]));
            assertFalse(provider.proofReported.await(200, TimeUnit.MILLISECONDS));
            assertFalse(provider.telemetryReported.await(200, TimeUnit.MILLISECONDS));
            assertEquals(0, provider.proofCount.get());
            assertEquals(0, provider.telemetryCount.get());
            assertEquals(0, provider.legacyTelemetryCount.get());
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void revokeAndProviderDeathStayFailClosedUntilApiSevenReregistrationFetchesPolicy()
            throws Exception {
        ProfileSnapshotStore store = ProfileSnapshotStore.getInstance();
        store.resetForTests();
        ProfileSyncReceiver receiver = startedReceiver(store);
        try {
            RecordingProvider first = new RecordingProvider(7L);
            first.policyPayload = policyPayload(1L);
            CountDownLatch firstFetch = first.expectPolicyFetch();
            ServiceConnection firstConnection = connect(receiver, first);

            assertTrue(first.captureRegistrationAttempted.await(3, TimeUnit.SECONDS));
            assertTrue(firstFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            assertEquals(1, first.captureRegistrationCount.get());
            assertEquals(7L, first.captureClientApiVersion);
            assertEquals("com.example.recorder", first.captureProcessName);
            assertNotNull(first.captureListener);
            assertEquals(1L, store.getSnapshot().generation());

            invokeCaptureOwnerRevoke(first.captureListener, 2L, 20L);
            assertFalse(store.getSnapshot().isValid());

            firstConnection.onServiceDisconnected(POLICY_COMPONENT);
            assertTrue(first.unregistered.await(3, TimeUnit.SECONDS));
            assertEquals(1, first.unregisterCount.get());
            assertFalse(store.getSnapshot().isValid());

            RecordingProvider second = new RecordingProvider(7L);
            CountDownLatch emptyFetch = second.expectPolicyFetch();
            connect(receiver, second);
            assertTrue(second.captureRegistrationAttempted.await(3, TimeUnit.SECONDS));
            assertTrue(emptyFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            assertFalse(store.getSnapshot().isValid());

            second.policyPayload = policyPayload(2L);
            CountDownLatch authorizedFetch = second.expectPolicyFetch();
            assertNotNull(second.captureListener);
            second.captureListener.onPolicyChanged(2L);
            assertTrue(authorizedFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);

            assertEquals(2L, store.getSnapshot().generation());
            assertEquals(2, second.policySnapshotCount.get());
            assertEquals(0, second.inactiveReportCount.get());
        } finally {
            shutdown(receiver);
            store.resetForTests();
        }
    }

    @Test
    public void apiSixHandshakeCannotFetchPolicyRequestCapabilityOrReportDrainAck()
            throws Exception {
        ProfileSnapshotStore store = ProfileSnapshotStore.getInstance();
        store.resetForTests();
        store.update(ProfileSnapshot.parse(policyPayload(1L)));
        ProfileSyncReceiver receiver = startedReceiver(store);
        try {
            RecordingProvider provider = new RecordingProvider(6L);
            provider.policyPayload = policyPayload(2L);
            connect(receiver, provider);

            assertTrue(provider.apiVersionRead.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            assertFalse(store.getSnapshot().isValid());
            assertEquals(0, provider.captureRegistrationCount.get());
            assertEquals(0, provider.policySnapshotCount.get());
            assertEquals(null, provider.captureListener);

            RecordingCallback callback = new RecordingCallback();
            assertTrue(receiver.requestLegacyPreprocessorCapability(
                    83, 15L, nonce(7), callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));
            assertEquals("unavailable", callback.failure.get());
            assertEquals(0, provider.requestCount.get());

            // No v7 registration exists, so there is no current endpoint to report through.
            awaitExecutorIdle(receiver);
            assertEquals(0, provider.inactiveReportCount.get());
        } finally {
            shutdown(receiver);
            store.resetForTests();
        }
    }

    @Test
    public void replacementRejectsDelayedCallbacksAndRoutesAckOnlyToCurrentProvider()
            throws Exception {
        ProfileSnapshotStore store = ProfileSnapshotStore.getInstance();
        store.resetForTests();
        ProfileSyncReceiver receiver = startedReceiver(store);
        long callbackThread = Thread.currentThread().getId();
        try {
            RecordingProvider first = new RecordingProvider(7L);
            first.policyPayload = policyPayload(1L);
            CountDownLatch firstFetch = first.expectPolicyFetch();
            ServiceConnection firstConnection = connect(receiver, first);
            assertTrue(firstFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            IEchidnaPolicyListener delayedListener = first.captureListener;
            Object firstBinding = currentBinding(receiver);
            assertNotNull(delayedListener);

            firstConnection.onServiceDisconnected(POLICY_COMPONENT);
            assertFalse(store.getSnapshot().isValid());
            DrainRequest disconnectDrain = captureRouteDrainer(receiver).awaitRequest();
            assertEquals(1L, disconnectDrain.generation);
            assertEquals(0L, disconnectDrain.handoffToken);
            assertEquals(null, disconnectDrain.completion);
            assertTrue(first.unregistered.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            assertNotEquals(callbackThread, first.unregisterThread.get());
            assertNotEquals(callbackThread, bindingAdapter(receiver).unbindThread.get());

            RecordingProvider second = new RecordingProvider(7L);
            second.policyPayload = policyPayload(2L);
            CountDownLatch secondFetch = second.expectPolicyFetch();
            connect(receiver, second);
            assertTrue(secondFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            assertEquals(2L, store.getSnapshot().generation());

            delayedListener.onPolicyChanged(99L);
            delayedListener.onCaptureOwnerRevoked(2L, 199L);
            reportCaptureOwnerInactive(receiver, firstBinding, 1L, 198L);
            awaitExecutorIdle(receiver);

            assertEquals(2L, store.getSnapshot().generation());
            assertEquals(1, first.policySnapshotCount.get());
            assertEquals(0, first.inactiveReportCount.get());

            second.captureListener.onCaptureOwnerRevoked(2L, 201L);
            second.captureListener.onCaptureOwnerRevoked(2L, 200L);
            assertFalse(store.getSnapshot().isValid());
            DrainRequest currentDrain = captureRouteDrainer(receiver).awaitRequest();
            assertEquals(2L, currentDrain.generation);
            assertEquals(201L, currentDrain.handoffToken);
            assertNotNull(currentDrain.completion);
            assertTrue(captureRouteDrainer(receiver).requests.isEmpty());
            currentDrain.completion.onDrained(2L, 201L);
            awaitExecutorIdle(receiver);
            assertEquals(1, second.inactiveReportCount.get());
            assertEquals(0, second.legacyInactiveReportCount.get());
            assertEquals(2L, second.inactiveGeneration);
            assertEquals(201L, second.inactiveHandoffToken);

            CountDownLatch reactivationFetch = second.expectPolicyFetch();
            second.captureListener.onPolicyChanged(2L);
            assertTrue(reactivationFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);
            assertEquals(2L, store.getSnapshot().generation());

            second.captureListener.onCaptureOwnerRevoked(2L, 201L);
            awaitExecutorIdle(receiver);
            assertEquals(2L, store.getSnapshot().generation());
            assertTrue(captureRouteDrainer(receiver).requests.isEmpty());
            assertEquals(1, second.inactiveReportCount.get());
        } finally {
            shutdown(receiver);
            store.resetForTests();
        }
    }

    @Test
    public void delayedCapabilityResultFromOldBindingFailsClosed() throws Exception {
        ProfileSnapshotStore store = ProfileSnapshotStore.getInstance();
        store.resetForTests();
        ProfileSyncReceiver receiver = startedReceiver(store);
        try {
            RecordingProvider first = new RecordingProvider(7L);
            first.policyPayload = policyPayload(1L);
            first.deferCapability = true;
            CountDownLatch firstFetch = first.expectPolicyFetch();
            ServiceConnection firstConnection = connect(receiver, first);
            assertTrue(firstFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);

            RecordingCallback callback = new RecordingCallback();
            assertTrue(receiver.requestLegacyPreprocessorCapability(
                    84, 1L, nonce(8), callback));
            assertTrue(first.capabilityRequested.await(3, TimeUnit.SECONDS));
            assertNotNull(first.pendingCapabilityCallback);

            firstConnection.onServiceDisconnected(POLICY_COMPONENT);
            assertTrue(first.unregistered.await(3, TimeUnit.SECONDS));
            RecordingProvider second = new RecordingProvider(7L);
            second.policyPayload = policyPayload(2L);
            CountDownLatch secondFetch = second.expectPolicyFetch();
            connect(receiver, second);
            assertTrue(secondFetch.await(3, TimeUnit.SECONDS));
            awaitExecutorIdle(receiver);

            first.pendingCapabilityCallback.onCapabilityResult(
                    0, 1L, new byte[] {9, 9, 9}, "accepted");
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));
            assertFalse(callback.resultDelivered.get());
            assertEquals("stale_binding", callback.failure.get());
            assertEquals(2L, store.getSnapshot().generation());
        } finally {
            shutdown(receiver);
            store.resetForTests();
        }
    }

    private static ProfileSyncReceiver connectedReceiver(RecordingProvider provider)
            throws Exception {
        ProfileSyncReceiver receiver = startedReceiver(ProfileSnapshotStore.getInstance());
        connect(receiver, provider);
        if (provider.apiVersion >= 7L) {
            assertTrue(provider.captureRegistrationAttempted.await(3, TimeUnit.SECONDS));
        } else {
            assertTrue(provider.apiVersionRead.await(3, TimeUnit.SECONDS));
        }
        awaitExecutorIdle(receiver);
        return receiver;
    }

    private static ProfileSyncReceiver startedReceiver(ProfileSnapshotStore store)
            throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        ProfileSyncReceiver receiver = new ProfileSyncReceiver(
                store,
                "com.example.recorder",
                new RecordingBindingAdapter(),
                new RecordingCaptureRouteDrainer(),
                executor);
        receiver.start();
        return receiver;
    }

    private static ServiceConnection connect(
            ProfileSyncReceiver receiver, RecordingProvider provider)
            throws Exception {
        ServiceConnection connection = bindingAdapter(receiver).awaitConnection();
        connection.onServiceConnected(POLICY_COMPONENT, provider.asBinder());
        return connection;
    }

    private static ScheduledExecutorService executor(ProfileSyncReceiver receiver)
            throws Exception {
        Field executor = ProfileSyncReceiver.class.getDeclaredField("executor");
        executor.setAccessible(true);
        return (ScheduledExecutorService) executor.get(receiver);
    }

    private static void awaitExecutorIdle(ProfileSyncReceiver receiver) throws Exception {
        CountDownLatch idle = new CountDownLatch(1);
        executor(receiver).execute(idle::countDown);
        assertTrue(idle.await(3, TimeUnit.SECONDS));
    }

    private static void reportCaptureOwnerInactive(
            ProfileSyncReceiver receiver, long generation, long handoffToken) throws Exception {
        Object binding = currentBinding(receiver);
        reportCaptureOwnerInactive(receiver, binding, generation, handoffToken);
    }

    private static void reportCaptureOwnerInactive(
            ProfileSyncReceiver receiver,
            Object binding,
            long generation,
            long handoffToken) throws Exception {
        Method report = ProfileSyncReceiver.class.getDeclaredMethod(
                "reportCaptureOwnerInactive", binding.getClass(), long.class, long.class);
        report.setAccessible(true);
        report.invoke(receiver, binding, generation, handoffToken);
    }

    private static void invokeCaptureOwnerRevoke(
            IEchidnaPolicyListener listener, long generation, long handoffToken) throws Exception {
        try {
            listener.onCaptureOwnerRevoked(generation, handoffToken);
        } catch (NoClassDefFoundError missingXposedRuntime) {
            // The host unit runtime intentionally excludes the compile-only Xposed API. The
            // listener fails the policy store closed before dispatching the separately tested
            // AudioRecord drain, so accept only that specific host-only linkage boundary.
            assertTrue(String.valueOf(missingXposedRuntime.getMessage())
                    .contains("de/robv/android/xposed/"));
        }
    }

    private static void shutdown(ProfileSyncReceiver receiver) throws Exception {
        executor(receiver).shutdownNow();
    }

    private static Object currentBinding(ProfileSyncReceiver receiver) throws Exception {
        Field current = ProfileSyncReceiver.class.getDeclaredField("currentBinding");
        current.setAccessible(true);
        return current.get(receiver);
    }

    private static RecordingBindingAdapter bindingAdapter(ProfileSyncReceiver receiver)
            throws Exception {
        Field adapter = ProfileSyncReceiver.class.getDeclaredField("bindingAdapter");
        adapter.setAccessible(true);
        return (RecordingBindingAdapter) adapter.get(receiver);
    }

    private static RecordingCaptureRouteDrainer captureRouteDrainer(
            ProfileSyncReceiver receiver) throws Exception {
        Field drainer = ProfileSyncReceiver.class.getDeclaredField("captureRouteDrainer");
        drainer.setAccessible(true);
        return (RecordingCaptureRouteDrainer) drainer.get(receiver);
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

    private static byte[] nonce(int seed) {
        byte[] value = new byte[16];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    private static final class RecordingBindingAdapter
            implements ProfileSyncReceiver.BindingAdapter {
        final LinkedBlockingQueue<ServiceConnection> connections = new LinkedBlockingQueue<>();
        final AtomicInteger bindCount = new AtomicInteger();
        final AtomicInteger unbindCount = new AtomicInteger();
        final AtomicLong bindThread = new AtomicLong(-1L);
        final AtomicLong unbindThread = new AtomicLong(-1L);

        @Override
        public android.content.Context context() {
            return RuntimeEnvironment.getApplication().getApplicationContext();
        }

        @Override
        public boolean bind(
                android.content.Context context,
                android.content.Intent intent,
                ServiceConnection connection) {
            bindThread.set(Thread.currentThread().getId());
            bindCount.incrementAndGet();
            connections.add(connection);
            return true;
        }

        @Override
        public void unbind(android.content.Context context, ServiceConnection connection) {
            unbindThread.set(Thread.currentThread().getId());
            unbindCount.incrementAndGet();
        }

        ServiceConnection awaitConnection() throws InterruptedException {
            ServiceConnection connection = connections.poll(3, TimeUnit.SECONDS);
            assertNotNull(connection);
            return connection;
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

    private static final class RecordingProvider extends IEchidnaPolicyProvider.Stub {
        final long apiVersion;
        final AtomicInteger requestCount = new AtomicInteger();
        final AtomicLong requestThread = new AtomicLong(-1L);
        final CountDownLatch capabilityRequested = new CountDownLatch(1);
        volatile int sessionId;
        volatile long generation;
        volatile String processName;
        volatile byte[] nonce;
        final AtomicInteger telemetryCount = new AtomicInteger();
        final AtomicInteger proofCount = new AtomicInteger();
        final AtomicInteger legacyCapabilityCount = new AtomicInteger();
        final AtomicInteger legacyTelemetryCount = new AtomicInteger();
        final AtomicInteger legacyV4TelemetryCount = new AtomicInteger();
        final AtomicInteger legacyV5ProofCount = new AtomicInteger();
        final AtomicInteger legacyInactiveReportCount = new AtomicInteger();
        final AtomicLong telemetryThread = new AtomicLong(-1L);
        volatile byte[] telemetrySnapshot;
        volatile byte[] telemetryNonce;
        final CountDownLatch telemetryReported = new CountDownLatch(1);
        final CountDownLatch proofReported = new CountDownLatch(1);
        final CountDownLatch apiVersionRead = new CountDownLatch(1);
        final CountDownLatch captureRegistrationAttempted = new CountDownLatch(1);
        final AtomicInteger captureRegistrationCount = new AtomicInteger();
        final AtomicInteger policySnapshotCount = new AtomicInteger();
        final AtomicInteger inactiveReportCount = new AtomicInteger();
        final AtomicInteger unregisterCount = new AtomicInteger();
        final CountDownLatch unregistered = new CountDownLatch(1);
        final AtomicLong unregisterThread = new AtomicLong(-1L);
        volatile CountDownLatch policySnapshotRequested = new CountDownLatch(1);
        volatile String policyPayload = "";
        volatile String captureProcessName;
        volatile long captureClientApiVersion;
        volatile IEchidnaPolicyListener captureListener;
        volatile long inactiveGeneration;
        volatile long inactiveHandoffToken;
        volatile boolean deferCapability;
        volatile boolean acceptV7Boundary = true;
        volatile IEchidnaCapabilityCallback pendingCapabilityCallback;

        RecordingProvider(long apiVersion) {
            this.apiVersion = apiVersion;
        }

        @Override
        public String getPolicySnapshot(String processName) {
            policySnapshotCount.incrementAndGet();
            policySnapshotRequested.countDown();
            return policyPayload;
        }

        @Override
        public void registerListener(String processName, IEchidnaPolicyListener listener) {
        }

        @Override
        public void unregisterListener(IEchidnaPolicyListener listener) {
            unregisterThread.set(Thread.currentThread().getId());
            unregisterCount.incrementAndGet();
            if (captureListener == listener) {
                captureListener = null;
            }
            unregistered.countDown();
        }

        @Override
        public long getApiVersion() {
            apiVersionRead.countDown();
            return apiVersion;
        }

        @Override
        public boolean registerCaptureOwnerClient(
                String processName,
                long clientApiVersion,
                IEchidnaPolicyListener listener) {
            captureRegistrationCount.incrementAndGet();
            captureProcessName = processName;
            captureClientApiVersion = clientApiVersion;
            captureListener = listener;
            captureRegistrationAttempted.countDown();
            return apiVersion >= 7L;
        }

        @Override
        public void reportCaptureOwnerInactive(
                String processName, long generation, long handoffToken) {
            legacyInactiveReportCount.incrementAndGet();
        }

        @Override
        public boolean reportCaptureOwnerInactiveV7(
                String processName, long generation, long handoffToken) {
            if (!acceptV7Boundary) {
                return false;
            }
            inactiveReportCount.incrementAndGet();
            inactiveGeneration = generation;
            inactiveHandoffToken = handoffToken;
            return true;
        }

        CountDownLatch expectPolicyFetch() {
            CountDownLatch expected = new CountDownLatch(1);
            policySnapshotRequested = expected;
            return expected;
        }

        @Override
        public void requestLegacyPreprocessorCapability(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] requestedNonce,
                IEchidnaCapabilityCallback callback) throws android.os.RemoteException {
            legacyCapabilityCount.incrementAndGet();
        }

        @Override
        public boolean requestLegacyPreprocessorCapabilityV7(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] requestedNonce,
                IEchidnaCapabilityCallback callback) throws android.os.RemoteException {
            if (!acceptV7Boundary) {
                return false;
            }
            requestThread.set(Thread.currentThread().getId());
            requestCount.incrementAndGet();
            sessionId = audioSessionId;
            processName = requestedProcess;
            generation = requestedGeneration;
            nonce = requestedNonce.clone();
            if (deferCapability) {
                pendingCapabilityCallback = callback;
                capabilityRequested.countDown();
                return true;
            }
            capabilityRequested.countDown();
            callback.onCapabilityResult(
                    0, requestedGeneration, new byte[] {4, 5, 6}, "accepted");
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
            legacyV4TelemetryCount.incrementAndGet();
        }

        @Override
        public boolean reportLegacyPreprocessorTelemetryV7(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] capabilityNonce,
                byte[] snapshot) {
            if (!acceptV7Boundary) {
                return false;
            }
            telemetryThread.set(Thread.currentThread().getId());
            telemetryCount.incrementAndGet();
            sessionId = audioSessionId;
            processName = requestedProcess;
            generation = requestedGeneration;
            telemetryNonce = capabilityNonce.clone();
            telemetrySnapshot = snapshot.clone();
            telemetryReported.countDown();
            return true;
        }

        @Override
        public void reportLegacyPreprocessorTelemetryProofV5(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] proof) {
            legacyV5ProofCount.incrementAndGet();
        }

        @Override
        public boolean reportLegacyPreprocessorTelemetryProofV7(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] proof) {
            if (!acceptV7Boundary) {
                return false;
            }
            telemetryThread.set(Thread.currentThread().getId());
            proofCount.incrementAndGet();
            sessionId = audioSessionId;
            processName = requestedProcess;
            generation = requestedGeneration;
            telemetrySnapshot = proof.clone();
            proofReported.countDown();
            return true;
        }
    }

    private static final class RecordingCallback
            implements ProfileSnapshotStore.LegacyCapabilityCallback {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean resultDelivered = new AtomicBoolean(false);
        final AtomicInteger status = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicLong callbackThread = new AtomicLong(-1L);
        final AtomicReference<byte[]> envelope = new AtomicReference<>();
        final AtomicReference<String> diagnostic = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public void onResult(
                int resultStatus,
                long generation,
                byte[] resultEnvelope,
                String resultDiagnostic) {
            callbackThread.set(Thread.currentThread().getId());
            resultDelivered.set(true);
            status.set(resultStatus);
            envelope.set(resultEnvelope);
            diagnostic.set(resultDiagnostic);
            completed.countDown();
        }

        @Override
        public void onFailure(String failureDiagnostic) {
            callbackThread.set(Thread.currentThread().getId());
            failure.set(failureDiagnostic);
            completed.countDown();
        }
    }
}
