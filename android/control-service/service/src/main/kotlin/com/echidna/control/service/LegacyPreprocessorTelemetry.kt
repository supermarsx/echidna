package com.echidna.control.service

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val PREPROCESSOR_TELEMETRY_PROVIDER_API_VERSION = 3L
internal const val PREPROCESSOR_TELEMETRY_VALUE_BYTES = 48
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

    private val leases = linkedMapOf<Key, Long>()

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
            leases.minByOrNull { it.value }?.key?.let(leases::remove)
        }
        leases[key] = expiry
        return true
    }

    @Synchronized
    fun hasLive(
        uid: Int,
        pid: Int,
        processName: String,
        sessionId: Int,
        generation: Long,
        receivedAtMs: Long,
    ): Boolean {
        prune(receivedAtMs)
        val expiry = leases[Key(uid, pid, processName, sessionId, generation)] ?: return false
        return receivedAtMs < expiry
    }

    @Synchronized
    fun clear() = leases.clear()

    private fun prune(now: Long) {
        leases.entries.removeAll { it.value <= now }
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
        val processBytes = buffer.getShort(104).toInt() and 0xffff
        val processStart = LEGACY_CAPABILITY_FIXED_BODY_BYTES
        if (
            issued < 0L || expiry <= now || expiry <= issued ||
            expiry - issued > LEGACY_CAPABILITY_LIFETIME_MS ||
            processBytes <= 0 || processStart + processBytes > envelope.size
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
    )

    private val previous = linkedMapOf<Key, Previous>()

    @Synchronized
    fun report(
        uid: Int,
        pid: Int,
        processName: String,
        sessionId: Int,
        generation: Long,
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
        if (
            !ledger.hasLive(uid, pid, processName, sessionId, generation, receivedAtMs)
        ) {
            return LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY
        }
        if (currentPolicyGeneration() != generation) {
            return LegacyPreprocessorTelemetryResult.STALE_POLICY
        }
        val key = Key(uid, pid, processName, sessionId, generation)
        val last = previous[key]
        val deltas = if (last == null) {
            AuthenticatedTelemetryDeltas(0L, 0L, 0L, 0L)
        } else {
            val sequenceDelta = modularDelta(snapshot.sequence, last.snapshot.sequence)
            if (sequenceDelta !in 1L..MAX_SEQUENCE_ADVANCE) {
                return LegacyPreprocessorTelemetryResult.STALE_SEQUENCE
            }
            val elapsedMs = receivedAtMs - last.receivedAtMs
            if (elapsedMs < PREPROCESSOR_TELEMETRY_MIN_INTERVAL_MS) {
                return LegacyPreprocessorTelemetryResult.RATE_LIMITED
            }
            val blocks = modularDelta(snapshot.blocks, last.snapshot.blocks)
            val frames = modularDelta(snapshot.frames, last.snapshot.frames)
            val failures = modularDelta(snapshot.failures, last.snapshot.failures)
            val mutations = modularDelta(snapshot.mutations, last.snapshot.mutations)
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
        )
        val recorded = telemetryStore.recordAt(
            frame,
            AuthenticatedPeer(uid, pid),
            generation,
            receivedAtMs,
        )
        if (recorded != TelemetryRecordResult.ACCEPTED) {
            return if (recorded == TelemetryRecordResult.STALE_GENERATION) {
                LegacyPreprocessorTelemetryResult.STALE_POLICY
            } else {
                LegacyPreprocessorTelemetryResult.STALE_SEQUENCE
            }
        }
        previous[key] = Previous(snapshot, receivedAtMs)
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
