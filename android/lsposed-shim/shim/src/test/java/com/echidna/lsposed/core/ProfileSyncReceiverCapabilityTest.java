package com.echidna.lsposed.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.echidna.control.service.IEchidnaCapabilityCallback;
import com.echidna.control.service.IEchidnaPolicyListener;
import com.echidna.control.service.IEchidnaPolicyProvider;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class ProfileSyncReceiverCapabilityTest {

    @Test
    public void supportedProviderRunsOffCallerAndRedispatchesOneShotResult() throws Exception {
        RecordingProvider provider = new RecordingProvider(2L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            long callerThread = Thread.currentThread().getId();
            byte[] nonce = nonce(1);
            RecordingCallback callback = new RecordingCallback();

            assertTrue(receiver.requestLegacyPreprocessorCapability(77, 9L, nonce, callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));

            assertEquals(1, provider.requestCount.get());
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
    public void versionOneProviderFallsBackWithoutCallingNewTransaction() throws Exception {
        RecordingProvider provider = new RecordingProvider(1L);
        ProfileSyncReceiver receiver = connectedReceiver(provider);
        try {
            RecordingCallback callback = new RecordingCallback();

            assertTrue(receiver.requestLegacyPreprocessorCapability(
                    78, 10L, nonce(2), callback));
            assertTrue(callback.completed.await(3, TimeUnit.SECONDS));

            assertEquals(0, provider.requestCount.get());
            assertEquals("unsupported", callback.failure.get());
            assertFalse(callback.resultDelivered.get());
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void versionFourProviderReceivesOwnedNonceBoundTelemetryOffCallerThread()
            throws Exception {
        RecordingProvider provider = new RecordingProvider(4L);
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
    public void versionThreeProviderKeepsCapabilityFallbackWithoutV4TelemetryTransaction()
            throws Exception {
        RecordingProvider provider = new RecordingProvider(3L);
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
        RecordingProvider provider = new RecordingProvider(5L);
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
            assertEquals(0x45, provider.telemetrySnapshot[0]);
        } finally {
            shutdown(receiver);
        }
    }

    @Test
    public void versionFourProviderNeverReceivesV2ProofThroughV4Transaction() throws Exception {
        RecordingProvider provider = new RecordingProvider(4L);
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

    private static ProfileSyncReceiver connectedReceiver(RecordingProvider provider)
            throws Exception {
        ProfileSyncReceiver receiver = new ProfileSyncReceiver(
                ProfileSnapshotStore.getInstance(), "com.example.recorder");
        Field started = ProfileSyncReceiver.class.getDeclaredField("started");
        started.setAccessible(true);
        ((AtomicBoolean) started.get(receiver)).set(true);
        Field connected = ProfileSyncReceiver.class.getDeclaredField("provider");
        connected.setAccessible(true);
        connected.set(receiver, provider);
        return receiver;
    }

    private static void shutdown(ProfileSyncReceiver receiver) throws Exception {
        Field executor = ProfileSyncReceiver.class.getDeclaredField("executor");
        executor.setAccessible(true);
        ((ScheduledExecutorService) executor.get(receiver)).shutdownNow();
    }

    private static byte[] nonce(int seed) {
        byte[] value = new byte[16];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    private static final class RecordingProvider extends IEchidnaPolicyProvider.Stub {
        final long apiVersion;
        final AtomicInteger requestCount = new AtomicInteger();
        final AtomicLong requestThread = new AtomicLong(-1L);
        volatile int sessionId;
        volatile long generation;
        volatile String processName;
        volatile byte[] nonce;
        final AtomicInteger telemetryCount = new AtomicInteger();
        final AtomicInteger proofCount = new AtomicInteger();
        final AtomicInteger legacyTelemetryCount = new AtomicInteger();
        final AtomicLong telemetryThread = new AtomicLong(-1L);
        volatile byte[] telemetrySnapshot;
        volatile byte[] telemetryNonce;
        final CountDownLatch telemetryReported = new CountDownLatch(1);
        final CountDownLatch proofReported = new CountDownLatch(1);

        RecordingProvider(long apiVersion) {
            this.apiVersion = apiVersion;
        }

        @Override
        public String getPolicySnapshot(String processName) {
            return "";
        }

        @Override
        public void registerListener(String processName, IEchidnaPolicyListener listener) {
        }

        @Override
        public void unregisterListener(IEchidnaPolicyListener listener) {
        }

        @Override
        public long getApiVersion() {
            return apiVersion;
        }

        @Override
        public void requestLegacyPreprocessorCapability(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] requestedNonce,
                IEchidnaCapabilityCallback callback) throws android.os.RemoteException {
            requestThread.set(Thread.currentThread().getId());
            requestCount.incrementAndGet();
            sessionId = audioSessionId;
            processName = requestedProcess;
            generation = requestedGeneration;
            nonce = requestedNonce.clone();
            callback.onCapabilityResult(
                    0, requestedGeneration, new byte[] {4, 5, 6}, "accepted");
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
            telemetryThread.set(Thread.currentThread().getId());
            telemetryCount.incrementAndGet();
            sessionId = audioSessionId;
            processName = requestedProcess;
            generation = requestedGeneration;
            telemetryNonce = capabilityNonce.clone();
            telemetrySnapshot = snapshot.clone();
            telemetryReported.countDown();
        }

        @Override
        public void reportLegacyPreprocessorTelemetryProofV5(
                int audioSessionId,
                String requestedProcess,
                long requestedGeneration,
                byte[] proof) {
            telemetryThread.set(Thread.currentThread().getId());
            proofCount.incrementAndGet();
            sessionId = audioSessionId;
            processName = requestedProcess;
            generation = requestedGeneration;
            telemetrySnapshot = proof.clone();
            proofReported.countDown();
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
