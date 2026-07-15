package com.echidna.magisk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** Pure policy for the per-install telemetry-origin HMAC key and its derived copies. */
final class TelemetryProofKeyPolicy {
    static final int KEY_BYTES = 32;
    static final int MAX_METADATA_BYTES = 256;
    static final String METADATA_VERSION = "1";

    private TelemetryProofKeyPolicy() {}

    static byte[] generate(SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException("secure random source is required");
        }
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        requireKey(key, "generated");
        return key;
    }

    static void requireKey(byte[] key, String label) {
        if (key == null || key.length != KEY_BYTES) {
            throw new SecurityException(label + " telemetry proof key must be exactly 32 bytes");
        }
        int nonzero = 0;
        for (byte value : key) {
            nonzero |= value & 0xff;
        }
        if (nonzero == 0) {
            throw new SecurityException(label + " telemetry proof key must not be all zero");
        }
    }

    static Evidence evidence(byte[] key) throws Exception {
        requireKey(key, "authoritative");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
        String sha256 = hex(digest);
        return new Evidence(sha256, sha256.substring(0, 16));
    }

    static byte[] metadata(byte[] key) throws Exception {
        Evidence evidence = evidence(key);
        return ("version=" + METADATA_VERSION + "\n"
                + "sha256=" + evidence.sha256 + "\n"
                + "key_id=" + evidence.keyId + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    static Evidence verifyMetadata(byte[] encoded, byte[] key) throws Exception {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_METADATA_BYTES) {
            throw new SecurityException("telemetry proof metadata size is invalid");
        }
        for (byte value : encoded) {
            if (value == 0 || (value & 0x80) != 0) {
                throw new SecurityException("telemetry proof metadata is not strict ASCII");
            }
        }
        Map<String, String> values = new HashMap<>();
        String text = new String(encoded, StandardCharsets.US_ASCII);
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0
                    || values.put(line.substring(0, separator),
                            line.substring(separator + 1)) != null) {
                throw new SecurityException("telemetry proof metadata is malformed or duplicated");
            }
        }
        if (values.size() != 3 || !METADATA_VERSION.equals(values.get("version"))) {
            throw new SecurityException("telemetry proof metadata version/shape is invalid");
        }
        Evidence expected = evidence(key);
        if (!expected.sha256.equals(values.get("sha256"))
                || !expected.keyId.equals(values.get("key_id"))) {
            throw new SecurityException(
                    "telemetry root pin metadata mismatch; silent rotation refused");
        }
        if (!MessageDigest.isEqual(encoded, metadata(key))) {
            throw new SecurityException("telemetry proof metadata is not canonical");
        }
        return expected;
    }

    static CopyPlan copies(byte[] rootPin, byte[] appCopy, byte[] effectCopy) {
        requireKey(rootPin, "root-pinned");
        boolean restoreApp = !matches(rootPin, appCopy);
        boolean restoreEffect = !matches(rootPin, effectCopy);
        String status;
        if (restoreApp && restoreEffect) {
            status = "restored-app-and-staged-effect";
        } else if (restoreApp) {
            status = "restored-app";
        } else if (restoreEffect) {
            status = "staged-effect-next-boot";
        } else {
            status = "ready";
        }
        return new CopyPlan(restoreApp, restoreEffect, status, restoreEffect);
    }

    private static boolean matches(byte[] expected, byte[] actual) {
        return actual != null
                && actual.length == KEY_BYTES
                && MessageDigest.isEqual(expected, actual);
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    static final class Evidence {
        final String sha256;
        final String keyId;

        Evidence(String sha256, String keyId) {
            this.sha256 = sha256;
            this.keyId = keyId;
        }
    }

    static final class CopyPlan {
        final boolean restoreApp;
        final boolean restoreEffect;
        final String status;
        final boolean rebootRequired;

        CopyPlan(boolean restoreApp, boolean restoreEffect, String status, boolean rebootRequired) {
            this.restoreApp = restoreApp;
            this.restoreEffect = restoreEffect;
            this.status = status;
            this.rebootRequired = rebootRequired;
        }
    }
}
