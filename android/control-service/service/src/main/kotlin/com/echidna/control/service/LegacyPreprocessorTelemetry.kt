package com.echidna.control.service

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal const val PREPROCESSOR_TELEMETRY_PROVIDER_API_VERSION = 6L
internal const val PREPROCESSOR_TELEMETRY_VALUE_BYTES = 48
internal const val PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES = 16
internal const val PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS = 250L
private const val UINT32_MASK = 0xffff_ffffL
private const val MAX_SEQUENCE_ADVANCE = 1_024L
private const val MAX_BLOCKS_PER_INTERVAL = 65_536L
private const val MAX_ENTRIES = 128

internal object LegacyPreprocessorTelemetryFlag {
    const val ENABLED = 1
    const val AUTHORIZED = 1 shl 1
    const val EXPIRED = 1 shl 2
    const val ALL = ENABLED or AUTHORIZED or EXPIRED
}

internal data class LegacyPreprocessorTelemetrySnapshot(
    val sessionId: Int,
    val generation: Long,
    val sequence: Long,
    val flags: Int,
    val blocks: Long,
    val frames: Long,
    val failures: Long,
    val mutations: Long,
)

internal object LegacyPreprocessorTelemetryCodec {
    private const val MAGIC = 0x45434854

    fun decode(value: ByteArray?): LegacyPreprocessorTelemetrySnapshot? {
        if (value == null || value.size != PREPROCESSOR_TELEMETRY_VALUE_BYTES) return null
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        if (
            buffer.int != MAGIC || buffer.short.toInt() != 1 || buffer.short.toInt() != 1 ||
            buffer.short.toInt() != PREPROCESSOR_TELEMETRY_VALUE_BYTES
        ) {
            return null
        }
        val flags = buffer.short.toInt() and 0xffff
        if (flags and LegacyPreprocessorTelemetryFlag.ALL.inv() != 0) return null
        val sessionId = buffer.int
        val generation = buffer.long
        val sequence = Integer.toUnsignedLong(buffer.int)
        val blocks = Integer.toUnsignedLong(buffer.int)
        val frames = Integer.toUnsignedLong(buffer.int)
        val failures = Integer.toUnsignedLong(buffer.int)
        val mutations = Integer.toUnsignedLong(buffer.int)
        if (buffer.int != 0 || generation <= 0L) return null
        return LegacyPreprocessorTelemetrySnapshot(
            sessionId,
            generation,
            sequence,
            flags,
            blocks,
            frames,
            failures,
            mutations,
        )
    }
}

internal class LegacyCapabilityIssuanceLedger(
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private data class Key(
        val uid: Int,
        val pid: Int,
        val processName: String,
        val sessionId: Int,
        val generation: Long,
    )

    private data class Lease(
        val expiryMs: Long,
        val capabilityNonce: ByteArray,
    )

    private val leases = linkedMapOf<Key, Lease>()

    @Synchronized
    fun record(
        callingPid: Int,
        request: LegacyCapabilityRequest,
        result: LegacyCapabilityResult,
    ): Boolean {
        val now = clockMs()
        prune(now)
        val expiry = validateIssuedEnvelope(request, result, now) ?: return false
        val key = Key(
            request.uid,
            callingPid,
            request.processName,
            request.audioSessionId,
            request.generation,
        )
        if (key.pid <= 0) return false
        if (key !in leases && leases.size >= maxEntries) {
            leases.minByOrNull { it.value.expiryMs }?.key?.let(leases::remove)
        }
        leases[key] = Lease(expiry, request.nonce.clone())
        return true
    }

    @Synchronized
    fun hasLive(
        uid: Int,
        pid: Int,
        processName: String,
        sessionId: Int,
        generation: Long,
        capabilityNonce: ByteArray?,
        receivedAtMs: Long,
    ): Boolean {
        prune(receivedAtMs)
        if (
            capabilityNonce == null ||
            capabilityNonce.size != PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES
        ) {
            return false
        }
        val lease = leases[Key(uid, pid, processName, sessionId, generation)] ?: return false
        return receivedAtMs < lease.expiryMs &&
            MessageDigest.isEqual(lease.capabilityNonce, capabilityNonce)
    }

    @Synchronized
    fun clear() = leases.clear()

    private fun prune(now: Long) {
        leases.entries.removeAll { it.value.expiryMs <= now }
    }

    private fun validateIssuedEnvelope(
        request: LegacyCapabilityRequest,
        result: LegacyCapabilityResult,
        now: Long,
    ): Long? {
        if (result.status != LegacyCapabilityStatus.OK || result.generation != request.generation) {
            return null
        }
        val envelope = result.envelope
        if (envelope.size < LEGACY_CAPABILITY_FIXED_BODY_BYTES + 2) return null
        val buffer = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        if (
            buffer.getInt(0) != 0x45434843 || buffer.getInt(24) != request.audioSessionId ||
            buffer.getInt(28) != request.uid || buffer.getLong(32) != request.generation
        ) {
            return null
        }
        val issued = buffer.getLong(40)
        val expiry = buffer.getLong(48)
        val capabilityNonce = envelope.copyOfRange(56, 72)
        val processBytes = buffer.getShort(104).toInt() and 0xffff
        val processStart = LEGACY_CAPABILITY_FIXED_BODY_BYTES
        if (
            issued < 0L || expiry <= now || expiry <= issued ||
            expiry - issued > LEGACY_CAPABILITY_LIFETIME_MS ||
            processBytes <= 0 || processStart + processBytes > envelope.size ||
            request.nonce.size != PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES ||
            !MessageDigest.isEqual(capabilityNonce, request.nonce)
        ) {
            return null
        }
        val process = runCatching {
            ProfileSyncWire.decodeUtf8Strict(
                envelope.copyOfRange(processStart, processStart + processBytes),
            )
        }.getOrNull() ?: return null
        return expiry.takeIf { process == request.processName }
    }
}

internal enum class LegacyPreprocessorTelemetryResult {
    ACCEPTED,
    INVALID_PAYLOAD,
    KEY_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    CALLER_UNAUTHORIZED,
    NO_LIVE_CAPABILITY,
    SESSION_MISMATCH,
    GENERATION_MISMATCH,
    STALE_SEQUENCE,
    RATE_LIMITED,
    COUNTER_BOUNDS,
    STALE_POLICY,
}

internal class LegacyPreprocessorTelemetryRelay(
    private val ledger: LegacyCapabilityIssuanceLedger,
    private val telemetryStore: AuthenticatedTelemetryStore,
    private val currentPolicyGeneration: () -> Long,
    private val proofVerifier: LegacyPreprocessorTelemetryProofVerifier? = null,
) {
    private data class Key(
        val uid: Int,
        val pid: Int,
        val processName: String,
        val sessionId: Int,
        val generation: Long,
    )

    private data class Previous(
        val snapshot: LegacyPreprocessorTelemetrySnapshot,
        val receivedAtMs: Long,
        val capabilityNonce: ByteArray,
    )

    private data class ProofPrevious(
        val snapshot: LegacyPreprocessorTelemetryProof,
        val receivedAtMs: Long,
        val capabilityNonce: ByteArray,
    )

    private val previous = linkedMapOf<Key, Previous>()
    private val proofPrevious = linkedMapOf<Key, ProofPrevious>()

    @Synchronized
    fun reportProof(
        uid: Int,
        pid: Int,
        processName: String,
        sessionId: Int,
        generation: Long,
        rawProof: ByteArray?,
        receivedAtMs: Long,
    ): LegacyPreprocessorTelemetryResult {
        val verifier = proofVerifier
            ?: return LegacyPreprocessorTelemetryResult.KEY_UNAVAILABLE
        if (!verifier.available()) return LegacyPreprocessorTelemetryResult.KEY_UNAVAILABLE
        val snapshot = verifier.verify(rawProof)
            ?: return LegacyPreprocessorTelemetryResult.AUTHENTICATION_FAILED
        if (sessionId <= 0 || snapshot.sessionId != sessionId) {
            return LegacyPreprocessorTelemetryResult.SESSION_MISMATCH
        }
        if (generation <= 0L || snapshot.generation != generation) {
            return LegacyPreprocessorTelemetryResult.GENERATION_MISMATCH
        }
        val reportNonce = snapshot.capabilityNonce
        if (
            !ledger.hasLive(
                uid,
                pid,
                processName,
                sessionId,
                generation,
                reportNonce,
                receivedAtMs,
            )
        ) {
            return LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY
        }
        if (currentPolicyGeneration() != generation) {
            return LegacyPreprocessorTelemetryResult.STALE_POLICY
        }
        val key = Key(uid, pid, processName, sessionId, generation)
        val last = proofPrevious[key]
        val newIncarnation = last == null ||
            !MessageDigest.isEqual(last.capabilityNonce, reportNonce)
        val deltas = if (newIncarnation) {
            AuthenticatedTelemetryDeltas(0L, 0L, 0L, 0L)
        } else {
            val prior = requireNotNull(last)
            val sequenceDelta = modularDelta(snapshot.sequence, prior.snapshot.sequence)
            if (sequenceDelta !in 1L..MAX_SEQUENCE_ADVANCE) {
                return LegacyPreprocessorTelemetryResult.STALE_SEQUENCE
            }
            val elapsedMs = receivedAtMs - prior.receivedAtMs
            if (elapsedMs < PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS) {
                return LegacyPreprocessorTelemetryResult.RATE_LIMITED
            }
            val blocks = modularDelta(snapshot.blocks, prior.snapshot.blocks)
            val frames = modularDelta(snapshot.frames, prior.snapshot.frames)
            val failures = modularDelta(snapshot.failures, prior.snapshot.failures)
            val mutations = modularDelta(snapshot.mutations, prior.snapshot.mutations)
            val intervals = ((elapsedMs + PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS - 1L) /
                PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS).coerceAtMost(20L)
            val maxBlocks = MAX_BLOCKS_PER_INTERVAL * intervals
            val callbackAllowance = blocks + 1L
            if (
                blocks > maxBlocks || frames > callbackAllowance * 4_096L ||
                failures > callbackAllowance || mutations > callbackAllowance
            ) {
                return LegacyPreprocessorTelemetryResult.COUNTER_BOUNDS
            }
            AuthenticatedTelemetryDeltas(blocks, frames, failures, mutations)
        }

        val active = snapshot.flags and LegacyPreprocessorTelemetryFlag.ENABLED != 0 &&
            snapshot.flags and LegacyPreprocessorTelemetryFlag.AUTHORIZED != 0 &&
            snapshot.flags and LegacyPreprocessorTelemetryFlag.EXPIRED == 0
        val state = when {
            active && deltas.mutations > 0L -> AuthenticatedTelemetryState.PROCESSING
            active && deltas.failures > 0L -> AuthenticatedTelemetryState.ERROR
            active -> AuthenticatedTelemetryState.INSTALLED
            else -> AuthenticatedTelemetryState.BYPASSED
        }
        val frame = AuthenticatedTelemetryFrame(
            sequence = snapshot.sequence,
            senderMonotonicMs = receivedAtMs,
            process = processName,
            route = AuthenticatedTelemetryRoute.PREPROCESSOR,
            generation = generation,
            state = state,
            deltas = deltas,
            audioSessionId = sessionId,
            verification = AuthenticatedTelemetryVerification.EFFECT_HMAC_V1,
        )
        val recorded = telemetryStore.recordAt(
            frame,
            AuthenticatedPeer(uid, pid),
            generation,
            receivedAtMs,
            replaceExisting = newIncarnation,
        )
        if (recorded != TelemetryRecordResult.ACCEPTED) {
            return if (recorded == TelemetryRecordResult.STALE_GENERATION) {
                LegacyPreprocessorTelemetryResult.STALE_POLICY
            } else {
                LegacyPreprocessorTelemetryResult.STALE_SEQUENCE
            }
        }
        proofPrevious[key] = ProofPrevious(snapshot, receivedAtMs, reportNonce.clone())
        if (proofPrevious.size > MAX_ENTRIES) proofPrevious.entries.iterator().run {
            if (hasNext()) {
                next()
                remove()
            }
        }
        return LegacyPreprocessorTelemetryResult.ACCEPTED
    }

    @Synchronized
    fun report(
        uid: Int,
        pid: Int,
        processName: String,
        sessionId: Int,
        generation: Long,
        capabilityNonce: ByteArray?,
        rawSnapshot: ByteArray?,
        receivedAtMs: Long,
    ): LegacyPreprocessorTelemetryResult {
        val snapshot = LegacyPreprocessorTelemetryCodec.decode(rawSnapshot)
            ?: return LegacyPreprocessorTelemetryResult.INVALID_PAYLOAD
        if (sessionId <= 0 || snapshot.sessionId != sessionId) {
            return LegacyPreprocessorTelemetryResult.SESSION_MISMATCH
        }
        if (generation <= 0L || snapshot.generation != generation) {
            return LegacyPreprocessorTelemetryResult.GENERATION_MISMATCH
        }
        val reportNonce = capabilityNonce?.takeIf {
            it.size == PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES
        } ?: return LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY
        if (
            !ledger.hasLive(
                uid,
                pid,
                processName,
                sessionId,
                generation,
                reportNonce,
                receivedAtMs,
            )
        ) {
            return LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY
        }
        if (currentPolicyGeneration() != generation) {
            return LegacyPreprocessorTelemetryResult.STALE_POLICY
        }
        val key = Key(uid, pid, processName, sessionId, generation)
        val last = previous[key]
        val newIncarnation = last == null ||
            !MessageDigest.isEqual(last.capabilityNonce, reportNonce)
        val deltas = if (newIncarnation) {
            AuthenticatedTelemetryDeltas(0L, 0L, 0L, 0L)
        } else {
            val prior = requireNotNull(last)
            val sequenceDelta = modularDelta(snapshot.sequence, prior.snapshot.sequence)
            if (sequenceDelta !in 1L..MAX_SEQUENCE_ADVANCE) {
                return LegacyPreprocessorTelemetryResult.STALE_SEQUENCE
            }
            val elapsedMs = receivedAtMs - prior.receivedAtMs
            if (elapsedMs < PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS) {
                return LegacyPreprocessorTelemetryResult.RATE_LIMITED
            }
            val blocks = modularDelta(snapshot.blocks, prior.snapshot.blocks)
            val frames = modularDelta(snapshot.frames, prior.snapshot.frames)
            val failures = modularDelta(snapshot.failures, prior.snapshot.failures)
            val mutations = modularDelta(snapshot.mutations, prior.snapshot.mutations)
            val intervals = ((elapsedMs + PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS - 1L) /
                PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS).coerceAtMost(20L)
            val maxBlocks = MAX_BLOCKS_PER_INTERVAL * intervals
            val callbackAllowance = blocks + 1L
            if (
                blocks > maxBlocks || frames > callbackAllowance * 4_096L ||
                failures > callbackAllowance || mutations > callbackAllowance
            ) {
                return LegacyPreprocessorTelemetryResult.COUNTER_BOUNDS
            }
            AuthenticatedTelemetryDeltas(blocks, frames, failures, mutations)
        }

        val active = snapshot.flags and LegacyPreprocessorTelemetryFlag.ENABLED != 0 &&
            snapshot.flags and LegacyPreprocessorTelemetryFlag.AUTHORIZED != 0 &&
            snapshot.flags and LegacyPreprocessorTelemetryFlag.EXPIRED == 0
        val state = when {
            active && deltas.mutations > 0L -> AuthenticatedTelemetryState.PROCESSING
            active && deltas.failures > 0L -> AuthenticatedTelemetryState.ERROR
            active -> AuthenticatedTelemetryState.INSTALLED
            else -> AuthenticatedTelemetryState.BYPASSED
        }
        val frame = AuthenticatedTelemetryFrame(
            sequence = snapshot.sequence,
            senderMonotonicMs = receivedAtMs,
            process = processName,
            route = AuthenticatedTelemetryRoute.PREPROCESSOR,
            generation = generation,
            state = state,
            deltas = deltas,
            audioSessionId = sessionId,
            verification = AuthenticatedTelemetryVerification.CALLER_ATTESTED_BINDER_V1,
        )
        val recorded = telemetryStore.recordAt(
            frame,
            AuthenticatedPeer(uid, pid),
            generation,
            receivedAtMs,
            replaceExisting = newIncarnation,
        )
        if (recorded != TelemetryRecordResult.ACCEPTED) {
            return if (recorded == TelemetryRecordResult.STALE_GENERATION) {
                LegacyPreprocessorTelemetryResult.STALE_POLICY
            } else {
                LegacyPreprocessorTelemetryResult.STALE_SEQUENCE
            }
        }
        previous[key] = Previous(snapshot, receivedAtMs, reportNonce.clone())
        if (previous.size > MAX_ENTRIES) previous.entries.iterator().run {
            if (hasNext()) {
                next()
                remove()
            }
        }
        return LegacyPreprocessorTelemetryResult.ACCEPTED
    }

    private fun modularDelta(next: Long, previous: Long): Long =
        (next - previous) and UINT32_MASK
}
