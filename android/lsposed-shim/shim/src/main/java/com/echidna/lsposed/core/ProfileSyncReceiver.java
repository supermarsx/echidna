package com.echidna.lsposed.core;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * Receives policy snapshots pushed by the control service over the
 * ProfileSyncBridge contract and forwards them to {@link ProfileSnapshotStore}.
 *
 * <p>This is the LSPosed-side counterpart of the native {@code ProfileSyncServer}
 * (native/zygisk/src/runtime/profile_sync_server.cpp). It binds the same
 * filesystem AF_UNIX socket ({@link #SOCKET_PATH}) and speaks the identical wire
 * framing produced by {@code ProfileSyncBridge.kt}: a 4-byte big-endian length
 * prefix followed by the UTF-8 JSON snapshot, optionally delivered as an Ashmem
 * file descriptor over SCM_RIGHTS. The control service's existing pusher needs
 * no change.
 *
 * <p>No policy is ever fabricated: any bind/read failure leaves the last
 * snapshot in place (initially {@link ProfileSnapshot#empty()}), so hook
 * resolution stays fail-closed.
 */
final class ProfileSyncReceiver {

    private static final String TAG = "EchidnaProfileSync";
    private static final String SOCKET_PATH = "/data/local/tmp/echidna_profiles.sock";
    private static final int MAX_PAYLOAD_BYTES = 10 * 1024 * 1024;
    private static final int LISTEN_BACKLOG = 4;

    private final ProfileSnapshotStore store;

    ProfileSyncReceiver(ProfileSnapshotStore store) {
        this.store = store;
    }

    void start() {
        Thread thread = new Thread(this::run, "echidna-profile-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        LocalServerSocket server = bindServer();
        if (server == null) {
            XposedBridge.log(TAG + ": unable to bind profile-sync socket; policy stays fail-closed");
            return;
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try (LocalSocket client = server.accept()) {
                    handleClient(client);
                } catch (IOException e) {
                    XposedBridge.log(TAG + ": accept failed: " + Log.getStackTraceString(e));
                    break;
                }
            }
        } finally {
            try {
                server.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try {
            String payload = readFramedPayload(client.getInputStream());
            if (isBlank(payload)) {
                payload = readFromAncillaryFd(client);
            }
            if (!isBlank(payload)) {
                store.update(ProfileSnapshot.parse(payload));
            }
        } catch (IOException e) {
            XposedBridge.log(TAG + ": failed reading snapshot: " + Log.getStackTraceString(e));
        }
    }

    /** Reads a {@code [4-byte big-endian length][payload]} frame, matching ProfileSyncBridge. */
    private String readFramedPayload(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int length;
        try {
            length = in.readInt();
        } catch (IOException endOfStream) {
            return null;
        }
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            return null;
        }
        byte[] buffer = new byte[length];
        in.readFully(buffer);
        return new String(buffer, StandardCharsets.UTF_8);
    }

    /**
     * Fallback path mirroring the native {@code ReadFromSharedFd}: when no inline
     * payload arrived, read the framed snapshot from the Ashmem region handed over
     * as an ancillary file descriptor.
     */
    private String readFromAncillaryFd(LocalSocket client) {
        try {
            FileDescriptor[] fds = client.getAncillaryFileDescriptors();
            if (fds == null) {
                return null;
            }
            for (FileDescriptor fd : fds) {
                if (fd == null) {
                    continue;
                }
                try (FileInputStream fis = new FileInputStream(fd)) {
                    String payload = readFramedPayload(fis);
                    if (!isBlank(payload)) {
                        return payload;
                    }
                }
            }
        } catch (IOException e) {
            XposedBridge.log(TAG + ": ancillary fd read failed: " + Log.getStackTraceString(e));
        }
        return null;
    }

    private LocalServerSocket bindServer() {
        try {
            // Mirror the native ::unlink(kSocketPath) so a stale node does not block bind.
            new File(SOCKET_PATH).delete();
            FileDescriptor fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0);
            if (!bindFileSystem(fd, SOCKET_PATH)) {
                return null;
            }
            Os.listen(fd, LISTEN_BACKLOG);
            return new LocalServerSocket(fd);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": socket bind failed: " + Log.getStackTraceString(t));
            return null;
        }
    }

    /**
     * Binds {@code fd} to a filesystem AF_UNIX path. {@code Os.bind(FileDescriptor,
     * SocketAddress)} and {@code android.system.UnixSocketAddress} are not stable
     * public API across the supported range (min SDK 26), so they are reached
     * reflectively — the shim already runs with hidden-API access under LSPosed.
     * Returns false on any failure (leaving policy fail-closed).
     */
    private boolean bindFileSystem(FileDescriptor fd, String path) {
        try {
            Class<?> addressClass = Class.forName("android.system.UnixSocketAddress");
            Method createFileSystem = addressClass.getMethod("createFileSystem", String.class);
            Object address = createFileSystem.invoke(null, path);
            Method bind = Os.class.getMethod("bind", FileDescriptor.class, SocketAddress.class);
            bind.invoke(null, fd, address);
            return true;
        } catch (ReflectiveOperationException e) {
            XposedBridge.log(TAG + ": filesystem bind unavailable: " + Log.getStackTraceString(e));
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
