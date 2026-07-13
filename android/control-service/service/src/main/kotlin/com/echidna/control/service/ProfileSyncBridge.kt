package com.echidna.control.service

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

private const val SYNC_TAG = "EchidnaProfileSync"
private const val SOCKET_NAME = "echidna_profiles"
private const val MAX_PROFILE_PAYLOAD_BYTES = 10 * 1024 * 1024
private val EMPTY_PROFILE_SNAPSHOT =
    "{" +
        "\"profiles\":{}," +
        "\"whitelist\":{}," +
        "\"appBindings\":{}," +
        "\"control\":{" +
        "\"masterEnabled\":true," +
        "\"bypass\":false," +
        "\"panicUntilEpochMs\":0," +
        "\"sidetoneEnabled\":false," +
        "\"sidetoneGainDb\":0.0," +
        "\"engineMode\":\"native_first\"" +
        "}" +
        "}"

/**
 * Publishes profile updates to hooked readers.
 */
interface ProfileSyncChannel {
    fun pushProfiles(json: String)
}

/**
 * Publishes profile updates to native Zygisk readers and the LSPosed shim.
 *
 * The companion service owns one abstract AF_UNIX socket. Hooked readers connect
 * to it and receive the last snapshot immediately, then stay connected for later
 * update frames. This removes the former per-hooked-process unlink-then-bind
 * ownership race on the profile-sync socket.
 *
 * Note: used as the production implementation of [ProfileSyncChannel].
 */
class ProfileSyncBridge : ProfileSyncChannel, Closeable {
    private val publisher = ProfileSnapshotPublisher(SOCKET_NAME)

    init {
        publisher.start()
    }

    override fun pushProfiles(json: String) {
        publisher.publish(json)
    }

    override fun close() {
        publisher.close()
    }
}

private class ProfileSnapshotPublisher(private val socketName: String) : Closeable {
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArraySet<LocalSocket>()
    @Volatile private var latestSnapshot: String = EMPTY_PROFILE_SNAPSHOT
    @Volatile private var server: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        thread = Thread(this::run, "echidna-profile-publisher").apply {
            isDaemon = true
            start()
        }
    }

    fun publish(json: String) {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_PROFILE_PAYLOAD_BYTES) {
            Log.w(SYNC_TAG, "Profile snapshot too large; not publishing")
            return
        }
        latestSnapshot = json
        broadcast(json)
    }

    private fun run() {
        try {
            val localServer = LocalServerSocket(socketName)
            server = localServer
            while (running.get()) {
                val client = localServer.accept()
                clients.add(client)
                if (!writeSnapshot(client, latestSnapshot)) {
                    closeClient(client)
                } else {
                    monitorClient(client)
                }
            }
        } catch (e: IOException) {
            if (running.get()) {
                Log.w(SYNC_TAG, "Profile snapshot publisher stopped", e)
            }
        } finally {
            running.set(false)
            server = null
            closeClients()
        }
    }

    private fun broadcast(json: String) {
        clients.forEach { client ->
            if (!writeSnapshot(client, json)) {
                closeClient(client)
            }
        }
    }

    private fun writeSnapshot(socket: LocalSocket, payload: String): Boolean {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_PROFILE_PAYLOAD_BYTES) {
            return false
        }
        val header = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(bytes.size)
            .array()
        return try {
            val output = socket.outputStream
            output.write(header)
            output.write(bytes)
            output.flush()
            true
        } catch (e: IOException) {
            Log.v(SYNC_TAG, "Dropping profile reader: ${e.message}")
            false
        }
    }

    private fun closeClient(socket: LocalSocket) {
        clients.remove(socket)
        try {
            socket.close()
        } catch (ignored: IOException) {
            // Nothing to do.
        }
    }

    private fun monitorClient(socket: LocalSocket) {
        Thread({
            try {
                val input = socket.inputStream
                while (running.get() && input.read() >= 0) {
                    // Readers do not send data; EOF means the connection closed.
                }
            } catch (ignored: IOException) {
                // The writer path closes the socket to drop failed clients.
            } finally {
                closeClient(socket)
            }
        }, "echidna-profile-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeClients() {
        clients.forEach(::closeClient)
    }

    override fun close() {
        running.set(false)
        val localServer = server
        server = null
        try {
            localServer?.close()
        } catch (ignored: IOException) {
            // Nothing to do.
        }
        closeClients()
    }
}
