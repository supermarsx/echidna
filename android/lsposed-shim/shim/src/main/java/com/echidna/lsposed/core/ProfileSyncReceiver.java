package com.echidna.lsposed.core;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * Receives policy snapshots served by the control service over the
 * ProfileSyncBridge contract and forwards them to {@link ProfileSnapshotStore}.
 *
 * <p>This is the LSPosed-side counterpart of the native profile-sync reader
 * (native/zygisk/src/runtime/profile_sync_server.cpp). It connects to the
 * companion service's abstract AF_UNIX socket ({@link #SOCKET_NAME}) and reads
 * the same framing as the native path: a 4-byte big-endian length prefix
 * followed by the UTF-8 JSON snapshot.
 *
 * <p>No policy is ever fabricated: any connect/read failure leaves the last
 * snapshot in place (initially {@link ProfileSnapshot#empty()}), so hook
 * resolution stays fail-closed.
 */
final class ProfileSyncReceiver {

    private static final String TAG = "EchidnaProfileSync";
    private static final String SOCKET_NAME = "echidna_profiles";
    private static final int MAX_PAYLOAD_BYTES = 10 * 1024 * 1024;
    private static final long RECONNECT_DELAY_MS = 1000L;

    private final ProfileSnapshotStore store;
    private boolean loggedConnectFailure;

    ProfileSyncReceiver(ProfileSnapshotStore store) {
        this.store = store;
    }

    void start() {
        Thread thread = new Thread(this::run, "echidna-profile-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try (LocalSocket socket = connectPublisher()) {
                loggedConnectFailure = false;
                readSnapshots(socket);
            } catch (IOException e) {
                if (!loggedConnectFailure) {
                    loggedConnectFailure = true;
                    XposedBridge.log(
                            TAG + ": unable to connect profile-sync socket; policy stays "
                                    + "fail-closed: " + Log.getStackTraceString(e));
                }
                sleepBeforeReconnect();
            }
        }
    }

    private LocalSocket connectPublisher() throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(
                    SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            throw e;
        }
    }

    private void readSnapshots(LocalSocket socket) throws IOException {
        while (!Thread.currentThread().isInterrupted()) {
            String payload = readFramedPayload(socket.getInputStream());
            if (isBlank(payload)) {
                payload = readFromAncillaryFd(socket);
            }
            if (isBlank(payload)) {
                return;
            }
            store.update(ProfileSnapshot.parse(payload));
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
     * Legacy fallback path mirroring the native {@code ReadFromSharedFd}: when no
     * inline payload arrived, read the framed snapshot from an ancillary file
     * descriptor.
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

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
