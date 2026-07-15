package com.echidna.control.service

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val SYNC_TAG = "EchidnaProfileSync"
private const val SOCKET_NAME = "echidna_profiles"
private const val MAX_CLIENTS = 16
private const val MAX_PENDING_HANDSHAKES = 16
private const val HANDSHAKE_TIMEOUT_MS = 750
private const val SLOW_WRITER_TIMEOUT_MS = 2_000L
private const val WRITER_WATCHDOG_PERIOD_MS = 250L
internal const val PROFILE_SYNC_V2_ZYGISK_HELLO = "ECHIDNA_PROFILE_SYNC/2 zygisk\n"
internal const val PROFILE_SYNC_V2_LSPOSED_HELLO = "ECHIDNA_PROFILE_SYNC/2 lsposed\n"
private const val MAX_HELLO_BYTES = 64
private val LEGACY_FAIL_CLOSED_SNAPSHOT =
    "{" +
        "\"profiles\":{}," +
        "\"whitelist\":{}," +
        "\"appBindings\":{}," +
        "\"control\":{" +
        "\"masterEnabled\":false," +
        "\"bypass\":true," +
        "\"panicUntilEpochMs\":0," +
        "\"sidetoneEnabled\":false," +
        "\"sidetoneGainDb\":0.0," +
        "\"engineMode\":\"native_first\"" +
        "}" +
        "}"

/** Publishes profile updates to hooked readers. */
interface ProfileSyncChannel {
    fun pushProfiles(json: String)
}

/** Client roles negotiated before any v2 policy bytes are sent. */
internal enum class ProfileSyncClientRole {
    ZYGISK,
    LSPOSED,
    LEGACY,
}

/** Pure framing/negotiation helpers shared with unit tests. */
internal object ProfileSyncWire {
    fun classifyHello(hello: String?): ProfileSyncClientRole = when (hello) {
        PROFILE_SYNC_V2_ZYGISK_HELLO -> ProfileSyncClientRole.ZYGISK
        PROFILE_SYNC_V2_LSPOSED_HELLO -> ProfileSyncClientRole.LSPOSED
        else -> ProfileSyncClientRole.LEGACY
    }

    @Throws(IOException::class)
    fun readHello(input: InputStream): String? {
        val bytes = ByteArray(MAX_HELLO_BYTES)
        var count = 0
        while (count < bytes.size) {
            val next = input.read()
            if (next < 0) return null
            bytes[count++] = next.toByte()
            if (next == '\n'.code) {
                return decodeUtf8Strict(bytes.copyOf(count))
            }
        }
        return decodeUtf8Strict(bytes)
    }

    fun encodeFrame(payload: String): ByteArray? {
        val payloadBytes = encodeUtf8Strict(payload) ?: return null
        if (payloadBytes.isEmpty() || payloadBytes.size > MAX_POLICY_ENVELOPE_BYTES) return null
        return ByteBuffer.allocate(Int.SIZE_BYTES + payloadBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payloadBytes.size)
            .put(payloadBytes)
            .array()
    }

    fun encodeUtf8Strict(value: String): ByteArray? = try {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        null
    }

    @Throws(CharacterCodingException::class)
    fun decodeUtf8Strict(value: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString()
}

/** One-slot mailbox: a slow writer observes the newest complete frame, never frame fragments. */
internal class LatestFrameMailbox {
    private val queue = ArrayBlockingQueue<ByteArray>(1)
    private val lock = Any()
    @Volatile private var closed = false

    fun offer(frame: ByteArray): Boolean {
        synchronized(lock) {
            if (closed) return false
            queue.clear()
            return queue.offer(frame)
        }
    }

    fun take(): ByteArray? {
        val next = try {
            queue.take()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return next.takeUnless { it.isEmpty() }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queue.clear()
            queue.offer(ByteArray(0))
        }
    }
}

/**
 * Publishes profile updates to native Zygisk readers and the LSPosed shim.
 *
 * The accept path only negotiates a role. Every v2 client owns a single writer thread and a
 * one-slot newest-frame mailbox, so neither ProfileStore locks nor the publisher state lock are
 * held during socket I/O. Legacy/no-hello readers receive one inert v1 document and are closed.
 */
class ProfileSyncBridge(context: Context) : ProfileSyncChannel, Closeable {
    private val publisher = ProfileSnapshotPublisher(context.applicationContext, SOCKET_NAME)

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

private data class PublishedSnapshot(
    val generation: Long,
    val payload: String,
    val parsed: VersionedPolicyEnvelope,
)

private class ProfileSnapshotPublisher(
    private val context: Context,
    private val socketName: String,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    private val clients = ConcurrentHashMap.newKeySet<ProfileClient>()
    private val handshakeExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_HANDSHAKES),
        { runnable -> daemonThread(runnable, "echidna-profile-handshake") },
    )
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            daemonThread(runnable, "echidna-profile-watchdog")
        }
    @Volatile private var latestSnapshot: PublishedSnapshot? = null
    @Volatile private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        watchdog.scheduleAtFixedRate(
            ::evictSlowWriters,
            WRITER_WATCHDOG_PERIOD_MS,
            WRITER_WATCHDOG_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
        acceptThread = daemonThread(::run, "echidna-profile-publisher").apply { start() }
    }

    fun publish(json: String) {
        val parsed = PolicyEnvelopeCodec.parsePublished(json) ?: run {
            Log.w(SYNC_TAG, "Rejected invalid v2 profile snapshot before publication")
            return
        }
        if (ProfileSyncWire.encodeFrame(json) == null) {
            Log.w(SYNC_TAG, "Profile snapshot cannot be framed safely")
            return
        }
        val next = PublishedSnapshot(parsed.generation, json, parsed)
        val recipients = synchronized(stateLock) {
            val current = latestSnapshot
            when {
                current != null && next.generation < current.generation -> {
                    Log.w(SYNC_TAG, "Rejected profile snapshot generation rollback")
                    return
                }
                current != null && next.generation == current.generation &&
                    next.payload != current.payload -> {
                    Log.w(SYNC_TAG, "Rejected conflicting profile snapshot generation")
                    return
                }
                current != null && next.generation == current.generation -> return
            }
            latestSnapshot = next
            clients.toList()
        }
        recipients.forEach { client -> client.offerSnapshot(next) }
    }

    private fun run() {
        try {
            val localServer = LocalServerSocket(socketName)
            server = localServer
            while (running.get()) {
                val client = localServer.accept()
                try {
                    handshakeExecutor.execute { negotiate(client) }
                } catch (_: RejectedExecutionException) {
                    Log.w(SYNC_TAG, "Dropping profile reader: handshake queue is full")
                    closeSocket(client)
                }
            }
        } catch (exception: IOException) {
            if (running.get()) Log.w(SYNC_TAG, "Profile snapshot publisher stopped", exception)
        } finally {
            running.set(false)
            server = null
            closeClients()
        }
    }

    private fun negotiate(socket: LocalSocket) {
        if (!running.get()) {
            closeSocket(socket)
            return
        }
        val hello = try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            ProfileSyncWire.readHello(socket.inputStream)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            closeSocket(socket)
            return
        }
        when (val role = ProfileSyncWire.classifyHello(hello)) {
            ProfileSyncClientRole.LEGACY -> sendLegacyAndClose(socket)
            ProfileSyncClientRole.LSPOSED -> closeSocket(socket)
            ProfileSyncClientRole.ZYGISK -> registerV2Client(socket, role)
        }
    }

    private fun registerV2Client(socket: LocalSocket, role: ProfileSyncClientRole) {
        val credentials = runCatching { socket.peerCredentials }.getOrNull()
        if (credentials == null || credentials.pid <= 0 || credentials.uid < 0) {
            Log.w(SYNC_TAG, "Dropping profile reader with unavailable peer credentials")
            closeSocket(socket)
            return
        }
        val packageNames = context.packageManager.getPackagesForUid(credentials.uid)
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            .orEmpty()
        if (packageNames.isEmpty()) {
            Log.w(SYNC_TAG, "Dropping profile reader with unmapped peer UID")
            closeSocket(socket)
            return
        }
        try {
            socket.soTimeout = 0
        } catch (_: IOException) {
            closeSocket(socket)
            return
        }
        var client: ProfileClient? = null
        var initialFrame: ByteArray? = null
        synchronized(stateLock) {
            if (running.get() && clients.size < MAX_CLIENTS) {
                val initial = latestSnapshot
                val scoped = initial?.let { snapshot ->
                    PolicyEnvelopeCodec.encodeScopedForPackages(snapshot.parsed, packageNames)
                }
                initialFrame = scoped?.let(ProfileSyncWire::encodeFrame)
                if (initialFrame != null) {
                    client = ProfileClient(socket, role, packageNames) { closed ->
                        clients.remove(closed)
                    }
                    client!!.offer(initialFrame!!)
                    clients.add(client!!)
                }
            }
        }
        val registered = client
        if (registered == null || initialFrame == null) {
            closeSocket(socket)
            return
        }
        registered.start()
    }

    private fun sendLegacyAndClose(socket: LocalSocket) {
        val frame = ProfileSyncWire.encodeFrame(LEGACY_FAIL_CLOSED_SNAPSHOT)
        try {
            if (frame != null) {
                socket.outputStream.write(frame)
                socket.outputStream.flush()
            }
        } catch (_: IOException) {
            // A legacy peer may have already timed out or disconnected.
        } finally {
            closeSocket(socket)
        }
    }

    private fun evictSlowWriters() {
        val now = System.nanoTime()
        clients.forEach { client ->
            if (client.hasWriteExceeded(now, SLOW_WRITER_TIMEOUT_MS)) {
                Log.w(SYNC_TAG, "Evicting blocked ${client.roleName()} profile reader")
                client.close()
            }
        }
    }

    private fun closeClients() {
        clients.toList().forEach(ProfileClient::close)
        clients.clear()
    }

    override fun close() {
        running.set(false)
        val localServer = server
        server = null
        try {
            localServer?.close()
        } catch (_: IOException) {
            // Nothing to do.
        }
        handshakeExecutor.shutdownNow()
        watchdog.shutdownNow()
        closeClients()
    }
}

private class ProfileClient(
    private val socket: LocalSocket,
    private val role: ProfileSyncClientRole,
    private val packageNames: Set<String>,
    private val onClosed: (ProfileClient) -> Unit,
) : Closeable {
    private val open = AtomicBoolean(true)
    private val mailbox = LatestFrameMailbox()
    private val writeStartedNanos = AtomicLong(0L)

    fun start() {
        daemonThread(::writeLoop, "echidna-profile-writer").start()
        daemonThread(::monitorInput, "echidna-profile-client-reader").start()
    }

    fun offer(frame: ByteArray) {
        if (open.get()) mailbox.offer(frame)
    }

    fun offerSnapshot(snapshot: PublishedSnapshot) {
        val scoped = PolicyEnvelopeCodec.encodeScopedForPackages(
            snapshot.parsed,
            packageNames,
            requireWhitelistMatch = false,
        ) ?: return close()
        val frame = ProfileSyncWire.encodeFrame(scoped) ?: return close()
        offer(frame)
    }

    fun roleName(): String = role.name.lowercase()

    fun hasWriteExceeded(nowNanos: Long, timeoutMs: Long): Boolean {
        val started = writeStartedNanos.get()
        return started != 0L && nowNanos - started > TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    }

    private fun writeLoop() {
        try {
            val output = socket.outputStream
            while (open.get()) {
                val frame = mailbox.take() ?: return
                writeStartedNanos.set(System.nanoTime())
                try {
                    output.write(frame)
                    output.flush()
                } finally {
                    writeStartedNanos.set(0L)
                }
            }
        } catch (exception: IOException) {
            Log.v(SYNC_TAG, "Dropping profile reader: ${exception.message}")
        } finally {
            close()
        }
    }

    private fun monitorInput() {
        try {
            val input = socket.inputStream
            while (open.get() && input.read() >= 0) {
                // Reserved for bounded client-to-service telemetry frames. It never writes policy.
            }
        } catch (_: IOException) {
            // Closing the socket is also how the watchdog interrupts a blocked writer.
        } finally {
            close()
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        mailbox.close()
        closeSocket(socket)
        onClosed(this)
    }
}

private fun daemonThread(block: () -> Unit, name: String): Thread =
    Thread(block, name).apply { isDaemon = true }

private fun daemonThread(runnable: Runnable, name: String): Thread =
    Thread(runnable, name).apply { isDaemon = true }

private fun closeSocket(socket: LocalSocket) {
    try {
        socket.close()
    } catch (_: IOException) {
        // Nothing to do.
    }
}
