package com.echidna.magisk;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

/** Dependency-free host fixtures for API 26, 28, and 33 policy branches. */
public final class TrustPolicyFixtureTest {
    private TrustPolicyFixtureTest() {}

    public static void main(String[] arguments) throws Exception {
        byte[] current = "release-current".getBytes(StandardCharsets.UTF_8);
        byte[] past = "release-past".getBytes(StandardCharsets.UTF_8);
        String expected = SignerPolicy.sha256(current);

        require(SignerPolicy.verify(
                        26, new byte[][] {current}, new byte[0][], false, expected, false)
                .lineageCount == 1);
        require(SignerPolicy.verify(
                        28,
                        new byte[][] {current},
                        new byte[][] {past, current},
                        false,
                        expected,
                        false)
                .lineageCount == 2);
        require(SignerPolicy.verify(
                        33,
                        new byte[][] {current},
                        new byte[][] {current},
                        false,
                        expected,
                        false)
                .currentDigest.equals(expected));

        rejects(() -> SignerPolicy.verify(
                33, new byte[][] {current}, new byte[][] {current}, true, expected, false));
        rejects(() -> SignerPolicy.verify(
                33, new byte[][] {current, past}, new byte[][] {current}, false, expected, false));
        rejects(() -> SignerPolicy.verify(
                28, new byte[][] {current}, new byte[][] {past}, false, expected, false));
        rejects(() -> SignerPolicy.verify(
                28,
                new byte[][] {current},
                new byte[][] {current, current},
                false,
                expected,
                false));
        rejects(() -> SignerPolicy.verify(
                34, new byte[][] {current}, new byte[][] {current}, false, expected, false));
        rejects(() -> SignerPolicy.verify(
                26, new byte[][] {current}, new byte[0][], false, "*", false));
        rejects(() -> SignerPolicy.verify(
                26,
                new byte[][] {current},
                new byte[0][],
                false,
                "0000000000000000000000000000000000000000000000000000000000000000",
                false));
        rejects(() -> SignerPolicy.verify(
                26, new byte[][] {past}, new byte[0][], false, expected, false));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair p256 = generator.generateKeyPair();
        require(SpkiPolicy.verifyP256(p256.getPublic().getEncoded()).length() == 64);
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384 = generator.generateKeyPair();
        rejects(() -> SpkiPolicy.verifyP256(p384.getPublic().getEncoded()));
        rejects(() -> SpkiPolicy.verifyP256(new byte[0]));
        rejects(() -> SpkiPolicy.verifyP256(new byte[SpkiPolicy.MAX_SPKI_BYTES + 1]));

        System.out.println("trust policy fixtures: API26/API28/API33/P-256 PASS");
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
}
