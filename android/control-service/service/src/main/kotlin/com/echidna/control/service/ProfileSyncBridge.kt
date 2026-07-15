package com.echidna.control.service

import android.app.ActivityManager
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.Closeable
import java.io.EOFException
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
import org.json.JSONObject

private const val SYNC_TAG = "EchidnaProfileSync"
private const val SOCKET_NAME = "echidna_profiles"
private const val MAX_CLIENTS = 16
private const val MAX_PENDING_HANDSHAKES = 16
private const val HANDSHAKE_TIMEOUT_MS = 750
private const val SLOW_WRITER_TIMEOUT_MS = 2_000L
private const val WRITER_WATCHDOG_PERIOD_MS = 250L
internal const val PROFILE_SYNC_V2_ZYGISK_HELLO = "ECHIDNA_PROFILE_SYNC/2 zygisk\n"
internal const val PROFILE_SYNC_V2_LSPOSED_HELLO = "ECHIDNA_PROFILE_SYNC/2 lsposed\n"
internal const val PROFILE_SYNC_V3_ZYGISK_PREFIX = "ECHIDNA_PROFILE_SYNC/3 zygisk "
private const val MAX_HELLO_BYTES = 320
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

internal data class ProfileSyncHello(
    val role: ProfileSyncClientRole,
    val processName: String = "",
    val acknowledgedHandoff: Boolean = false,
)

/** Pure framing/negotiation helpers shared with unit tests. */
internal object ProfileSyncWire {
    fun classifyHello(hello: String?): ProfileSyncClientRole = when (hello) {
        PROFILE_SYNC_V2_LSPOSED_HELLO -> ProfileSyncClientRole.LSPOSED
        else -> ProfileSyncClientRole.LEGACY
    }

    fun parseHello(hello: String?): ProfileSyncHello {
        if (hello == PROFILE_SYNC_V2_LSPOSED_HELLO) {
            return ProfileSyncHello(ProfileSyncClientRole.LSPOSED)
        }
        if (
            hello != null && hello.endsWith('\n') &&
            hello.startsWith(PROFILE_SYNC_V3_ZYGISK_PREFIX)
        ) {
            val process = hello.substring(
                PROFILE_SYNC_V3_ZYGISK_PREFIX.length,
                hello.length - 1,
            )
            if (isValidClaimedProcess(process)) {
                return ProfileSyncHello(ProfileSyncClientRole.ZYGISK, process, true)
            }
        }
        return ProfileSyncHello(ProfileSyncClientRole.LEGACY)
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

    fun encodeCapturePolicyFrame(payload: String, handoffToken: Long): ByteArray? {
        if (handoffToken <= 0L || PolicyEnvelopeCodec.parsePublished(payload) == null) return null
        val wrapped = "{" +
            "\"schemaVersion\":1," +
            "\"type\":\"capture_policy\"," +
            "\"handoffToken\":" + handoffToken + "," +
            "\"policy\":" + payload +
            "}"
        val payloadBytes = encodeUtf8Strict(wrapped) ?: return null
        if (payloadBytes.isEmpty() || payloadBytes.size > MAX_POLICY_ENVELOPE_BYTES + 128) {
            return null
        }
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

    private fun isValidClaimedProcess(process: String): Boolean =
        process.isNotEmpty() &&
            (encodeUtf8Strict(process)?.size ?: 0) in 1..255 &&
            process.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*"))
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
internal class ProfileSyncBridge(
    context: Context,
    telemetryStore: AuthenticatedTelemetryStore = AuthenticatedTelemetryStore(),
) : ProfileSyncChannel, Closeable {
    private val handoffCoordinator = CaptureOwnerHandoffRegistry.get()
    private val publisher = ProfileSnapshotPublisher(
        context.applicationContext,
        SOCKET_NAME,
        telemetryStore,
        handoffCoordinator,
    )

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

private class ProfileSnapshotPublisher(
    private val context: Context,
    private val socketName: String,
    private val telemetryStore: AuthenticatedTelemetryStore,
    private val handoffCoordinator: CaptureOwnerHandoffCoordinator,
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
        if (!handoffCoordinator.publishPolicy(parsed)) {
            Log.w(SYNC_TAG, "Rejected profile snapshot generation rollback or conflict")
        }
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
        val parsed = ProfileSyncWire.parseHello(hello)
        when (parsed.role) {
            ProfileSyncClientRole.LEGACY -> sendLegacyAndClose(socket)
            ProfileSyncClientRole.LSPOSED -> closeSocket(socket)
            ProfileSyncClientRole.ZYGISK -> registerV3Client(socket, parsed.processName)
        }
    }

    private fun registerV3Client(socket: LocalSocket, processName: String) {
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
        if (!authenticatedProcessClaim(context, credentials.uid, credentials.pid, processName, packageNames)) {
            Log.w(SYNC_TAG, "Dropping profile reader with mismatched process claim")
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
        synchronized(stateLock) {
            if (running.get() && clients.size < MAX_CLIENTS) {
                client = ProfileClient(
                    socket = socket,
                    processName = processName,
                    packageNames = packageNames,
                    peer = AuthenticatedPeer(credentials.uid, credentials.pid),
                    telemetryStore = telemetryStore,
                    handoffCoordinator = handoffCoordinator,
                    onClosed = { closed -> clients.remove(closed) },
                )
                clients.add(client!!)
            }
        }
        val registered = client
        if (registered == null || !handoffCoordinator.registerNative(registered)) {
            registered?.close()
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
    override val processName: String,
    private val packageNames: Set<String>,
    private val peer: AuthenticatedPeer,
    private val telemetryStore: AuthenticatedTelemetryStore,
    private val handoffCoordinator: CaptureOwnerHandoffCoordinator,
    private val onClosed: (ProfileClient) -> Unit,
) : NativeCaptureEndpoint, Closeable {
    private val open = AtomicBoolean(true)
    private val mailbox = LatestFrameMailbox()
    private val writeStartedNanos = AtomicLong(0L)

    fun start() {
        daemonThread(::writeLoop, "echidna-profile-writer").start()
        daemonThread(::monitorInput, "echidna-profile-client-reader").start()
    }

    override fun publishPolicy(payload: String, handoffToken: Long): Boolean {
        val frame = ProfileSyncWire.encodeCapturePolicyFrame(payload, handoffToken) ?: return false
        return open.get() && mailbox.offer(frame)
    }

    fun roleName(): String = "zygisk"

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
            val telemetryRateLimiter = PeerTelemetryRateLimiter()
            val acknowledgementRateLimiter = PeerTelemetryRateLimiter(
                windowMs = 2_000L,
                maxFrames = 32,
            )
            while (open.get()) {
                val payload = readProfileClientPayload(input) ?: return
                val ack = CaptureOwnerAckWire.parse(payload)
                if (ack != null) {
                    if (!acknowledgementRateLimiter.allow(telemetryStore.nowMs())) {
                        throw IOException("Profile peer exceeded the bounded ACK rate")
                    }
                    if (ack.processName != processName) {
                        throw IOException("Capture ACK process does not match authenticated peer")
                    }
                    handoffCoordinator.acknowledgeNative(
                        this,
                        ack.processName,
                        ack.generation,
                        ack.handoffToken,
                        ack.active,
                    )
                    continue
                }
                val frame = AuthenticatedTelemetryWire.parse(payload)
                    ?: throw IOException("Invalid authenticated profile-peer frame")
                if (!telemetryRateLimiter.allow(telemetryStore.nowMs())) {
                    throw IOException("Profile peer exceeded the bounded telemetry rate")
                }
                if (frame.process != processName) {
                    throw IOException("Telemetry process does not match authenticated peer")
                }
                if (!handoffCoordinator.acceptsNativeTelemetry(
                        this,
                        processName,
                        frame.generation,
                    )) {
                    continue
                }
                telemetryStore.record(
                    frame = frame,
                    peer = peer,
                    currentPolicyGeneration = PublishedPolicyRegistry.generation(),
                )
            }
        } catch (exception: IOException) {
            Log.v(SYNC_TAG, "Dropping telemetry peer: ${exception.message}")
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
        handoffCoordinator.unregisterNative(this)
    }
}

internal data class CaptureOwnerAck(
    val processName: String,
    val generation: Long,
    val handoffToken: Long,
    val active: Boolean,
)

internal object CaptureOwnerAckWire {
    private val keys = setOf(
        "schemaVersion",
        "type",
        "process",
        "generation",
        "handoffToken",
        "active",
    )

    fun parse(payload: String): CaptureOwnerAck? {
        if (!StrictJsonValidator.isValid(payload)) return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val actual = buildSet {
            val iterator = root.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (actual != keys || root.optInt("schemaVersion", -1) != 1) return null
        if (root.optString("type", "") != "capture_owner_ack") return null
        val process = root.opt("process") as? String ?: return null
        if (
            process.isEmpty() || process.length > 255 ||
            !process.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*"))
        ) {
            return null
        }
        val rawGeneration = root.opt("generation")
        if (rawGeneration !is Number || rawGeneration is Float || rawGeneration is Double) return null
        val generation = rawGeneration.toString().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val rawToken = root.opt("handoffToken")
        if (rawToken !is Number || rawToken is Float || rawToken is Double) return null
        val handoffToken = rawToken.toString().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val active = root.opt("active") as? Boolean ?: return null
        return CaptureOwnerAck(process, generation, handoffToken, active)
    }
}

@Throws(IOException::class)
private fun readProfileClientPayload(input: InputStream): String? {
    val first = input.read()
    if (first < 0) return null
    val header = ByteArray(Int.SIZE_BYTES)
    header[0] = first.toByte()
    readFully(input, header, 1, header.size - 1)
    val size =
        ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
    if (size <= 0 || size > MAX_TELEMETRY_FRAME_BYTES) {
        throw IOException("Invalid profile-client frame size")
    }
    val payload = ByteArray(size)
    readFully(input, payload, 0, payload.size)
    return try {
        ProfileSyncWire.decodeUtf8Strict(payload)
    } catch (error: Exception) {
        throw IOException("Profile-client frame is not strict UTF-8", error)
    }
}

@Throws(IOException::class)
private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int) {
    var cursor = offset
    val end = offset + length
    while (cursor < end) {
        val count = input.read(target, cursor, end - cursor)
        if (count < 0) throw EOFException("Truncated profile-client frame")
        if (count > 0) cursor += count
    }
}

private fun authenticatedProcessClaim(
    context: Context,
    uid: Int,
    pid: Int,
    processName: String,
    packageNames: Set<String>,
): Boolean {
    val packageName = processName.substringBefore(':')
    if (packageName !in packageNames) return false
    return context.getSystemService(ActivityManager::class.java)
        ?.runningAppProcesses
        .orEmpty()
        .any { process ->
            process.uid == uid &&
                process.pid == pid &&
                process.processName == processName &&
                packageName in process.pkgList.orEmpty()
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
