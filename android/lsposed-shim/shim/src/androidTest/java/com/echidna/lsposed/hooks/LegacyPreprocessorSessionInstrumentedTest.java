package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.echidna.control.service.IEchidnaCapabilityCallback;
import com.echidna.control.service.IEchidnaPolicyListener;
import com.echidna.control.service.IEchidnaPolicyProvider;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Disposable-AVD proof that drives the production provider, session manager and AudioEffect path.
 * The host supplies deterministic PCM through EmulatorController.injectAudio at marker files.
 */
@RunWith(AndroidJUnit4.class)
public final class LegacyPreprocessorSessionInstrumentedTest {
    private static final String PROCESS = "com.echidna.lsposed";
    private static final ComponentName PROVIDER_COMPONENT = new ComponentName(
            "com.echidna.app", "com.echidna.control.service.PolicySnapshotService");
    private static final long PROVIDER_API_VERSION = 7L;
    private static final int SAMPLE_RATE = 48_000;
    private static final int CAPTURE_SAMPLES = 24_000;
    private static final int DISCARD_SIGNAL_SAMPLES = 4_800;
    private static final int SIGNAL_THRESHOLD = 100;
    private static final long CHECKPOINT_TIMEOUT_MS = 15_000L;
    private static final String EXPECTED_MODULE_ID = "echidna";
    private static final String EXPECTED_MODULE_VERSION = "0.0.0";
    private static final String EXPECTED_MODULE_VERSION_CODE = "000";
    private static final String EXPECTED_MODULE_SOURCE_COMMIT =
            "cea16af9a4a617e6277b5e55cfb5bf2619ebaf0f";
    private static final String EXPECTED_MODULE_ZIP_SHA256 =
            "54dd0e373fbc3bc050dd8147ffdfbc6ea613d050dcaf36f09ea3e59ebd95834f";
    private static final String ARG_MODULE_LIFECYCLE_STATUS =
            "echidna.module.lifecycle_status";
    private static final String ARG_MODULE_ID = "echidna.module.id";
    private static final String ARG_MODULE_VERSION = "echidna.module.version";
    private static final String ARG_MODULE_VERSION_CODE = "echidna.module.version_code";
    private static final String ARG_MODULE_SOURCE_COMMIT = "echidna.module.source_commit";
    private static final String ARG_MODULE_ZIP_SHA256 = "echidna.module.zip_sha256";

    @Rule
    public final GrantPermissionRule recordAudioPermission =
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    @Test
    public void apiSevenBinderBoundaryAndInjectedBaselineAreModuleIndependent() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(PROCESS, context.getPackageName());
        File evidenceDirectory = prepareEvidenceDirectory(
                context, "baseline.pcm", "baseline-result.json");
        try (ProviderSession session = ProviderSession.open(context, evidenceDirectory, true)) {
            AudioCapture baseline = captureBaseline(evidenceDirectory);
            assertTrue("baseline must be non-silent", baseline.rms > 100.0);
            assertTrue("baseline peak must be nonzero", baseline.peak > SIGNAL_THRESHOLD);
            assertTrue("baseline 1 kHz magnitude must be nonzero",
                    baseline.toneMagnitude > 100.0);

            JSONObject evidence = new JSONObject()
                    .put("status", "PASS")
                    .put("scope", "module-independent-api7-handshake-and-baseline")
                    .put("process", PROCESS)
                    .put("providerApiVersion", session.provider.getApiVersion())
                    .put("generation", session.generation)
                    .put("handoffRevokedGeneration", session.listener.revokedGeneration)
                    .put("handoffToken", session.listener.handoffToken)
                    .put("oneWayPidZeroRejected", true)
                    .put("baselineRms", baseline.rms)
                    .put("baselinePeak", baseline.peak)
                    .put("baselineToneMagnitude", baseline.toneMagnitude);
            writeText(
                    new File(evidenceDirectory, "baseline-result.json"),
                    evidence.toString(2));
        }
    }

    @Test
    public void readyModuleMutatesInjectedAudioAndRejectsInvalidProofs() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(PROCESS, context.getPackageName());

        ModuleReadiness readiness = ModuleReadiness.from(
                InstrumentationRegistry.getArguments());
        Assume.assumeTrue(readiness.failureMessage(), readiness.isReady());

        File evidenceDirectory = prepareEvidenceDirectory(
                context, "enabled.pcm", "result.json");
        try (ProviderSession session = ProviderSession.open(context, evidenceDirectory, false)) {
            IEchidnaPolicyProvider provider = session.provider;
            long generation = session.generation;
            AudioCapture baseline = captureBaseline(evidenceDirectory);

            ProofCollector proofCollector = new ProofCollector();
            ObservedEffectFactory effectFactory = new ObservedEffectFactory();
            DiagnosticCollector diagnostics = new DiagnosticCollector();
            CapabilityBridge capabilityBridge = new CapabilityBridge(provider);
            LegacyPreprocessorSessionManager.RouteLeases leases =
                    new LegacyPreprocessorSessionManager.RouteLeases();
            LegacyPreprocessorSessionManager manager = new LegacyPreprocessorSessionManager(
                    new LegacyPreprocessorSessionManager.PolicyAccess() {
                        @Override
                        public LegacyPreprocessorSessionManager.Policy current() {
                            return new LegacyPreprocessorSessionManager.Policy(true, generation);
                        }

                        @Override
                        public void invalidateDirectPermits() {
                            // No direct Java/JNI fallback is active in this standalone effect proof.
                        }
                    },
                    capabilityBridge,
                    proofCollector,
                    effectFactory,
                    SystemClock::elapsedRealtime,
                    diagnostics,
                    new LegacyPreprocessorSessionManager.DefaultScheduler(128),
                    new SecureRandom(),
                    leases);

            AudioRecord enabledRecord = createAudioRecord();
            boolean recording = false;
            int enabledSession = enabledRecord.getAudioSessionId();
            try {
                assertTrue(enabledSession > 0);
                manager.onInitialized(enabledRecord, enabledSession, true);
                enabledRecord.startRecording();
                recording = true;
                manager.onStart(enabledRecord, enabledSession);
                assertTrue("production manager did not authorize and enable the effect",
                        effectFactory.active.await(10, TimeUnit.SECONDS));
                assertEquals(0, effectFactory.authorizeStatus);
                assertEquals(0, effectFactory.enableStatus);
                assertTrue("session route lease must be held", waitForLease(manager, enabledRecord));
                writeCheckpoint(evidenceDirectory, "enabled-active");
                awaitHost(evidenceDirectory, "enabled-active");

                byte[] firstProof = proofCollector.awaitProof(10_000L);
                ProofView firstView = ProofView.decode(firstProof);
                assertEquals(enabledSession, firstView.sessionId);
                assertEquals(generation, firstView.generation);
                assertTrue(provider.reportLegacyPreprocessorTelemetryProofV7(
                        enabledSession, PROCESS, generation, firstProof));
                long firstReportAt = SystemClock.elapsedRealtime();

                writeCheckpoint(evidenceDirectory, "enabled-ready");
                AudioCapture enabled = readInjectedSignal(enabledRecord);
                writePcm(new File(evidenceDirectory, "enabled.pcm"), enabled.samples);

                byte[] secondProof = awaitMutatedProof(proofCollector, firstView);
                long elapsed = SystemClock.elapsedRealtime() - firstReportAt;
                if (elapsed < 300L) Thread.sleep(300L - elapsed);
                ProofView secondView = ProofView.decode(secondProof);
                assertEquals(enabledSession, secondView.sessionId);
                assertEquals(generation, secondView.generation);
                assertArrayEquals(firstView.nonce, secondView.nonce);
                assertTrue("telemetry sequence must advance",
                        isNewer(secondView.sequence, firstView.sequence));
                assertTrue("effect must process frames", secondView.frames > firstView.frames);
                assertTrue("effect must report real sample mutation",
                        secondView.mutations > firstView.mutations);
                assertTrue(provider.reportLegacyPreprocessorTelemetryProofV7(
                        enabledSession, PROCESS, generation, secondProof));
                Thread.sleep(350L);
                writeCheckpoint(evidenceDirectory, "valid-proof");
                awaitHost(evidenceDirectory, "valid-proof");

                // Replay has a valid tag but a stale sequence. Tamper changes the authenticated tag.
                assertTrue(provider.reportLegacyPreprocessorTelemetryProofV7(
                        enabledSession, PROCESS, generation, secondProof));
                byte[] tampered = secondProof.clone();
                tampered[tampered.length - 1] ^= 0x01;
                assertTrue(provider.reportLegacyPreprocessorTelemetryProofV7(
                        enabledSession, PROCESS, generation, tampered));

                // A newly issued capability replaces the live ledger nonce. The old, still-valid
                // HMAC proof is then stale for the exact session incarnation and must be rejected.
                byte[] replacementNonce = new byte[16];
                new SecureRandom().nextBytes(replacementNonce);
                CapabilityResult replacement = requestCapability(
                        provider, enabledSession, generation, replacementNonce);
                assertEquals(
                        "replacement capability must be genuinely signed", 0, replacement.status);
                assertArrayEquals(replacementNonce, capabilityNonce(replacement.envelope));
                assertTrue(provider.reportLegacyPreprocessorTelemetryProofV7(
                        enabledSession, PROCESS, generation, secondProof));
                Thread.sleep(350L);
                writeCheckpoint(evidenceDirectory, "invalid-proofs");
                awaitHost(evidenceDirectory, "invalid-proofs");

                double rmsRatio = enabled.rms / baseline.rms;
                double toneRatio = enabled.toneMagnitude / baseline.toneMagnitude;
                double singlePass = Math.pow(10.0, -9.0 / 20.0);
                double doublePass = singlePass * singlePass;
                assertTrue("baseline must be non-silent", baseline.rms > 100.0);
                assertTrue("enabled capture must be non-silent", enabled.rms > 20.0);
                assertTrue("-9 dB DSP must attenuate RMS once; ratio=" + rmsRatio,
                        rmsRatio > 0.24 && rmsRatio < 0.48);
                assertTrue("1 kHz spectral magnitude must attenuate once; ratio=" + toneRatio,
                        toneRatio > 0.24 && toneRatio < 0.48);
                assertTrue("observed level is closer to one pass than double processing",
                        Math.abs(rmsRatio - singlePass) < Math.abs(rmsRatio - doublePass));

                JSONObject evidence = new JSONObject()
                        .put("status", "PASS")
                        .put("moduleLifecycle", readiness.asJson())
                        .put("process", PROCESS)
                        .put("generation", generation)
                        .put("providerApiVersion", provider.getApiVersion())
                        .put("handoffRevokedGeneration", session.listener.revokedGeneration)
                        .put("handoffToken", session.listener.handoffToken)
                        .put("audioSessionId", enabledSession)
                        .put("effectType", LegacyPreprocessorSessionManager.TYPE_UUID.toString())
                        .put("effectImplementation",
                                LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID.toString())
                        .put("authorizeStatus", effectFactory.authorizeStatus)
                        .put("enableStatus", effectFactory.enableStatus)
                        .put("capabilityEnvelopeSha256", sha256(capabilityBridge.lastEnvelope.get()))
                        .put("capabilityNonce", hex(firstView.nonce))
                        .put("replacementCapabilityNonce", hex(replacementNonce))
                        .put("telemetryKeyId", hex(secondView.keyId))
                        .put("telemetrySequenceFirst", firstView.sequence)
                        .put("telemetrySequenceSecond", secondView.sequence)
                        .put("telemetryBlocksFirst", firstView.blocks)
                        .put("telemetryBlocksSecond", secondView.blocks)
                        .put("telemetryFramesFirst", firstView.frames)
                        .put("telemetryFramesSecond", secondView.frames)
                        .put("telemetryFailuresSecond", secondView.failures)
                        .put("telemetryMutationsFirst", firstView.mutations)
                        .put("telemetryMutationsSecond", secondView.mutations)
                        .put("baselineRms", baseline.rms)
                        .put("enabledRms", enabled.rms)
                        .put("rmsRatio", rmsRatio)
                        .put("baselinePeak", baseline.peak)
                        .put("enabledPeak", enabled.peak)
                        .put("baselineToneMagnitude", baseline.toneMagnitude)
                        .put("enabledToneMagnitude", enabled.toneMagnitude)
                        .put("toneMagnitudeRatio", toneRatio)
                        .put("singlePassExpectedRatio", singlePass)
                        .put("doublePassExpectedRatio", doublePass)
                        .put("routeLeaseOwned", manager.ownsRoute(enabledRecord))
                        .put("diagnostics", diagnostics.asJson());
                writeText(new File(evidenceDirectory, "result.json"), evidence.toString(2));
            } finally {
                manager.onStop(enabledRecord);
                manager.onRelease(enabledRecord);
                if (recording) {
                    try {
                        enabledRecord.stop();
                    } catch (IllegalStateException ignored) {
                        // Release still runs after an already-stopped or failed AudioRecord.
                    }
                }
                enabledRecord.release();
                manager.shutdown();
            }
        }
    }

    private static AudioCapture captureBaseline(File evidenceDirectory) throws Exception {
        AudioRecord record = createAudioRecord();
        boolean recording = false;
        try {
            record.startRecording();
            recording = true;
            writeCheckpoint(evidenceDirectory, "baseline-ready");
            AudioCapture capture = readInjectedSignal(record);
            writePcm(new File(evidenceDirectory, "baseline.pcm"), capture.samples);
            return capture;
        } finally {
            if (recording) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                    // Release still runs after an already-stopped or failed AudioRecord.
                }
            }
            record.release();
        }
    }

    private static AudioRecord createAudioRecord() {
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        assertTrue("minimum input buffer unavailable: " + minimum, minimum > 0);
        AudioRecord record = new AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum * 2, 19_200));
        assertEquals(AudioRecord.STATE_INITIALIZED, record.getState());
        return record;
    }

    private static AudioCapture readInjectedSignal(AudioRecord record) {
        short[] chunk = new short[960];
        short[] captured = new short[CAPTURE_SAMPLES];
        int discarded = 0;
        int written = 0;
        boolean signal = false;
        long deadline = SystemClock.elapsedRealtime() + 12_000L;
        while (written < captured.length && SystemClock.elapsedRealtime() < deadline) {
            int count = record.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
            assertTrue("AudioRecord read failed: " + count, count > 0);
            int offset = 0;
            if (!signal) {
                for (int i = 0; i < count; i++) {
                    if (Math.abs((int) chunk[i]) >= SIGNAL_THRESHOLD) {
                        signal = true;
                        offset = i;
                        break;
                    }
                }
                if (!signal) continue;
            }
            while (offset < count && discarded < DISCARD_SIGNAL_SAMPLES) {
                discarded++;
                offset++;
            }
            int copy = Math.min(count - offset, captured.length - written);
            if (copy > 0) {
                System.arraycopy(chunk, offset, captured, written, copy);
                written += copy;
            }
        }
        assertEquals("deterministic injected signal was not fully captured",
                captured.length, written);
        return AudioCapture.measure(captured);
    }

    private static byte[] awaitMutatedProof(ProofCollector collector, ProofView first)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            byte[] raw = collector.awaitProof(1_000L);
            ProofView view = ProofView.decode(raw);
            if (isNewer(view.sequence, first.sequence)
                    && view.frames > first.frames
                    && view.mutations > first.mutations) {
                return raw;
            }
        }
        throw new AssertionError("no advancing mutated ECHT v2 proof observed");
    }

    private static boolean waitForLease(
            LegacyPreprocessorSessionManager manager, Object record) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (manager.ownsRoute(record)) return true;
            Thread.sleep(20L);
        }
        return manager.ownsRoute(record);
    }

    private static boolean registerWhenPublished(
            IEchidnaPolicyProvider provider, IEchidnaPolicyListener listener) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (provider.registerCaptureOwnerClient(PROCESS, PROVIDER_API_VERSION, listener)) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private static JSONObject awaitActivatingPolicy(
            IEchidnaPolicyProvider provider, PolicyListener listener)
            throws Exception {
        for (int i = 0; i < 100; i++) {
            String snapshot = provider.getPolicySnapshot(PROCESS);
            if (snapshot != null && !snapshot.isEmpty()) return new JSONObject(snapshot);
            Thread.sleep(100L);
        }
        throw new AssertionError("registered capture owner never received activating policy; "
                + "changed=" + listener.generation
                + ", revoked=" + listener.revokedGeneration
                + ", token=" + listener.handoffToken);
    }

    private static CapabilityResult requestCapability(
            IEchidnaPolicyProvider provider, int session, long generation, byte[] nonce)
            throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<CapabilityResult> result = new AtomicReference<>();
        assertTrue("synchronous capability request must preserve Binder identity",
                provider.requestLegacyPreprocessorCapabilityV7(
                session,
                PROCESS,
                generation,
                nonce,
                new IEchidnaCapabilityCallback.Stub() {
                    @Override
                    public void onCapabilityResult(
                            int status, long actualGeneration, byte[] envelope, String diagnostic) {
                        result.set(new CapabilityResult(
                                status,
                                actualGeneration,
                                envelope != null ? envelope.clone() : new byte[0],
                                diagnostic));
                        completed.countDown();
                    }
                }));
        assertTrue("capability callback timed out", completed.await(5, TimeUnit.SECONDS));
        CapabilityResult value = result.get();
        assertNotNull(value);
        assertEquals(generation, value.generation);
        return value;
    }

    private static void assertOneWayRegistrationRejected(IEchidnaPolicyProvider provider)
            throws Exception {
        PolicyListener probe = new PolicyListener();
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IEchidnaPolicyProvider.DESCRIPTOR);
            data.writeString(PROCESS);
            data.writeLong(PROVIDER_API_VERSION);
            data.writeStrongBinder(probe.asBinder());
            assertTrue("raw one-way registration transaction must be dispatched",
                    provider.asBinder().transact(
                            IBinder.FIRST_CALL_TRANSACTION + 8,
                            data,
                            null,
                            IBinder.FLAG_ONEWAY));
        } finally {
            data.recycle();
        }
        assertFalse("PID-zero registration must not reach the handoff coordinator",
                probe.revoked.await(500L, TimeUnit.MILLISECONDS));
        assertEquals("PID-zero registration must not receive policy callbacks", 0L,
                probe.generation);
    }

    private static byte[] capabilityNonce(byte[] envelope) {
        assertNotNull(envelope);
        assertTrue(envelope.length >= 72);
        return Arrays.copyOfRange(envelope, 56, 72);
    }

    private static void writeCheckpoint(File directory, String value) throws Exception {
        writeText(new File(directory, "checkpoint"), value);
    }

    private static void awaitHost(File directory, String checkpoint) throws Exception {
        File signal = new File(directory, "continue-" + checkpoint);
        long deadline = SystemClock.elapsedRealtime() + CHECKPOINT_TIMEOUT_MS;
        while (!signal.isFile() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue("host did not acknowledge checkpoint " + checkpoint, signal.isFile());
    }

    private static void writePcm(File file, short[] samples) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) bytes.putShort(sample);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes.array());
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static File prepareEvidenceDirectory(Context context, String... ownedOutputs) {
        File directory = new File(context.getCacheDir(), "session-proof");
        assertTrue(directory.mkdirs() || directory.isDirectory());
        List<String> outputs = Arrays.asList(ownedOutputs);
        File[] children = directory.listFiles();
        if (children == null) return directory;
        for (File child : children) {
            String name = child.getName();
            if (name.equals("checkpoint")
                    || name.startsWith("continue-")
                    || outputs.contains(name)) {
                assertTrue("failed to delete stale evidence " + child, child.delete());
            }
        }
        return directory;
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) output.append(String.format(Locale.US, "%02x", item & 0xff));
        return output.toString();
    }

    private static boolean isNewer(long next, long previous) {
        long distance = (next - previous) & 0xffff_ffffL;
        return distance > 0L && distance < 0x8000_0000L;
    }

    private static final class ModuleReadiness {
        final String lifecycleStatus;
        final String moduleId;
        final String version;
        final String versionCode;
        final String sourceCommit;
        final String zipSha256;

        ModuleReadiness(
                String lifecycleStatus,
                String moduleId,
                String version,
                String versionCode,
                String sourceCommit,
                String zipSha256) {
            this.lifecycleStatus = lifecycleStatus;
            this.moduleId = moduleId;
            this.version = version;
            this.versionCode = versionCode;
            this.sourceCommit = sourceCommit;
            this.zipSha256 = zipSha256;
        }

        static ModuleReadiness from(Bundle arguments) {
            return new ModuleReadiness(
                    arguments.getString(ARG_MODULE_LIFECYCLE_STATUS),
                    arguments.getString(ARG_MODULE_ID),
                    arguments.getString(ARG_MODULE_VERSION),
                    arguments.getString(ARG_MODULE_VERSION_CODE),
                    arguments.getString(ARG_MODULE_SOURCE_COMMIT),
                    arguments.getString(ARG_MODULE_ZIP_SHA256));
        }

        boolean isReady() {
            return "ready".equals(lifecycleStatus)
                    && EXPECTED_MODULE_ID.equals(moduleId)
                    && EXPECTED_MODULE_VERSION.equals(version)
                    && EXPECTED_MODULE_VERSION_CODE.equals(versionCode)
                    && EXPECTED_MODULE_SOURCE_COMMIT.equals(sourceCommit)
                    && EXPECTED_MODULE_ZIP_SHA256.equals(zipSha256);
        }

        String failureMessage() {
            return "enabled DSP proof requires the exact ready current-module tuple before any "
                    + "effect work; expected="
                    + expectedDescription()
                    + ", actual="
                    + actualDescription();
        }

        JSONObject asJson() throws Exception {
            return new JSONObject()
                    .put("lifecycleStatus", lifecycleStatus)
                    .put("moduleId", moduleId)
                    .put("version", version)
                    .put("versionCode", versionCode)
                    .put("sourceCommit", sourceCommit)
                    .put("zipSha256", zipSha256);
        }

        private static String expectedDescription() {
            return "ready/"
                    + EXPECTED_MODULE_ID
                    + "/"
                    + EXPECTED_MODULE_VERSION
                    + "/"
                    + EXPECTED_MODULE_VERSION_CODE
                    + "/"
                    + EXPECTED_MODULE_SOURCE_COMMIT
                    + "/"
                    + EXPECTED_MODULE_ZIP_SHA256;
        }

        private String actualDescription() {
            return String.valueOf(lifecycleStatus)
                    + "/"
                    + String.valueOf(moduleId)
                    + "/"
                    + String.valueOf(version)
                    + "/"
                    + String.valueOf(versionCode)
                    + "/"
                    + String.valueOf(sourceCommit)
                    + "/"
                    + String.valueOf(zipSha256);
        }
    }

    private static final class ProviderSession implements AutoCloseable {
        final Context context;
        final ProviderBinding binding;
        final IEchidnaPolicyProvider provider;
        final PolicyListener listener;
        final long generation;

        ProviderSession(
                Context context,
                ProviderBinding binding,
                IEchidnaPolicyProvider provider,
                PolicyListener listener,
                long generation) {
            this.context = context;
            this.binding = binding;
            this.provider = provider;
            this.listener = listener;
            this.generation = generation;
        }

        static ProviderSession open(
                Context context, File evidenceDirectory, boolean probeOneWayRegistration)
                throws Exception {
            ProviderBinding binding = new ProviderBinding();
            boolean bound = false;
            IEchidnaPolicyProvider provider = null;
            PolicyListener listener = new PolicyListener();
            boolean registered = false;
            try {
                Intent intent = new Intent().setComponent(PROVIDER_COMPONENT);
                bound = context.bindService(intent, binding, Context.BIND_AUTO_CREATE);
                assertTrue("provider bind must register", bound);
                provider = binding.await();
                assertEquals(PROVIDER_API_VERSION, provider.getApiVersion());
                if (probeOneWayRegistration) assertOneWayRegistrationRejected(provider);

                registered = registerWhenPublished(provider, listener);
                assertTrue("published identity/owner registration timed out", registered);
                assertTrue("initial capture-owner drain was not requested",
                        listener.revoked.await(10, TimeUnit.SECONDS));
                assertTrue(
                        "initial inactive handoff token must be positive",
                        listener.handoffToken > 0L);
                // This standalone proof has no pre-existing AudioRecord route to drain.
                assertTrue("synchronous handoff report must preserve Binder identity",
                        provider.reportCaptureOwnerInactiveV7(
                                PROCESS, listener.revokedGeneration, listener.handoffToken));
                writeCheckpoint(evidenceDirectory, "handoff-reported");
                awaitHost(evidenceDirectory, "handoff-reported");

                JSONObject policy = awaitActivatingPolicy(provider, listener);
                long generation = policy.getLong("generation");
                assertTrue(generation > 0L);
                assertEquals(generation, listener.revokedGeneration);
                assertEquals(
                        "lsposed",
                        policy.getJSONObject("captureOwners").getString(PROCESS));
                assertEquals(
                        "compatibility",
                        policy.getJSONObject("control").getString("engineMode"));
                return new ProviderSession(context, binding, provider, listener, generation);
            } catch (Throwable failure) {
                if (registered && provider != null) {
                    try {
                        provider.unregisterListener(listener);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                if (bound) {
                    try {
                        context.unbindService(binding);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new AssertionError("unexpected provider-session failure", failure);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                provider.unregisterListener(listener);
            } finally {
                context.unbindService(binding);
            }
        }
    }

    private static final class ProviderBinding implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1);
        volatile IEchidnaPolicyProvider provider;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            provider = IEchidnaPolicyProvider.Stub.asInterface(service);
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            provider = null;
        }

        IEchidnaPolicyProvider await() throws InterruptedException {
            assertTrue("provider bind timed out", connected.await(10, TimeUnit.SECONDS));
            assertNotNull(provider);
            return provider;
        }
    }

    private static final class PolicyListener extends IEchidnaPolicyListener.Stub {
        final CountDownLatch revoked = new CountDownLatch(1);
        volatile long generation;
        volatile long revokedGeneration;
        volatile long handoffToken;

        @Override
        public void onPolicyChanged(long value) {
            generation = value;
        }

        @Override
        public void onCaptureOwnerRevoked(long value, long token) {
            revokedGeneration = value;
            handoffToken = token;
            revoked.countDown();
        }
    }

    private static final class CapabilityBridge
            implements LegacyPreprocessorSessionManager.CapabilityClient {
        private final IEchidnaPolicyProvider provider;
        final AtomicReference<byte[]> lastEnvelope = new AtomicReference<>(new byte[0]);

        CapabilityBridge(IEchidnaPolicyProvider provider) {
            this.provider = provider;
        }

        @Override
        public boolean request(
                int sessionId,
                long generation,
                byte[] nonce,
                LegacyPreprocessorSessionManager.CapabilityCallback callback) {
            try {
                boolean accepted = provider.requestLegacyPreprocessorCapabilityV7(
                        sessionId,
                        PROCESS,
                        generation,
                        nonce,
                        new IEchidnaCapabilityCallback.Stub() {
                            @Override
                            public void onCapabilityResult(
                                    int status,
                                    long actualGeneration,
                                    byte[] envelope,
                                    String diagnostic) {
                                byte[] owned = envelope != null ? envelope.clone() : new byte[0];
                                if (status == 0) lastEnvelope.set(owned.clone());
                                callback.onResult(
                                        status, actualGeneration, owned, diagnostic);
                            }
                        });
                if (!accepted) callback.onFailure("request_rejected");
                return accepted;
            } catch (RemoteException error) {
                callback.onFailure("remote_exception");
                return false;
            }
        }
    }

    private static final class ProofCollector
            implements LegacyPreprocessorSessionManager.TelemetryClient {
        final ArrayBlockingQueue<byte[]> proofs = new ArrayBlockingQueue<>(64);

        @Override
        public boolean report(
                int sessionId, long generation, byte[] capabilityNonce, byte[] snapshot) {
            if (snapshot == null || snapshot.length != 112) return false;
            return proofs.offer(snapshot.clone());
        }

        byte[] awaitProof(long timeoutMs) throws Exception {
            byte[] proof = proofs.poll(timeoutMs, TimeUnit.MILLISECONDS);
            assertNotNull("ECHT v2 proof timed out", proof);
            return proof;
        }
    }

    private static final class ObservedEffectFactory
            implements LegacyPreprocessorSessionManager.EffectFactory {
        final LegacyPreprocessorSessionManager.EffectFactory delegate =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory();
        final CountDownLatch active = new CountDownLatch(1);
        volatile int authorizeStatus = Integer.MIN_VALUE;
        volatile int enableStatus = Integer.MIN_VALUE;

        @Override
        public LegacyPreprocessorSessionManager.EffectHandle create(int sessionId) throws Exception {
            LegacyPreprocessorSessionManager.EffectHandle effect = delegate.create(sessionId);
            return new LegacyPreprocessorSessionManager.EffectHandle() {
                @Override
                public LegacyPreprocessorSessionManager.Descriptor descriptor() throws Exception {
                    return effect.descriptor();
                }

                @Override
                public boolean hasControl() throws Exception {
                    return effect.hasControl();
                }

                @Override
                public int setParameter(byte[] parameter, byte[] value) throws Exception {
                    int status = effect.setParameter(parameter, value);
                    if (Arrays.equals(
                            parameter, LegacyPreprocessorSessionManager.AUTHORIZE_PARAMETER)) {
                        authorizeStatus = status;
                        signalIfActive();
                    }
                    return status;
                }

                @Override
                public int getParameter(byte[] parameter, byte[] value) throws Exception {
                    return effect.getParameter(parameter, value);
                }

                @Override
                public int setEnabled(boolean enabled) throws Exception {
                    int status = effect.setEnabled(enabled);
                    if (enabled) {
                        enableStatus = status;
                        signalIfActive();
                    }
                    return status;
                }

                @Override
                public void release() throws Exception {
                    effect.release();
                }
            };
        }

        private void signalIfActive() {
            if (authorizeStatus == 0 && enableStatus == 0) active.countDown();
        }
    }

    private static final class DiagnosticCollector
            implements LegacyPreprocessorSessionManager.Diagnostics {
        private final List<String> values = new ArrayList<>();

        @Override
        public synchronized void report(String code, Throwable error) {
            values.add(code + (error != null ? ":" + error.getClass().getSimpleName() : ""));
        }

        synchronized org.json.JSONArray asJson() {
            org.json.JSONArray output = new org.json.JSONArray();
            for (String value : values) output.put(value);
            return output;
        }
    }

    private static final class CapabilityResult {
        final int status;
        final long generation;
        final byte[] envelope;
        final String diagnostic;

        CapabilityResult(int status, long generation, byte[] envelope, String diagnostic) {
            this.status = status;
            this.generation = generation;
            this.envelope = envelope;
            this.diagnostic = diagnostic;
        }
    }

    private static final class ProofView {
        final int sessionId;
        final long generation;
        final byte[] nonce;
        final long sequence;
        final int flags;
        final long blocks;
        final long frames;
        final long failures;
        final long mutations;
        final byte[] keyId;

        ProofView(
                int sessionId,
                long generation,
                byte[] nonce,
                long sequence,
                int flags,
                long blocks,
                long frames,
                long failures,
                long mutations,
                byte[] keyId) {
            this.sessionId = sessionId;
            this.generation = generation;
            this.nonce = nonce;
            this.sequence = sequence;
            this.flags = flags;
            this.blocks = blocks;
            this.frames = frames;
            this.failures = failures;
            this.mutations = mutations;
            this.keyId = keyId;
        }

        static ProofView decode(byte[] raw) {
            assertNotNull(raw);
            assertEquals(112, raw.length);
            ByteBuffer value = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            assertEquals(0x45434854, value.getInt());
            assertEquals(2, Short.toUnsignedInt(value.getShort()));
            assertEquals(2, Short.toUnsignedInt(value.getShort()));
            assertEquals(112, Short.toUnsignedInt(value.getShort()));
            int flags = Short.toUnsignedInt(value.getShort());
            int session = value.getInt();
            long generation = value.getLong();
            byte[] nonce = new byte[16];
            value.get(nonce);
            long sequence = Integer.toUnsignedLong(value.getInt());
            long blocks = Integer.toUnsignedLong(value.getInt());
            long frames = Integer.toUnsignedLong(value.getInt());
            long failures = Integer.toUnsignedLong(value.getInt());
            long mutations = Integer.toUnsignedLong(value.getInt());
            byte[] keyId = new byte[16];
            value.get(keyId);
            assertEquals(0, value.getInt());
            byte[] tag = new byte[32];
            value.get(tag);
            assertTrue("HMAC tag must be nonzero", Arrays.stream(toIntArray(tag)).anyMatch(v -> v != 0));
            return new ProofView(
                    session,
                    generation,
                    nonce,
                    sequence,
                    flags,
                    blocks,
                    frames,
                    failures,
                    mutations,
                    keyId);
        }

        private static int[] toIntArray(byte[] value) {
            int[] output = new int[value.length];
            for (int i = 0; i < value.length; i++) output[i] = value[i] & 0xff;
            return output;
        }
    }

    private static final class AudioCapture {
        final short[] samples;
        final double rms;
        final int peak;
        final double toneMagnitude;

        AudioCapture(short[] samples, double rms, int peak, double toneMagnitude) {
            this.samples = samples;
            this.rms = rms;
            this.peak = peak;
            this.toneMagnitude = toneMagnitude;
        }

        static AudioCapture measure(short[] samples) {
            double squares = 0.0;
            int peak = 0;
            double real = 0.0;
            double imaginary = 0.0;
            for (int i = 0; i < samples.length; i++) {
                double sample = samples[i];
                squares += sample * sample;
                peak = Math.max(peak, Math.abs((int) samples[i]));
                double phase = 2.0 * Math.PI * 1_000.0 * i / SAMPLE_RATE;
                real += sample * Math.cos(phase);
                imaginary -= sample * Math.sin(phase);
            }
            double rms = Math.sqrt(squares / samples.length);
            double magnitude = 2.0 * Math.hypot(real, imaginary) / samples.length;
            return new AudioCapture(samples, rms, peak, magnitude);
        }
    }
}
