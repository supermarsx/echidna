package com.echidna.magisk;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure signer policy shared by the host fixtures and the app_process entry point. */
public final class SignerPolicy {
    public static final int MIN_SDK = 26;
    public static final int MAX_SDK = 34;
    private static final int MAX_CERTIFICATE_BYTES = 64 * 1024;
    private static final int MAX_LINEAGE_CERTIFICATES = 8;
    private static final String KNOWN_DEBUG_CERTIFICATE =
            "b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b";

    private SignerPolicy() {}

    public static Result verify(
            int sdk,
            byte[][] currentSigners,
            byte[][] signingHistory,
            boolean multipleSigners,
            String expectedDigest,
            boolean developmentMode) {
        if (sdk < MIN_SDK || sdk > MAX_SDK) {
            throw new IllegalArgumentException("unsupported Android SDK " + sdk + "; expected 26..34");
        }
        String normalizedExpected = requireNormalizedDigest(expectedDigest);
        if (!developmentMode && KNOWN_DEBUG_CERTIFICATE.equals(normalizedExpected)) {
            throw new IllegalArgumentException("production signer pin is a forbidden Android debug certificate");
        }
        if (multipleSigners) {
            throw new IllegalArgumentException("APK must have exactly one current signer");
        }
        if (currentSigners == null || currentSigners.length != 1) {
            throw new IllegalArgumentException("PackageManager must return exactly one current signer");
        }
        byte[] current = requireCertificate(currentSigners[0], "current signer");
        String currentDigest = sha256(current);
        if (!constantTimeHexEquals(currentDigest, normalizedExpected)) {
            throw new SecurityException(
                    "current package signer does not match the embedded release certificate pin");
        }

        if (sdk <= 27) {
            return new Result(currentDigest, 1);
        }
        if (signingHistory == null
                || signingHistory.length == 0
                || signingHistory.length > MAX_LINEAGE_CERTIFICATES) {
            throw new IllegalArgumentException("SigningInfo lineage must contain 1..8 certificates");
        }
        Set<String> lineageDigests = new HashSet<>();
        int currentOccurrences = 0;
        for (int index = 0; index < signingHistory.length; index++) {
            String digest = sha256(requireCertificate(signingHistory[index], "lineage signer " + index));
            if (!lineageDigests.add(digest)) {
                throw new IllegalArgumentException("SigningInfo lineage contains a duplicate certificate");
            }
            if (constantTimeHexEquals(digest, currentDigest)) {
                currentOccurrences++;
            }
        }
        if (currentOccurrences != 1) {
            throw new IllegalArgumentException(
                    "SigningInfo lineage must contain the current signer exactly once");
        }
        return new Result(currentDigest, signingHistory.length);
    }

    public static String requireNormalizedDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "expected release certificate digest must be exactly 64 lowercase hex digits");
        }
        if (digest.matches("0{64}")) {
            throw new IllegalArgumentException("all-zero release certificate digest is forbidden");
        }
        return digest.toLowerCase(Locale.ROOT);
    }

    public static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                output.append(String.format(Locale.ROOT, "%02x", current & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static byte[] requireCertificate(byte[] certificate, String label) {
        if (certificate == null
                || certificate.length == 0
                || certificate.length > MAX_CERTIFICATE_BYTES) {
            throw new IllegalArgumentException(label + " has an invalid encoded size");
        }
        return certificate;
    }

    private static boolean constantTimeHexEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    public static final class Result {
        public final String currentDigest;
        public final int lineageCount;

        Result(String currentDigest, int lineageCount) {
            this.currentDigest = currentDigest;
            this.lineageCount = lineageCount;
        }
    }
}
