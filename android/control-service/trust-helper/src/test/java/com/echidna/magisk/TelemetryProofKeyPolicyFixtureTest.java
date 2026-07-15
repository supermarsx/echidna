package com.echidna.magisk;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** Dependency-free fixtures for telemetry-proof key generation and copy policy. */
public final class TelemetryProofKeyPolicyFixtureTest {
    private TelemetryProofKeyPolicyFixtureTest() {}

    public static void main(String[] arguments) throws Exception {
        byte[] generated = TelemetryProofKeyPolicy.generate(new FixedRandom());
        require(generated.length == 32);
        byte[] metadata = TelemetryProofKeyPolicy.metadata(generated);
        TelemetryProofKeyPolicy.Evidence evidence =
                TelemetryProofKeyPolicy.verifyMetadata(metadata, generated);
        String metadataText = new String(metadata, StandardCharsets.US_ASCII);
        require(metadataText.contains("sha256=" + evidence.sha256));
        require(metadataText.contains("key_id=" + evidence.keyId));
        require(!metadataText.contains("telemetry-fixture-secret"));

        TelemetryProofKeyPolicy.CopyPlan first =
                TelemetryProofKeyPolicy.copies(generated, null, null);
        require(first.restoreApp && first.restoreEffect && first.rebootRequired);
        require("restored-app-and-staged-effect".equals(first.status));

        TelemetryProofKeyPolicy.CopyPlan idempotent =
                TelemetryProofKeyPolicy.copies(generated, generated.clone(), generated.clone());
        require(!idempotent.restoreApp && !idempotent.restoreEffect);
        require(!idempotent.rebootRequired && "ready".equals(idempotent.status));

        byte[] tampered = generated.clone();
        tampered[0] ^= 0x55;
        TelemetryProofKeyPolicy.CopyPlan appTamper =
                TelemetryProofKeyPolicy.copies(generated, tampered, generated);
        require(appTamper.restoreApp && !appTamper.restoreEffect && !appTamper.rebootRequired);
        TelemetryProofKeyPolicy.CopyPlan effectTamper =
                TelemetryProofKeyPolicy.copies(generated, generated, tampered);
        require(!effectTamper.restoreApp && effectTamper.restoreEffect
                && effectTamper.rebootRequired);
        TelemetryProofKeyPolicy.CopyPlan dataClearRestore =
                TelemetryProofKeyPolicy.copies(generated, null, generated);
        require(dataClearRestore.restoreApp && !dataClearRestore.restoreEffect
                && !dataClearRestore.rebootRequired
                && "restored-app".equals(dataClearRestore.status));

        byte[] rotated = generated.clone();
        rotated[1] ^= 0x33;
        rejects(() -> TelemetryProofKeyPolicy.verifyMetadata(metadata, rotated));
        rejects(() -> TelemetryProofKeyPolicy.requireKey(new byte[31], "root-pinned"));
        rejects(() -> TelemetryProofKeyPolicy.requireKey(new byte[33], "root-pinned"));
        rejects(() -> TelemetryProofKeyPolicy.requireKey(new byte[32], "root-pinned"));
        byte[] malformed = Arrays.copyOf(metadata, metadata.length);
        malformed[0] = (byte) 0xff;
        rejects(() -> TelemetryProofKeyPolicy.verifyMetadata(malformed, generated));

        System.out.println(
                "telemetry proof key fixtures: generation/idempotence/tamper/restore/rotation PASS");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("fixture requirement failed");
        }
    }

    private static void rejects(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("fixture unexpectedly accepted invalid input");
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception expected) {
            // Expected rejection.
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FixedRandom extends SecureRandom {
        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) index;
            }
        }
    }
}
