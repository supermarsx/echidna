package com.echidna.control.service

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val CAPABILITY_PROVIDER_API_VERSION = PREPROCESSOR_TELEMETRY_PROVIDER_API_VERSION
internal const val LEGACY_CAPABILITY_LIFETIME_MS = 5_000L
internal const val LEGACY_CAPABILITY_FIXED_BODY_BYTES = 110
internal const val LEGACY_CAPABILITY_MAX_PRESET_BYTES = 60 * 1024
internal const val LEGACY_CAPABILITY_KEY_ALIAS =
    "echidna_legacy_preprocessor_controller_p256_v1"
internal const val LEGACY_PREPROCESSOR_PREFERENCES = "legacy_preprocessor_internal"
internal const val LEGACY_PREPROCESSOR_ENABLED_KEY = "enabled_v1"

internal object LegacyCapabilityStatus {
    const val OK = 0
    const val DENIED = -1
    const val RATE_LIMITED = -11
    const val INVALID = -22
    const val STALE = -116
    const val KEY_UNAVAILABLE = -126
    const val EXPIRED = -127
    const val SIGNING_FAILED = -129
}

internal object LegacyCapabilityDiagnostic {
    const val ACCEPTED = "accepted"
    const val FEATURE_DISABLED = "feature_disabled"
    const val CALLER_UNAUTHORIZED = "caller_unauthorized"
    const val INVALID_REQUEST = "invalid_request"
    const val POLICY_DENIED = "policy_denied"
    const val STALE_GENERATION = "stale_generation"
    const val RATE_LIMITED = "rate_limited"
    const val SIGNING_QUEUE_FULL = "signing_queue_full"
    const val KEY_UNAVAILABLE = "key_unavailable"
    const val SIGNING_FAILED = "signing_failed"
    const val SIGNING_EXPIRED = "signing_expired"
    const val CALLBACK_FAILED = "callback_failed"
}

internal data class LegacyCapabilityPolicy(
    val generation: Long,
    val processName: String,
    val preset: ByteArray,
)

internal data class LegacyCapabilityRequest(
    val uid: Int,
    val packageName: String,
    val processName: String,
    val audioSessionId: Int,
    val generation: Long,
    val nonce: ByteArray,
)

internal data class LegacyCapabilityResult(
    val status: Int,
    val generation: Long,
    val envelope: ByteArray = ByteArray(0),
    val diagnostic: String,
)

internal fun interface LegacyCapabilityResultSink {
    fun complete(result: LegacyCapabilityResult)
    fun isAlive(): Boolean = true
}

internal interface LegacyCapabilitySigner {
    fun preparePublicKey(): ByteArray
    fun sign(body: ByteArray): ByteArray
}

internal object LegacyCapabilityCodec {
    private val magic = byteArrayOf(
        'E'.code.toByte(),
        'C'.code.toByte(),
        'H'.code.toByte(),
        'C'.code.toByte(),
    )
    private val implementationUuid = byteArrayOf(
        0x3e, 0x66, 0xa3.toByte(), 0x6e, 0xde.toByte(), 0xe9.toByte(), 0x5d, 0x81.toByte(),
        0xa0.toByte(), 0xd6.toByte(), 0x49, 0xfc.toByte(), 0x3b, 0x86.toByte(), 0x35, 0x30,
    )
    private val processPattern = Regex(
        "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+" +
            "(:[A-Za-z][A-Za-z0-9_]*)?",
    )

    fun encodeBody(
        audioSessionId: Int,
        uid: Int,
        generation: Long,
        issuedBoottimeMs: Long,
        expiryBoottimeMs: Long,
        nonce: ByteArray,
        processName: String,
        preset: ByteArray,
    ): ByteArray? {
        val process = processName.toByteArray(StandardCharsets.UTF_8)
        if (
            audioSessionId <= 0 || uid !in 10_000..99_999 || generation !in 1..Long.MAX_VALUE ||
            issuedBoottimeMs < 0L || expiryBoottimeMs <= issuedBoottimeMs ||
            expiryBoottimeMs - issuedBoottimeMs > LEGACY_CAPABILITY_LIFETIME_MS ||
            nonce.size != 16 || nonce.all { it == 0.toByte() } ||
            process.isEmpty() || process.size > 255 || !processPattern.matches(processName) ||
            preset.isEmpty() || preset.size > LEGACY_CAPABILITY_MAX_PRESET_BYTES
        ) {
            return null
        }
        val bodySize = LEGACY_CAPABILITY_FIXED_BODY_BYTES + process.size + preset.size
        val buffer = ByteBuffer.allocate(bodySize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.put(implementationUuid)
        buffer.putInt(audioSessionId)
        buffer.putInt(uid)
        buffer.putLong(generation)
        buffer.putLong(issuedBoottimeMs)
        buffer.putLong(expiryBoottimeMs)
        buffer.put(nonce)
        buffer.put(MessageDigest.getInstance("SHA-256").digest(preset))
        buffer.putShort(process.size.toShort())
        buffer.putInt(preset.size)
        buffer.put(process)
        buffer.put(preset)
        return buffer.array()
    }

    fun appendSignature(body: ByteArray, signature: ByteArray): ByteArray? {
        if (body.size < LEGACY_CAPABILITY_FIXED_BODY_BYTES || signature.size !in 64..80) return null
        return ByteBuffer.allocate(body.size + 2 + signature.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(body)
            .putShort(signature.size.toShort())
            .put(signature)
            .array()
    }
}

internal class LegacyPreprocessorFlagStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        LEGACY_PREPROCESSOR_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    init {
        if (!preferences.contains(LEGACY_PREPROCESSOR_ENABLED_KEY)) {
            // Persist the fail-closed migration explicitly without changing strict policy JSON.
            preferences.edit().putBoolean(LEGACY_PREPROCESSOR_ENABLED_KEY, false).commit()
        }
    }

    fun isEnabled(): Boolean = preferences.getBoolean(LEGACY_PREPROCESSOR_ENABLED_KEY, false)

    internal fun setEnabled(enabled: Boolean): Boolean =
        preferences.edit().putBoolean(LEGACY_PREPROCESSOR_ENABLED_KEY, enabled).commit()
}

internal class AndroidKeyStoreLegacyCapabilitySigner(private val context: Context) :
    LegacyCapabilitySigner {
    private val lock = Any()

    override fun preparePublicKey(): ByteArray = synchronized(lock) {
        val store = loadStore()
        ensurePrivateKey(store)
        val spki = validatedPublicSpki(store)
        exportSpkiAtomically(spki)
        spki
    }

    override fun sign(body: ByteArray): ByteArray = synchronized(lock) {
        val store = loadStore()
        val privateKey = ensurePrivateKey(store)
        val spki = validatedPublicSpki(store)
        exportSpkiAtomically(spki)
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(body)
            sign()
        }
    }

    internal fun exportedSpkiFile(): File =
        File(File(context.filesDir, EXPORT_DIRECTORY), EXPORT_FILENAME)

    private fun loadStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun ensurePrivateKey(store: KeyStore): PrivateKey {
        (store.getKey(LEGACY_CAPABILITY_KEY_ALIAS, null) as? PrivateKey)?.let { return it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(LEGACY_CAPABILITY_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKeyPair().private
    }

    private fun validatedPublicSpki(store: KeyStore): ByteArray {
        val publicKey = store.getCertificate(LEGACY_CAPABILITY_KEY_ALIAS)?.publicKey as? ECPublicKey
            ?: throw IllegalStateException("AndroidKeyStore EC public key unavailable")
        require(publicKey.params.curve.field.fieldSize == 256) { "capability key is not P-256" }
        return publicKey.encoded ?: throw IllegalStateException("public SPKI unavailable")
    }

    private fun exportSpkiAtomically(spki: ByteArray) {
        require(spki.isNotEmpty() && spki.size <= 1024) { "invalid SPKI size" }
        val directory = File(context.filesDir, EXPORT_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "unable to create SPKI directory" }
        Os.chmod(directory.absolutePath, 448) // 0700
        val target = File(directory, EXPORT_FILENAME)
        if (target.isFile && target.readBytes().contentEquals(spki)) {
            Os.chmod(target.absolutePath, 384) // 0600
            return
        }
        val temporary = File(directory, "$EXPORT_FILENAME.new")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(spki)
                output.fd.sync()
            }
            Os.chmod(temporary.absolutePath, 384)
            check(temporary.renameTo(target)) { "unable to atomically publish SPKI" }
            Os.chmod(target.absolutePath, 384)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val EXPORT_DIRECTORY = "echidna"
        const val EXPORT_FILENAME = "preprocessor_controller_p256.spki"
    }
}

internal class LegacyCapabilityRateLimiter(
    private val windowMs: Long = 10_000L,
    private val maxPerSession: Int = 8,
    private val maxPendingPerUid: Int = 8,
    private val maxPendingGlobal: Int = 16,
    private val maxEntries: Int = 256,
) {
    private data class Key(val uid: Int, val sessionId: Int)
    private data class Entry(
        val issued: ArrayDeque<Long> = ArrayDeque(),
        val nonces: LinkedHashMap<String, Long> = linkedMapOf(),
        var pending: Int = 0,
    )

    private val entries = linkedMapOf<Key, Entry>()

    @Synchronized
    fun acquire(uid: Int, sessionId: Int, nonce: ByteArray, nowMs: Long): Boolean {
        prune(nowMs)
        val key = Key(uid, sessionId)
        val entry = entries[key] ?: run {
            if (entries.size >= maxEntries) {
                val removable = entries.entries.firstOrNull { it.value.pending == 0 } ?: return false
                entries.remove(removable.key)
            }
            Entry().also { entries[key] = it }
        }
        val nonceKey = nonce.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (
            nonceKey in entry.nonces || entry.pending > 0 || entry.issued.size >= maxPerSession ||
            entries.filterKeys { it.uid == uid }.values.sumOf { it.pending } >= maxPendingPerUid ||
            entries.values.sumOf { it.pending } >= maxPendingGlobal
        ) {
            return false
        }
        entry.issued.addLast(nowMs)
        entry.nonces[nonceKey] = nowMs
        entry.pending += 1
        return true
    }

    @Synchronized
    fun release(uid: Int, sessionId: Int) {
        entries[Key(uid, sessionId)]?.let { it.pending = (it.pending - 1).coerceAtLeast(0) }
    }

    @Synchronized
    private fun prune(nowMs: Long) {
        val threshold = nowMs - windowMs
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            while (entry.issued.firstOrNull()?.let { it <= threshold } == true) {
                entry.issued.removeFirst()
            }
            entry.nonces.entries.removeAll { it.value <= threshold }
            if (entry.pending == 0 && entry.issued.isEmpty() && entry.nonces.isEmpty()) iterator.remove()
        }
    }
}

internal class BoundedCapabilityExecutor : Closeable {
    private val executor = ThreadPoolExecutor(
        1,
        2,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
        { runnable ->
            Thread(runnable, "echidna-capability-signer").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun close() {
        executor.shutdownNow()
    }
}

internal class LegacyCapabilityIssuer(
    private val enabled: () -> Boolean,
    private val policySource: (String, String) -> LegacyCapabilityPolicy?,
    private val signer: LegacyCapabilitySigner,
    private val executor: BoundedCapabilityExecutor,
    private val limiter: LegacyCapabilityRateLimiter = LegacyCapabilityRateLimiter(),
    private val boottimeMs: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun prepareKey() {
        if (!closed.get()) executor.execute { runCatching(signer::preparePublicKey) }
    }

    fun request(request: LegacyCapabilityRequest, sink: LegacyCapabilityResultSink) {
        if (!sink.isAlive()) return
        if (closed.get()) {
            sink.complete(
                result(
                    request,
                    LegacyCapabilityStatus.RATE_LIMITED,
                    LegacyCapabilityDiagnostic.SIGNING_QUEUE_FULL,
                ),
            )
            return
        }
        if (!enabled()) {
            sink.complete(
                result(
                    request,
                    LegacyCapabilityStatus.DENIED,
                    LegacyCapabilityDiagnostic.FEATURE_DISABLED,
                ),
            )
            return
        }
        if (!validRequest(request)) {
            sink.complete(
                result(
                    request,
                    LegacyCapabilityStatus.INVALID,
                    LegacyCapabilityDiagnostic.INVALID_REQUEST,
                ),
            )
            return
        }
        val acceptedAt = boottimeMs()
        if (!limiter.acquire(request.uid, request.audioSessionId, request.nonce, acceptedAt)) {
            sink.complete(
                result(
                    request,
                    LegacyCapabilityStatus.RATE_LIMITED,
                    LegacyCapabilityDiagnostic.RATE_LIMITED,
                ),
            )
            return
        }
        if (!executor.execute { sign(request, sink) }) {
            limiter.release(request.uid, request.audioSessionId)
            sink.complete(
                result(
                    request,
                    LegacyCapabilityStatus.RATE_LIMITED,
                    LegacyCapabilityDiagnostic.SIGNING_QUEUE_FULL,
                ),
            )
        }
    }

    private fun sign(request: LegacyCapabilityRequest, sink: LegacyCapabilityResultSink) {
        if (closed.get() || !sink.isAlive()) {
            limiter.release(request.uid, request.audioSessionId)
            return
        }
        val completed = try {
            issue(request)
        } catch (_: java.security.KeyStoreException) {
            result(
                request,
                LegacyCapabilityStatus.KEY_UNAVAILABLE,
                LegacyCapabilityDiagnostic.KEY_UNAVAILABLE,
            )
        } catch (_: java.security.UnrecoverableKeyException) {
            result(
                request,
                LegacyCapabilityStatus.KEY_UNAVAILABLE,
                LegacyCapabilityDiagnostic.KEY_UNAVAILABLE,
            )
        } catch (_: IllegalStateException) {
            result(
                request,
                LegacyCapabilityStatus.KEY_UNAVAILABLE,
                LegacyCapabilityDiagnostic.KEY_UNAVAILABLE,
            )
        } catch (_: IllegalArgumentException) {
            result(
                request,
                LegacyCapabilityStatus.KEY_UNAVAILABLE,
                LegacyCapabilityDiagnostic.KEY_UNAVAILABLE,
            )
        } catch (_: Exception) {
            result(
                request,
                LegacyCapabilityStatus.SIGNING_FAILED,
                LegacyCapabilityDiagnostic.SIGNING_FAILED,
            )
        } finally {
            limiter.release(request.uid, request.audioSessionId)
        }
        if (!closed.get() && sink.isAlive()) sink.complete(completed)
    }

    private fun issue(request: LegacyCapabilityRequest): LegacyCapabilityResult {
        if (!enabled()) {
            return result(
                request,
                LegacyCapabilityStatus.DENIED,
                LegacyCapabilityDiagnostic.FEATURE_DISABLED,
            )
        }
        val before = policySource(request.packageName, request.processName)
            ?: return result(
                request,
                LegacyCapabilityStatus.DENIED,
                LegacyCapabilityDiagnostic.POLICY_DENIED,
            )
        if (before.generation != request.generation) {
            return result(
                request,
                LegacyCapabilityStatus.STALE,
                LegacyCapabilityDiagnostic.STALE_GENERATION,
            )
        }
        if (before.processName != request.processName) {
            return result(
                request,
                LegacyCapabilityStatus.DENIED,
                LegacyCapabilityDiagnostic.POLICY_DENIED,
            )
        }
        val issued = boottimeMs()
        val expiry = issued + LEGACY_CAPABILITY_LIFETIME_MS
        val body = LegacyCapabilityCodec.encodeBody(
            request.audioSessionId,
            request.uid,
            request.generation,
            issued,
            expiry,
            request.nonce,
            request.processName,
            before.preset,
        ) ?: return result(
            request,
            LegacyCapabilityStatus.INVALID,
            LegacyCapabilityDiagnostic.INVALID_REQUEST,
        )
        val signature = signer.sign(body)
        if (!enabled()) {
            return result(
                request,
                LegacyCapabilityStatus.DENIED,
                LegacyCapabilityDiagnostic.FEATURE_DISABLED,
            )
        }
        val after = policySource(request.packageName, request.processName)
        if (
            after == null || after.generation != request.generation ||
            after.processName != request.processName || !after.preset.contentEquals(before.preset)
        ) {
            return result(
                request,
                LegacyCapabilityStatus.STALE,
                LegacyCapabilityDiagnostic.STALE_GENERATION,
            )
        }
        if (boottimeMs() >= expiry) {
            return result(
                request,
                LegacyCapabilityStatus.EXPIRED,
                LegacyCapabilityDiagnostic.SIGNING_EXPIRED,
            )
        }
        val envelope = LegacyCapabilityCodec.appendSignature(body, signature)
            ?: return result(
                request,
                LegacyCapabilityStatus.SIGNING_FAILED,
                LegacyCapabilityDiagnostic.SIGNING_FAILED,
            )
        return LegacyCapabilityResult(
            LegacyCapabilityStatus.OK,
            request.generation,
            envelope,
            LegacyCapabilityDiagnostic.ACCEPTED,
        )
    }

    private fun validRequest(request: LegacyCapabilityRequest): Boolean =
        request.uid in 10_000..99_999 && request.audioSessionId > 0 && request.generation > 0L &&
            request.nonce.size == 16 && request.nonce.any { it != 0.toByte() } &&
            request.packageName == request.processName.substringBefore(':')

    private fun result(request: LegacyCapabilityRequest, status: Int, diagnostic: String) =
        LegacyCapabilityResult(status, request.generation, diagnostic = diagnostic)

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.close()
    }
}
