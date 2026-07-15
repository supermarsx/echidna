package com.echidna.control.service

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES = 112
internal const val PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES = 32
private const val PREPROCESSOR_TELEMETRY_PROOF_BODY_BYTES = 80
private const val PREPROCESSOR_TELEMETRY_PROOF_KEY_ID_BYTES = 16
private const val PREPROCESSOR_TELEMETRY_PROOF_TAG_BYTES = 32
private const val PREPROCESSOR_TELEMETRY_PROOF_KEY_MODE = 0x180 // 0600
private val PREPROCESSOR_TELEMETRY_PROOF_DOMAIN =
    "ECHIDNA_PREPROCESSOR_TELEMETRY_PROOF_V2".toByteArray(StandardCharsets.US_ASCII)

internal data class LegacyPreprocessorTelemetryProof(
    val sessionId: Int,
    val generation: Long,
    val capabilityNonce: ByteArray,
    val sequence: Long,
    val flags: Int,
    val blocks: Long,
    val frames: Long,
    val failures: Long,
    val mutations: Long,
)

internal fun interface LegacyPreprocessorTelemetryProofKeySource {
    fun load(): ByteArray?
}

/**
 * Loads the Magisk-provisioned app copy without following links or accepting metadata drift.
 * Loading is service-start work; proof verification never performs file I/O.
 */
internal class AppPrivateTelemetryProofKeySource(
    private val file: File,
    private val expectedUid: Int = Process.myUid(),
) : LegacyPreprocessorTelemetryProofKeySource {
    override fun load(): ByteArray? {
        var descriptor: FileDescriptor? = null
        return try {
            descriptor = Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
            val before = Os.fstat(descriptor)
            if (!safeMetadata(before.st_mode, before.st_size, before.st_uid, before.st_gid)) {
                return null
            }
            val key = ByteArray(PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES)
            var offset = 0
            while (offset < key.size) {
                val count = Os.read(descriptor, key, offset, key.size - offset)
                if (count <= 0) {
                    key.fill(0)
                    return null
                }
                offset += count
            }
            val after = Os.fstat(descriptor)
            if (
                before.st_dev != after.st_dev || before.st_ino != after.st_ino ||
                before.st_mode != after.st_mode || before.st_size != after.st_size ||
                before.st_uid != after.st_uid || before.st_gid != after.st_gid ||
                key.all { it == 0.toByte() }
            ) {
                key.fill(0)
                null
            } else {
                key
            }
        } catch (_: android.system.ErrnoException) {
            null
        } catch (_: InterruptedIOException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
        }
    }

    private fun safeMetadata(mode: Int, size: Long, uid: Int, gid: Int): Boolean =
        OsConstants.S_ISREG(mode) && size == PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES.toLong() &&
            uid == expectedUid && gid == expectedUid &&
            mode and 0x1ff == PREPROCESSOR_TELEMETRY_PROOF_KEY_MODE
}

/** Fixed-key verifier. [prepare] must run off Binder threads before accepting proof reports. */
internal class LegacyPreprocessorTelemetryProofVerifier(
    private val keySource: LegacyPreprocessorTelemetryProofKeySource,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile private var preparedKey: ByteArray? = null

    fun prepare(): Boolean {
        if (closed.get()) return false
        val candidate = keySource.load()
        if (
            candidate == null || candidate.size != PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES ||
            candidate.all { it == 0.toByte() }
        ) {
            candidate?.fill(0)
            replaceKey(null)
            return false
        }
        if (!replaceKey(candidate.clone())) {
            candidate.fill(0)
            return false
        }
        candidate.fill(0)
        return true
    }

    fun verify(rawProof: ByteArray?): LegacyPreprocessorTelemetryProof? {
        val key = preparedKey?.clone() ?: return null
        return try {
            LegacyPreprocessorTelemetryProofCodec.verify(rawProof, key)
        } finally {
            key.fill(0)
        }
    }

    fun available(): Boolean = preparedKey != null

    @Synchronized
    private fun replaceKey(next: ByteArray?): Boolean {
        if (closed.get() && next != null) {
            next.fill(0)
            return false
        }
        preparedKey?.fill(0)
        preparedKey = next
        return true
    }

    override fun close() {
        closed.set(true)
        replaceKey(null)
    }
}

internal object LegacyPreprocessorTelemetryProofCodec {
    private const val MAGIC = 0x45434854

    fun verify(rawProof: ByteArray?, key: ByteArray): LegacyPreprocessorTelemetryProof? {
        if (
            rawProof == null || rawProof.size != PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES ||
            key.size != PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES || key.all { it == 0.toByte() }
        ) {
            return null
        }
        val value = ByteBuffer.wrap(rawProof).order(ByteOrder.BIG_ENDIAN)
        if (
            value.int != MAGIC || value.short.toInt() != 2 || value.short.toInt() != 2 ||
            value.short.toInt() != PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES
        ) {
            return null
        }
        val flags = value.short.toInt() and 0xffff
        val sessionId = value.int
        val generation = value.long
        val nonce = ByteArray(PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES).also(value::get)
        val sequence = Integer.toUnsignedLong(value.int)
        val blocks = Integer.toUnsignedLong(value.int)
        val frames = Integer.toUnsignedLong(value.int)
        val failures = Integer.toUnsignedLong(value.int)
        val mutations = Integer.toUnsignedLong(value.int)
        val keyId = ByteArray(PREPROCESSOR_TELEMETRY_PROOF_KEY_ID_BYTES).also(value::get)
        if (
            flags and LegacyPreprocessorTelemetryFlag.ALL.inv() != 0 || generation <= 0L ||
            nonce.all { it == 0.toByte() } || value.int != 0
        ) {
            return null
        }
        val expectedKeyId = MessageDigest.getInstance("SHA-256")
            .digest(key)
            .copyOf(PREPROCESSOR_TELEMETRY_PROOF_KEY_ID_BYTES)
        if (!MessageDigest.isEqual(keyId, expectedKeyId)) {
            expectedKeyId.fill(0)
            return null
        }
        expectedKeyId.fill(0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(PREPROCESSOR_TELEMETRY_PROOF_DOMAIN)
        mac.update(rawProof, 0, PREPROCESSOR_TELEMETRY_PROOF_BODY_BYTES)
        val expectedTag = mac.doFinal()
        val suppliedTag = rawProof.copyOfRange(
            PREPROCESSOR_TELEMETRY_PROOF_BODY_BYTES,
            PREPROCESSOR_TELEMETRY_PROOF_BODY_BYTES + PREPROCESSOR_TELEMETRY_PROOF_TAG_BYTES,
        )
        val authenticated = MessageDigest.isEqual(suppliedTag, expectedTag)
        expectedTag.fill(0)
        suppliedTag.fill(0)
        if (!authenticated) return null
        return LegacyPreprocessorTelemetryProof(
            sessionId,
            generation,
            nonce,
            sequence,
            flags,
            blocks,
            frames,
            failures,
            mutations,
        )
    }
}
