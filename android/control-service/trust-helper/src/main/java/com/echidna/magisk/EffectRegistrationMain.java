package com.echidna.magisk;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Root-only app_process entry point that stages a legacy effect registry for the next boot. */
public final class EffectRegistrationMain {
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LIBRARY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_KEY_BYTES = 512;

    private EffectRegistrationMain() {}

    public static void main(String[] arguments) {
        try {
            Options options = Options.parse(arguments);
            stage(options);
        } catch (DriftException drift) {
            System.err.println("ECHIDNA_EFFECT_REGISTRATION_DRIFT=" + safe(drift.getMessage()));
            System.exit(3);
        } catch (Throwable failure) {
            String message = failure.getMessage();
            if (message == null || message.isEmpty()) {
                message = failure.getClass().getSimpleName();
            }
            System.err.println("ECHIDNA_EFFECT_REGISTRATION_ERROR=" + safe(message));
            System.exit(2);
        }
    }

    private static void stage(Options options) throws Exception {
        byte[] source = readSafeRootFile(options.source, MAX_CONFIG_BYTES, false);
        byte[] library = readSafeRootFile(options.librarySource, MAX_LIBRARY_BYTES, false);
        byte[] key = readSafeRootFile(options.pendingKey, MAX_KEY_BYTES, true);
        validateElf(library, options.abi, options.bits);
        SpkiPolicy.verifyP256(key);

        String sourceHash = sha256(source);
        ExistingState existing = readExistingState(options);
        if (existing != null) {
            if (!options.fingerprint.equals(existing.fingerprint)) {
                throw new DriftException("build fingerprint changed since overlay staging");
            }
            if (!sourceHash.equals(existing.sourceHash)
                    && !sourceHash.equals(existing.overlayHash)) {
                throw new DriftException("active effect configuration no longer matches source hash");
            }
            requireDigest(options.configOutput, existing.overlayHash, MAX_CONFIG_BYTES);
            requireDigest(options.libraryOutput, existing.libraryHash, MAX_LIBRARY_BYTES);
            requireDigest(options.activeKey, existing.keyHash, MAX_KEY_BYTES);
            System.out.println("ECHIDNA_EFFECT_REGISTRATION_V1");
            System.out.println("status=already-staged");
            System.out.println("reboot_required=true");
            return;
        }
        refuseUntrackedOutput(options.configOutput);
        refuseUntrackedOutput(options.libraryOutput);
        refuseUntrackedOutput(options.activeKey);

        String decoded = decodeUtf8(source);
        String legacyPath = "/" + options.partition + "/lib"
                + (options.bits == 64 ? "64" : "")
                + "/soundfx/" + EffectConfigMerger.LIBRARY_FILE;
        EffectConfigMerger.Result merged = EffectConfigMerger.merge(
                decoded, options.format, legacyPath);
        byte[] overlay = merged.contents.getBytes(StandardCharsets.UTF_8);
        String overlayHash = sha256(overlay);
        String libraryHash = sha256(library);
        String keyHash = sha256(key);

        boolean wroteConfig = false;
        boolean wroteLibrary = false;
        boolean wroteKey = false;
        try {
            ensureRootDirectory(new File(options.configOutput).getParentFile(), 0755);
            ensureRootDirectory(new File(options.libraryOutput).getParentFile(), 0755);
            ensureRootDirectory(new File(options.activeKey).getParentFile(), 0755);
            ensureRootDirectory(new File(options.metadata).getParentFile(), 0700);
            writeAtomic(options.configOutput, overlay, 0644);
            wroteConfig = true;
            writeAtomic(options.libraryOutput, library, 0644);
            wroteLibrary = true;
            writeAtomic(options.activeKey, key, 0444);
            wroteKey = true;
            String metadata = metadata(options, sourceHash, overlayHash, libraryHash, keyHash);
            writeAtomic(options.metadata, metadata.getBytes(StandardCharsets.US_ASCII), 0444);
        } catch (Throwable failure) {
            if (wroteConfig) {
                new File(options.configOutput).delete();
            }
            if (wroteLibrary) {
                new File(options.libraryOutput).delete();
            }
            if (wroteKey) {
                new File(options.activeKey).delete();
            }
            throw failure;
        }

        System.out.println("ECHIDNA_EFFECT_REGISTRATION_V1");
        System.out.println("status=staged-next-boot");
        System.out.println("source_sha256=" + sourceHash);
        System.out.println("overlay_sha256=" + overlayHash);
        System.out.println("partition=" + options.partition);
        System.out.println("host_bits=" + options.bits);
        System.out.println("format=" + (options.format == EffectConfigMerger.Format.XML ? "xml" : "conf"));
        System.out.println("type_uuid=" + EffectConfigMerger.TYPE_UUID);
        System.out.println("implementation_uuid=" + EffectConfigMerger.IMPLEMENTATION_UUID);
        System.out.println("auto_apply=false");
        System.out.println("reboot_required=true");
    }

    private static ExistingState readExistingState(Options options) throws Exception {
        if (!existsNoFollow(options.metadata)) {
            return null;
        }
        byte[] bytes = readSafeRootFile(options.metadata, 4096, true);
        Map<String, String> values = new HashMap<>();
        for (String line : decodeAscii(bytes).split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || values.put(line.substring(0, separator),
                    line.substring(separator + 1)) != null) {
                throw new SecurityException("registration metadata is malformed or duplicated");
            }
        }
        requireMetadata(values, "version", "1");
        requireMetadata(values, "source_path", options.source);
        requireMetadata(values, "config_output", options.configOutput);
        requireMetadata(values, "library_output", options.libraryOutput);
        requireMetadata(values, "active_key", options.activeKey);
        requireMetadata(values, "partition", options.partition);
        requireMetadata(values, "bits", Integer.toString(options.bits));
        requireMetadata(values, "abi", options.abi);
        requireMetadata(values, "format",
                options.format == EffectConfigMerger.Format.XML ? "xml" : "conf");
        requireMetadata(values, "type_uuid", EffectConfigMerger.TYPE_UUID);
        requireMetadata(values, "implementation_uuid", EffectConfigMerger.IMPLEMENTATION_UUID);
        requireMetadata(values, "auto_apply", "false");
        return new ExistingState(
                requiredDigest(values, "source_sha256"),
                requiredDigest(values, "overlay_sha256"),
                requiredDigest(values, "library_sha256"),
                requiredDigest(values, "key_sha256"),
                required(values, "fingerprint"));
    }

    private static String metadata(
            Options options,
            String sourceHash,
            String overlayHash,
            String libraryHash,
            String keyHash) {
        return "version=1\n"
                + "source_path=" + options.source + "\n"
                + "config_output=" + options.configOutput + "\n"
                + "library_output=" + options.libraryOutput + "\n"
                + "active_key=" + options.activeKey + "\n"
                + "partition=" + options.partition + "\n"
                + "bits=" + options.bits + "\n"
                + "abi=" + options.abi + "\n"
                + "format=" + (options.format == EffectConfigMerger.Format.XML ? "xml" : "conf") + "\n"
                + "source_sha256=" + sourceHash + "\n"
                + "overlay_sha256=" + overlayHash + "\n"
                + "library_sha256=" + libraryHash + "\n"
                + "key_sha256=" + keyHash + "\n"
                + "fingerprint=" + options.fingerprint + "\n"
                + "type_uuid=" + EffectConfigMerger.TYPE_UUID + "\n"
                + "implementation_uuid=" + EffectConfigMerger.IMPLEMENTATION_UUID + "\n"
                + "auto_apply=false\n";
    }

    private static void validateElf(byte[] bytes, String abi, int bits) {
        if (bytes.length < 20 || bytes[0] != 0x7f || bytes[1] != 'E'
                || bytes[2] != 'L' || bytes[3] != 'F') {
            throw new SecurityException("preprocessor payload is not ELF");
        }
        int expectedClass = bits == 64 ? 2 : 1;
        int expectedMachine;
        if ("arm64-v8a".equals(abi)) {
            expectedMachine = 183;
        } else if ("armeabi-v7a".equals(abi)) {
            expectedMachine = 40;
        } else if ("x86_64".equals(abi)) {
            expectedMachine = 62;
        } else {
            throw new SecurityException("unsupported preprocessor ABI: " + abi);
        }
        if ((bytes[4] & 0xff) != expectedClass || (bytes[5] & 0xff) != 1) {
            throw new SecurityException("preprocessor ELF class or byte order mismatch");
        }
        int machine = ByteBuffer.wrap(bytes, 18, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
        if (machine != expectedMachine) {
            throw new SecurityException("preprocessor ELF machine does not match " + abi);
        }
        if (!containsAscii(bytes, "AELI")) {
            throw new SecurityException("preprocessor ELF lacks the legacy AELI export marker");
        }
    }

    private static boolean containsAscii(byte[] bytes, String needle) {
        byte[] encoded = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int index = 0; index <= bytes.length - encoded.length; index++) {
            for (int offset = 0; offset < encoded.length; offset++) {
                if (bytes[index + offset] != encoded[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] readSafeRootFile(String path, int maximum, boolean exactMode)
            throws Exception {
        StructStat link = Os.lstat(path);
        if (!OsConstants.S_ISREG(link.st_mode)) {
            throw new SecurityException("file is not regular or is a symlink: " + path);
        }
        FileDescriptor descriptor = Os.open(
                path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
            StructStat before = Os.fstat(descriptor);
            int mode = before.st_mode & 0777;
            if (!OsConstants.S_ISREG(before.st_mode)
                    || before.st_uid != 0
                    || (exactMode ? mode != 0444 : (mode & 0022) != 0)
                    || before.st_size <= 0
                    || before.st_size > maximum) {
                throw new SecurityException("unsafe owner, mode, type, or size for " + path);
            }
            byte[] result = new byte[(int) before.st_size];
            int received = 0;
            while (received < result.length) {
                int count = Os.read(descriptor, result, received, result.length - received);
                if (count <= 0) {
                    throw new SecurityException("short read from " + path);
                }
                received += count;
            }
            StructStat after = Os.fstat(descriptor);
            if (after.st_dev != before.st_dev || after.st_ino != before.st_ino
                    || after.st_uid != before.st_uid || after.st_mode != before.st_mode
                    || after.st_size != before.st_size) {
                throw new SecurityException("file changed while being verified: " + path);
            }
            return result;
        } finally {
            Os.close(descriptor);
        }
    }

    private static void writeAtomic(String path, byte[] bytes, int mode) throws Exception {
        String temporary = path + ".tmp." + Os.getpid();
        FileDescriptor output = null;
        try {
            output = Os.open(temporary,
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                            | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                    0600);
            int written = 0;
            while (written < bytes.length) {
                int count = Os.write(output, bytes, written, bytes.length - written);
                if (count <= 0) {
                    throw new SecurityException("short write to " + temporary);
                }
                written += count;
            }
            Os.fsync(output);
            Os.fchown(output, 0, 0);
            Os.fchmod(output, mode);
            Os.fsync(output);
            Os.close(output);
            output = null;
            Os.rename(temporary, path);
            fsyncDirectory(new File(path).getParent());
        } catch (Throwable failure) {
            new File(temporary).delete();
            throw failure;
        } finally {
            if (output != null) {
                Os.close(output);
            }
        }
    }

    private static void ensureRootDirectory(File directory, int mode) throws Exception {
        if (directory == null) {
            throw new SecurityException("output directory is missing");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new SecurityException("unable to create output directory " + directory);
        }
        StructStat status = Os.lstat(directory.getAbsolutePath());
        if (!OsConstants.S_ISDIR(status.st_mode) || status.st_uid != 0
                || (status.st_mode & 0022) != 0) {
            throw new SecurityException("output directory is not root-owned and protected: " + directory);
        }
        Os.chmod(directory.getAbsolutePath(), mode);
    }

    private static void refuseUntrackedOutput(String path) throws Exception {
        if (existsNoFollow(path)) {
            throw new SecurityException("untracked registration output exists: " + path);
        }
    }

    private static void requireDigest(String path, String digest, int maximum) throws Exception {
        byte[] bytes = readSafeRootFile(path, maximum, path.endsWith(".spki"));
        if (!digest.equals(sha256(bytes))) {
            throw new DriftException("staged registration output changed: " + path);
        }
    }

    private static boolean existsNoFollow(String path) throws Exception {
        try {
            Os.lstat(path);
            return true;
        } catch (ErrnoException exception) {
            if (exception.errno == OsConstants.ENOENT) {
                return false;
            }
            throw exception;
        }
    }

    private static void fsyncDirectory(String path) throws Exception {
        FileDescriptor directory = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try {
            Os.fsync(directory);
        } finally {
            Os.close(directory);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String decodeAscii(byte[] bytes) {
        for (byte value : bytes) {
            if ((value & 0x80) != 0 || value == 0) {
                throw new SecurityException("registration metadata is not strict ASCII");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder output = new StringBuilder(64);
        for (byte value : digest) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    private static void requireMetadata(Map<String, String> values, String key, String expected) {
        String actual = required(values, key);
        if (!expected.equals(actual)) {
            throw new SecurityException("registration metadata mismatch for " + key);
        }
    }

    private static String requiredDigest(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new SecurityException("invalid registration digest: " + key);
        }
        return value;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) {
            throw new SecurityException("registration metadata is missing " + key);
        }
        return value;
    }

    private static String safe(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class Options {
        final String source;
        final int format;
        final String partition;
        final int bits;
        final String abi;
        final String librarySource;
        final String configOutput;
        final String libraryOutput;
        final String metadata;
        final String pendingKey;
        final String activeKey;
        final String fingerprint;

        Options(Map<String, String> values) throws Exception {
            source = absolute(values, "--source");
            format = EffectConfigMerger.Format.parse(required(values, "--format"));
            partition = required(values, "--partition");
            if (!"system".equals(partition) && !"vendor".equals(partition)) {
                throw new IllegalArgumentException("registration partition must be system or vendor");
            }
            bits = Integer.parseInt(required(values, "--bits"));
            if (bits != 32 && bits != 64) {
                throw new IllegalArgumentException("effect host bitness must be 32 or 64");
            }
            abi = required(values, "--abi");
            if (("armeabi-v7a".equals(abi) ? 32 : 64) != bits) {
                throw new IllegalArgumentException("ABI does not match effect host bitness");
            }
            librarySource = absolute(values, "--library-source");
            configOutput = absolute(values, "--config-output");
            libraryOutput = absolute(values, "--library-output");
            metadata = absolute(values, "--metadata");
            pendingKey = absolute(values, "--pending-key");
            activeKey = absolute(values, "--active-key");
            fingerprint = required(values, "--fingerprint");
            if (fingerprint.indexOf('\n') >= 0 || fingerprint.indexOf('\r') >= 0
                    || fingerprint.indexOf('=') >= 0 || fingerprint.length() > 512) {
                throw new IllegalArgumentException("invalid build fingerprint");
            }
            String filename = format == EffectConfigMerger.Format.XML
                    ? "audio_effects.xml" : "audio_effects.conf";
            boolean standardSource = source.equals("/" + partition + "/etc/" + filename);
            boolean vendorSkuSource = "vendor".equals(partition)
                    && source.matches("/vendor/etc/audio/sku_[A-Za-z0-9_.-]+/" + filename);
            if (!standardSource && !vendorSkuSource) {
                throw new IllegalArgumentException("source path does not match selected partition/format");
            }
            String sourceSuffix = source.substring(("/" + partition).length());
            String overlayPartition = "system".equals(partition)
                    ? "/system" : "/system/vendor";
            if (!configOutput.endsWith(overlayPartition + sourceSuffix)
                    || !libraryOutput.endsWith(overlayPartition + "/lib"
                            + (bits == 64 ? "64" : "") + "/soundfx/"
                            + EffectConfigMerger.LIBRARY_FILE)
                    || !activeKey.endsWith(
                            "/system/etc/echidna/preprocessor_controller_p256.spki")) {
                throw new IllegalArgumentException("registration output path violates module contract");
            }
        }

        static Options parse(String[] arguments) throws Exception {
            if (arguments.length != 24) {
                throw new IllegalArgumentException("effect registration expects 12 option/value pairs");
            }
            Map<String, String> values = new HashMap<>();
            for (int index = 0; index < arguments.length; index += 2) {
                if (!arguments[index].startsWith("--")
                        || values.put(arguments[index], arguments[index + 1]) != null) {
                    throw new IllegalArgumentException("duplicate or malformed effect registration option");
                }
            }
            String[] expected = {"--source", "--format", "--partition", "--bits", "--abi",
                    "--library-source", "--config-output", "--library-output", "--metadata",
                    "--pending-key", "--active-key", "--fingerprint"};
            if (values.size() != expected.length || !values.keySet().containsAll(Arrays.asList(expected))) {
                throw new IllegalArgumentException("unknown or missing effect registration option");
            }
            return new Options(values);
        }

        private static String absolute(Map<String, String> values, String key) {
            String value = required(values, key);
            if (!value.startsWith("/") || value.contains("/../") || value.endsWith("/..")) {
                throw new IllegalArgumentException(key + " must be an absolute normalized path");
            }
            return value;
        }
    }

    private static final class ExistingState {
        final String sourceHash;
        final String overlayHash;
        final String libraryHash;
        final String keyHash;
        final String fingerprint;

        ExistingState(String sourceHash, String overlayHash, String libraryHash,
                String keyHash, String fingerprint) {
            this.sourceHash = sourceHash;
            this.overlayHash = overlayHash;
            this.libraryHash = libraryHash;
            this.keyHash = keyHash;
            this.fingerprint = fingerprint;
        }
    }

    private static final class DriftException extends Exception {
        DriftException(String message) {
            super(message);
        }
    }
}
