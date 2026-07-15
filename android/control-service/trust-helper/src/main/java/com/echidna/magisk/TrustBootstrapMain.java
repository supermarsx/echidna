package com.echidna.magisk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Root-owned app_process entry point for release signer verification and next-boot SPKI pinning. */
public final class TrustBootstrapMain {
    private static final String PACKAGE_NAME = "com.echidna.app";
    private static final String KEY_SUFFIX =
            "/files/echidna/preprocessor_controller_p256.spki";
    private static final int ANDROID_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;

    private TrustBootstrapMain() {}

    public static void main(String[] arguments) {
        try {
            Options options = Options.parse(arguments);
            Inspection inspection = inspect(options);
            System.out.println("ECHIDNA_TRUST_V1");
            System.out.println("status=" + inspection.pinStatus);
            System.out.println("package=" + PACKAGE_NAME);
            System.out.println("uid=" + inspection.uid);
            System.out.println("user=0");
            System.out.println("data_dir=" + inspection.dataDir);
            System.out.println("signer_sha256_prefix=" + inspection.signerDigest.substring(0, 12));
            System.out.println("lineage_count=" + inspection.lineageCount);
            System.out.println("spki_sha256_prefix=" + inspection.spkiDigest.substring(0, 12));
            System.out.println("reboot_required=" + inspection.rebootRequired);
        } catch (Throwable failure) {
            String message = failure.getMessage();
            if (message == null || message.isEmpty()) {
                message = failure.getClass().getSimpleName();
            }
            System.err.println("ECHIDNA_TRUST_ERROR=" + message.replace('\n', ' '));
            System.exit(2);
        }
    }

    private static Inspection inspect(Options options) throws Exception {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < SignerPolicy.MIN_SDK || sdk > SignerPolicy.MAX_SDK) {
            throw new IllegalArgumentException("unsupported Android SDK " + sdk + "; expected 26..33");
        }
        boolean developmentMode = "development".equals(options.mode);
        String expectedDigest = readExpectedDigest(options.expectedFile);

        Context context = systemContext();
        PackageManager packageManager = context.getPackageManager();
        int flags = sdk >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo packageInfo = packageManager.getPackageInfo(PACKAGE_NAME, flags);
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (applicationInfo == null || !applicationInfo.enabled) {
            throw new SecurityException("companion package is absent or disabled");
        }
        int uid = applicationInfo.uid;
        if (uid < FIRST_APPLICATION_UID || uid >= ANDROID_USER_RANGE) {
            throw new SecurityException("companion UID is not a user-0 application UID: " + uid);
        }
        String dataDir = requireDataDir(applicationInfo.dataDir);

        byte[][] current;
        byte[][] history;
        boolean multiple;
        if (sdk <= 27) {
            current = encode(packageInfo.signatures);
            history = new byte[0][];
            multiple = false;
        } else {
            ModernSignerReader.Evidence evidence = ModernSignerReader.read(packageInfo);
            current = evidence.current;
            history = evidence.history;
            multiple = evidence.multiple;
        }
        SignerPolicy.Result signer = SignerPolicy.verify(
                sdk, current, history, multiple, expectedDigest, developmentMode);

        String sourcePath = dataDir + KEY_SUFFIX;
        byte[] spki = readSafeAppSpki(sourcePath, uid);
        String spkiDigest = SpkiPolicy.verifyP256(spki);
        PinResult pin = pinForNextBoot(spki, options.pendingKey, options.activeKey);
        return new Inspection(
                uid,
                dataDir,
                signer.currentDigest,
                signer.lineageCount,
                spkiDigest,
                pin.status,
                pin.rebootRequired);
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Object context = getSystemContext.invoke(thread);
        if (!(context instanceof Context)) {
            throw new IllegalStateException("ActivityThread did not provide a system Context");
        }
        return (Context) context;
    }

    private static String requireDataDir(String dataDir) {
        String modern = "/data/user/0/" + PACKAGE_NAME;
        String legacy = "/data/data/" + PACKAGE_NAME;
        if (!modern.equals(dataDir) && !legacy.equals(dataDir)) {
            throw new SecurityException("unexpected companion dataDir: " + dataDir);
        }
        return dataDir;
    }

    private static String readExpectedDigest(String path) throws Exception {
        byte[] encoded = readSafeRootFile(path, 128, false);
        String raw = new String(encoded, StandardCharsets.US_ASCII);
        if (raw.endsWith("\n")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("release certificate pin must contain one line");
        }
        return SignerPolicy.requireNormalizedDigest(raw);
    }

    private static byte[] readSafeAppSpki(String path, int ownerUid) throws Exception {
        File parent = new File(path).getParentFile();
        if (parent == null) {
            throw new SecurityException("SPKI parent directory is missing");
        }
        StructStat directory = Os.lstat(parent.getAbsolutePath());
        if (!OsConstants.S_ISDIR(directory.st_mode)
                || directory.st_uid != ownerUid
                || (directory.st_mode & 0777) != 0700) {
            throw new SecurityException("SPKI parent must be UID-owned mode 0700 without symlinks");
        }
        return readSafeFile(path, ownerUid, 0600, SpkiPolicy.MAX_SPKI_BYTES, true);
    }

    private static byte[] readSafeRootFile(String path, int maximumBytes, boolean exactMode)
            throws Exception {
        return readSafeFile(path, 0, exactMode ? 0444 : -1, maximumBytes, exactMode);
    }

    private static byte[] readSafeFile(
            String path, int ownerUid, int requiredMode, int maximumBytes, boolean exactMode)
            throws Exception {
        FileDescriptor descriptor = null;
        try {
            StructStat linkStatus = Os.lstat(path);
            if (!OsConstants.S_ISREG(linkStatus.st_mode)) {
                throw new SecurityException("file is not regular or is a symlink: " + path);
            }
            descriptor = Os.open(
                    path,
                    OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                    0);
            StructStat before = Os.fstat(descriptor);
            int permissions = before.st_mode & 0777;
            boolean safeMode = exactMode
                    ? permissions == requiredMode
                    : (permissions & 0022) == 0;
            if (!OsConstants.S_ISREG(before.st_mode)
                    || before.st_uid != ownerUid
                    || !safeMode
                    || before.st_size <= 0
                    || before.st_size > maximumBytes) {
                throw new SecurityException("unsafe owner, mode, type, or size for " + path);
            }
            byte[] contents = new byte[(int) before.st_size];
            int received = 0;
            while (received < contents.length) {
                int count = Os.read(descriptor, contents, received, contents.length - received);
                if (count <= 0) {
                    throw new SecurityException("short read from " + path);
                }
                received += count;
            }
            StructStat after = Os.fstat(descriptor);
            if (after.st_dev != before.st_dev
                    || after.st_ino != before.st_ino
                    || after.st_uid != before.st_uid
                    || after.st_mode != before.st_mode
                    || after.st_size != before.st_size) {
                throw new SecurityException("file changed while being verified: " + path);
            }
            return contents;
        } finally {
            if (descriptor != null) {
                Os.close(descriptor);
            }
        }
    }

    private static PinResult pinForNextBoot(byte[] spki, String pendingPath, String activePath)
            throws Exception {
        if (existsNoFollow(activePath)) {
            requireMatchingPinnedKey(activePath, spki, "active");
            return new PinResult("active-match", false);
        }
        if (existsNoFollow(pendingPath)) {
            requireMatchingPinnedKey(pendingPath, spki, "pending");
            return new PinResult("already-pinned", true);
        }

        File parent = new File(pendingPath).getParentFile();
        if (parent == null) {
            throw new SecurityException("pending trust directory is missing");
        }
        StructStat parentStatus = Os.lstat(parent.getAbsolutePath());
        if (!OsConstants.S_ISDIR(parentStatus.st_mode)
                || parentStatus.st_uid != 0
                || (parentStatus.st_mode & 0777) != 0700) {
            throw new SecurityException("pending trust directory must be root-owned mode 0700");
        }

        String temporary = pendingPath + ".tmp." + Os.getpid();
        FileDescriptor output = null;
        try {
            output = Os.open(
                    temporary,
                    OsConstants.O_WRONLY
                            | OsConstants.O_CREAT
                            | OsConstants.O_EXCL
                            | OsConstants.O_CLOEXEC
                            | OsConstants.O_NOFOLLOW,
                    0600);
            int written = 0;
            while (written < spki.length) {
                int count = Os.write(output, spki, written, spki.length - written);
                if (count <= 0) {
                    throw new SecurityException("short write while pinning SPKI");
                }
                written += count;
            }
            Os.fsync(output);
            Os.fchown(output, 0, 0);
            Os.fchmod(output, 0444);
            Os.fsync(output);
            Os.close(output);
            output = null;
            Os.rename(temporary, pendingPath);
            fsyncDirectory(parent.getAbsolutePath());
        } catch (Throwable failure) {
            // Best-effort cleanup; the O_EXCL name cannot replace the final pin.
            new File(temporary).delete();
            throw failure;
        } finally {
            if (output != null) {
                Os.close(output);
            }
        }
        requireMatchingPinnedKey(pendingPath, spki, "new pending");
        return new PinResult("pinned-next-boot", true);
    }

    private static void requireMatchingPinnedKey(String path, byte[] expected, String label)
            throws Exception {
        byte[] existing = readSafeRootFile(path, SpkiPolicy.MAX_SPKI_BYTES, true);
        SpkiPolicy.verifyP256(existing);
        if (!MessageDigest.isEqual(existing, expected)) {
            throw new SecurityException(label + " SPKI mismatch; silent key rotation refused");
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
        FileDescriptor directory = Os.open(
                path,
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC,
                0);
        try {
            Os.fsync(directory);
        } finally {
            Os.close(directory);
        }
    }

    private static byte[][] encode(Signature[] signatures) {
        if (signatures == null) {
            return null;
        }
        byte[][] result = new byte[signatures.length][];
        for (int index = 0; index < signatures.length; index++) {
            result[index] = signatures[index] == null ? null : signatures[index].toByteArray();
        }
        return result;
    }

    private static final class Options {
        final String expectedFile;
        final String mode;
        final String pendingKey;
        final String activeKey;

        Options(String expectedFile, String mode, String pendingKey, String activeKey) {
            this.expectedFile = expectedFile;
            this.mode = mode;
            this.pendingKey = pendingKey;
            this.activeKey = activeKey;
        }

        static Options parse(String[] arguments) {
            if (arguments.length != 8
                    || !"--expected-file".equals(arguments[0])
                    || !"--mode".equals(arguments[2])
                    || !"--pending".equals(arguments[4])
                    || !"--active".equals(arguments[6])) {
                throw new IllegalArgumentException(
                        "expected --expected-file PATH --mode MODE --pending PATH --active PATH");
            }
            if (!"production".equals(arguments[3])
                    && !"development".equals(arguments[3])) {
                throw new IllegalArgumentException("trust mode must be production or development");
            }
            for (int index : new int[] {1, 5, 7}) {
                if (!arguments[index].startsWith("/")) {
                    throw new IllegalArgumentException("trust paths must be absolute");
                }
            }
            return new Options(arguments[1], arguments[3], arguments[5], arguments[7]);
        }
    }

    private static final class Inspection {
        final int uid;
        final String dataDir;
        final String signerDigest;
        final int lineageCount;
        final String spkiDigest;
        final String pinStatus;
        final boolean rebootRequired;

        Inspection(
                int uid,
                String dataDir,
                String signerDigest,
                int lineageCount,
                String spkiDigest,
                String pinStatus,
                boolean rebootRequired) {
            this.uid = uid;
            this.dataDir = dataDir;
            this.signerDigest = signerDigest;
            this.lineageCount = lineageCount;
            this.spkiDigest = spkiDigest;
            this.pinStatus = pinStatus;
            this.rebootRequired = rebootRequired;
        }
    }

    private static final class PinResult {
        final String status;
        final boolean rebootRequired;

        PinResult(String status, boolean rebootRequired) {
            this.status = status;
            this.rebootRequired = rebootRequired;
        }
    }
}
