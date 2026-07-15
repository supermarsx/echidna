package com.echidna.control.service

import android.os.SystemClock
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_TELEMETRY_FRAME_BYTES = 16 * 1024
internal const val TELEMETRY_ENTRY_TTL_MS = 2_000L
private const val TELEMETRY_MUTATION_FRESHNESS_MS = 1_500L
private const val MAX_TELEMETRY_ENTRIES = 128
private const val MAX_PROCESS_BYTES = 255
private const val UINT32_MAX = 0xffff_ffffL

private val TELEMETRY_ROOT_KEYS = setOf(
    "schemaVersion",
    "type",
    "sequence",
    "senderMonotonicMs",
    "process",
    "route",
    "generation",
    "state",
    "deltas",
)
private val TELEMETRY_DELTA_KEYS = setOf("blocks", "frames", "failures", "mutations")

internal enum class AuthenticatedTelemetryRoute(val wireName: String) {
    AAUDIO("aaudio"),
    AUDIORECORD("audiorecord"),
    OPENSL("opensl"),
    TINYALSA("tinyalsa"),
    LIBC_READ("libc_read"),
    API("api"),
    UNKNOWN("unknown"),
    LSPOSED("lsposed"),
    PREPROCESSOR("preprocessor");

    companion object {
        fun fromWire(value: String): AuthenticatedTelemetryRoute? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal enum class AuthenticatedTelemetryState(val wireName: String) {
    INSTALLED("installed"),
    PROCESSING("processing"),
    BYPASSED("bypassed"),
    ERROR("error");

    companion object {
        fun fromWire(value: String): AuthenticatedTelemetryState? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal data class AuthenticatedTelemetryDeltas(
    val blocks: Long,
    val frames: Long,
    val failures: Long,
    val mutations: Long,
)

internal data class AuthenticatedTelemetryFrame(
    val sequence: Long,
    val senderMonotonicMs: Long,
    val process: String,
    val route: AuthenticatedTelemetryRoute,
    val generation: Long,
    val state: AuthenticatedTelemetryState,
    val deltas: AuthenticatedTelemetryDeltas,
)

internal data class AuthenticatedPeer(
    val uid: Int,
    val pid: Int,
)

internal object AuthenticatedTelemetryWire {
    @Throws(IOException::class)
    fun readFrame(input: InputStream): AuthenticatedTelemetryFrame? {
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
            throw IOException("Invalid telemetry frame size")
        }
        val payload = ByteArray(size)
        readFully(input, payload, 0, payload.size)
        val json = try {
            ProfileSyncWire.decodeUtf8Strict(payload)
        } catch (exception: Exception) {
            throw IOException("Telemetry frame is not strict UTF-8", exception)
        }
        return parse(json) ?: throw IOException("Invalid telemetry v2 envelope")
    }

    fun parse(json: String): AuthenticatedTelemetryFrame? {
        if (!StrictJsonValidator.isValid(json)) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.keysSet() != TELEMETRY_ROOT_KEYS) return null
        if (strictLong(root, "schemaVersion", 2L, 2L) != 2L) return null
        if (root.optString("type", "") != "telemetry") return null

        val sequence = strictLong(root, "sequence", 0L, UINT32_MAX) ?: return null
        val senderMonotonicMs = strictLong(
            root,
            "senderMonotonicMs",
            1L,
            Long.MAX_VALUE,
        ) ?: return null
        val generation = strictLong(root, "generation", 1L, Long.MAX_VALUE) ?: return null
        val process = root.optString("process", "")
        if (!isValidProcessName(process)) return null
        val route = AuthenticatedTelemetryRoute.fromWire(root.optString("route", ""))
            ?: return null
        val state = AuthenticatedTelemetryState.fromWire(root.optString("state", ""))
            ?: return null
        val deltasObject = root.optJSONObject("deltas") ?: return null
        if (deltasObject.keysSet() != TELEMETRY_DELTA_KEYS) return null
        val deltas = AuthenticatedTelemetryDeltas(
            blocks = strictLong(deltasObject, "blocks", 0L, UINT32_MAX) ?: return null,
            frames = strictLong(deltasObject, "frames", 0L, UINT32_MAX) ?: return null,
            failures = strictLong(deltasObject, "failures", 0L, UINT32_MAX) ?: return null,
            mutations = strictLong(deltasObject, "mutations", 0L, UINT32_MAX) ?: return null,
        )
        if (state == AuthenticatedTelemetryState.PROCESSING) {
            if (deltas.mutations == 0L || deltas.blocks == 0L || deltas.frames == 0L) return null
        } else if (deltas.mutations != 0L) {
            return null
        }
        if (deltas.mutations > deltas.blocks) return null
        return AuthenticatedTelemetryFrame(
            sequence = sequence,
            senderMonotonicMs = senderMonotonicMs,
            process = process,
            route = route,
            generation = generation,
            state = state,
            deltas = deltas,
        )
    }

    private fun strictLong(root: JSONObject, key: String, min: Long, max: Long): Long? {
        val value = runCatching { root.get(key) }.getOrNull() ?: return null
        if (value is Float || value is Double || value !is Number) return null
        val parsed = value.toString().toLongOrNull() ?: return null
        return parsed.takeIf { it in min..max }
    }

    private fun isValidProcessName(process: String): Boolean {
        if (process.isBlank() || process.any { it.code < 0x20 }) return false
        val encoded = ProfileSyncWire.encodeUtf8Strict(process) ?: return false
        if (encoded.size > MAX_PROCESS_BYTES) return false
        return process.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*"))
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val count = input.read(target, cursor, end - cursor)
            if (count < 0) throw EOFException("Truncated telemetry frame")
            if (count == 0) continue
            cursor += count
        }
    }
}

/** Android's JSONObject accepts non-JSON extensions, so validate RFC 8259 syntax first. */
private object StrictJsonValidator {
    fun isValid(json: String): Boolean = try {
        Parser(json).validate()
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun validate() {
            skipWhitespace()
            parseValue(0)
            skipWhitespace()
            require(index == input.length)
        }

        private fun parseValue(depth: Int) {
            require(depth <= 16 && index < input.length)
            when (input[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> consumeLiteral("true")
                'f' -> consumeLiteral("false")
                'n' -> consumeLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Invalid JSON token")
            }
        }

        private fun parseObject(depth: Int) {
            index += 1
            skipWhitespace()
            if (consumeIf('}')) return
            val keys = hashSetOf<String>()
            while (true) {
                require(index < input.length && input[index] == '"')
                val key = parseString()
                require(keys.add(key))
                skipWhitespace()
                require(consumeIf(':'))
                skipWhitespace()
                parseValue(depth)
                skipWhitespace()
                if (consumeIf('}')) return
                require(consumeIf(','))
                skipWhitespace()
            }
        }

        private fun parseArray(depth: Int) {
            index += 1
            skipWhitespace()
            if (consumeIf(']')) return
            while (true) {
                parseValue(depth)
                skipWhitespace()
                if (consumeIf(']')) return
                require(consumeIf(','))
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            require(consumeIf('"'))
            val output = StringBuilder()
            while (index < input.length) {
                val character = input[index++]
                when {
                    character == '"' -> {
                        require(ProfileSyncWire.encodeUtf8Strict(output.toString()) != null)
                        return output.toString()
                    }
                    character == '\\' -> output.append(parseEscape())
                    character.code < 0x20 -> throw IllegalArgumentException("Control in string")
                    else -> output.append(character)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseEscape(): Char {
            require(index < input.length)
            return when (val escaped = input[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= input.length)
                    val value = input.substring(index, index + 4).toIntOrNull(16)
                        ?: throw IllegalArgumentException("Invalid unicode escape")
                    index += 4
                    value.toChar()
                }
                else -> throw IllegalArgumentException("Invalid escape")
            }
        }

        private fun parseNumber() {
            consumeIf('-')
            require(index < input.length)
            if (consumeIf('0')) {
                require(index >= input.length || input[index] !in '0'..'9')
            } else {
                require(input[index] in '1'..'9')
                while (index < input.length && input[index] in '0'..'9') index += 1
            }
            if (consumeIf('.')) {
                require(index < input.length && input[index] in '0'..'9')
                while (index < input.length && input[index] in '0'..'9') index += 1
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                index += 1
                if (index < input.length && (input[index] == '+' || input[index] == '-')) index += 1
                require(index < input.length && input[index] in '0'..'9')
                while (index < input.length && input[index] in '0'..'9') index += 1
            }
        }

        private fun consumeLiteral(literal: String) {
            require(input.regionMatches(index, literal, 0, literal.length))
            index += literal.length
        }

        private fun consumeIf(expected: Char): Boolean {
            if (index >= input.length || input[index] != expected) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index] in " \t\r\n") index += 1
        }
    }
}

internal fun processBelongsToPeerPackages(process: String, packageNames: Set<String>): Boolean =
    packageNames.any { packageName ->
        process == packageName || process.startsWith("$packageName:")
    }

/** Allows the required four frames/second while disconnecting sustained or burst floods. */
internal class PeerTelemetryRateLimiter(
    private val windowMs: Long = 2_000L,
    private val maxFrames: Int = 8,
) {
    private val acceptedAtMs = ArrayDeque<Long>(maxFrames)

    fun allow(receivedAtMs: Long): Boolean {
        while (acceptedAtMs.isNotEmpty() &&
            elapsed(receivedAtMs, acceptedAtMs.first()) >= windowMs
        ) {
            acceptedAtMs.removeFirst()
        }
        if (acceptedAtMs.size >= maxFrames) return false
        acceptedAtMs.addLast(receivedAtMs)
        return true
    }
}

internal enum class TelemetryRecordResult {
    ACCEPTED,
    STALE_GENERATION,
    STALE_SEQUENCE,
}

private data class TelemetryEntryKey(
    val uid: Int,
    val pid: Int,
    val process: String,
    val route: AuthenticatedTelemetryRoute,
    val generation: Long,
)

private data class MutableTelemetryEntry(
    var state: AuthenticatedTelemetryState,
    var sequence: Long,
    var senderMonotonicMs: Long,
    var receivedAtMs: Long,
    var lastMutationAtMs: Long?,
    var blocks: Long,
    var frames: Long,
    var failures: Long,
    var mutations: Long,
)

internal data class AuthenticatedTelemetryEntry(
    val uid: Int,
    val pid: Int,
    val process: String,
    val route: String,
    val generation: Long,
    val state: String,
    val sequence: Long,
    val senderMonotonicMs: Long,
    val ageMs: Long,
    val recentMutation: Boolean,
    val blocks: Long,
    val frames: Long,
    val failures: Long,
    val mutations: Long,
)

internal data class AuthenticatedTelemetrySnapshot(
    val policyGeneration: Long,
    val snapshotMonotonicMs: Long,
    val entries: List<AuthenticatedTelemetryEntry>,
) {
    val processing: Boolean
        get() = entries.any {
            it.generation == policyGeneration &&
                it.state == AuthenticatedTelemetryState.PROCESSING.wireName &&
                it.recentMutation &&
                it.mutations > 0L
        }

    val totalBlocks: Long get() = entries.saturatingSum { it.blocks }
    val totalFrames: Long get() = entries.saturatingSum { it.frames }
    val totalFailures: Long get() = entries.saturatingSum { it.failures }
    val totalMutations: Long get() = entries.saturatingSum { it.mutations }

    fun toLiveJson(legacy: TelemetrySnapshot?): String {
        val root = baseJson(includeIdentities = true)
        root.put("averageLatencyMs", 0.0)
        root.put("averageCpuPercent", 0.0)
        root.put("inputRms", -120.0)
        root.put("outputRms", -120.0)
        root.put("inputPeak", -120.0)
        root.put("outputPeak", -120.0)
        root.put("detectedPitchHz", 0.0)
        root.put("targetPitchHz", 0.0)
        root.put("formantShiftCents", 0.0)
        root.put("formantWidth", 0.0)
        root.put("xruns", 0)
        root.put("warningFlags", 0)
        root.put("samples", JSONArray())
        root.put("hooks", hooksJson())
        legacy?.let { snapshot ->
            root.put(
                "legacy",
                JSONObject()
                    .put("source", "shared_memory_v2")
                    .put("verification", "unverified")
                    .put("totalCallbacks", snapshot.totalCallbacks)
                    .put("averageLatencyMs", snapshot.averageLatencyMs.toDouble())
                    .put("averageCpuPercent", snapshot.averageCpuPercent.toDouble()),
            )
        }
        return root.toString()
    }

    fun toAnonymizedJson(includeTrends: Boolean): String =
        baseJson(includeIdentities = false)
            .put("includeTrends", includeTrends)
            .toString()

    fun toDiagnosticsJson(includeTrends: Boolean, legacy: TelemetrySnapshot?): JSONObject {
        val root = baseJson(includeIdentities = false)
        root.put("includeTrends", includeTrends)
        legacy?.let { snapshot ->
            root.put(
                "legacyUnverified",
                JSONObject()
                    .put("verification", "unverified")
                    .put("present", true)
                    .put("totalCallbacks", snapshot.totalCallbacks),
            )
        }
        return root
    }

    private fun baseJson(includeIdentities: Boolean): JSONObject {
        val root = JSONObject()
            .put("schemaVersion", 2)
            .put("type", "telemetrySnapshot")
            .put("verification", "authenticated_socket_v2")
            .put("currentPolicyGeneration", policyGeneration)
            .put("snapshotMonotonicMs", snapshotMonotonicMs)
            .put("processing", processing)
            .put("totalCallbacks", totalBlocks)
            .put("totalFrames", totalFrames)
            .put("totalFailures", totalFailures)
            .put("totalMutations", totalMutations)
        val routes = JSONArray()
        entries.forEach { entry ->
            val item = JSONObject()
                .put("route", entry.route)
                .put("generation", entry.generation)
                .put("state", entry.state)
                .put("sequence", entry.sequence)
                .put("senderMonotonicMs", entry.senderMonotonicMs)
                .put("ageMs", entry.ageMs)
                .put("recentMutation", entry.recentMutation)
                .put("blocks", entry.blocks)
                .put("frames", entry.frames)
                .put("failures", entry.failures)
                .put("mutations", entry.mutations)
            if (includeIdentities) {
                item.put("uid", entry.uid)
                item.put("pid", entry.pid)
                item.put("process", entry.process)
            }
            routes.put(item)
        }
        root.put("routes", routes)
        return root
    }

    private fun hooksJson(): JSONArray = JSONArray().also { hooks ->
        entries.groupBy { it.route }.toSortedMap().forEach { (route, routeEntries) ->
            val successes = routeEntries.count {
                it.state != AuthenticatedTelemetryState.ERROR.wireName
            }
            hooks.put(
                JSONObject()
                    .put("name", route)
                    .put("reason", routeEntries.last().state)
                    .put("attempts", routeEntries.size)
                    .put("successes", successes)
                    .put("failures", routeEntries.saturatingSum { it.failures }.coerceAtMost(Int.MAX_VALUE.toLong()))
                    .put("lastAttemptNs", 0L)
                    .put("lastSuccessNs", 0L),
            )
        }
    }
}

internal class AuthenticatedTelemetryStore(
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val maxEntries: Int = MAX_TELEMETRY_ENTRIES,
    private val entryTtlMs: Long = TELEMETRY_ENTRY_TTL_MS,
    private val mutationFreshnessMs: Long = TELEMETRY_MUTATION_FRESHNESS_MS,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<TelemetryEntryKey, MutableTelemetryEntry>()

    fun nowMs(): Long = clockMs()

    fun record(
        frame: AuthenticatedTelemetryFrame,
        peer: AuthenticatedPeer,
        currentPolicyGeneration: Long,
    ): TelemetryRecordResult = synchronized(lock) {
        val now = clockMs()
        pruneLocked(currentPolicyGeneration, now)
        if (currentPolicyGeneration <= 0L || frame.generation != currentPolicyGeneration) {
            return@synchronized TelemetryRecordResult.STALE_GENERATION
        }
        val key = TelemetryEntryKey(
            uid = peer.uid,
            pid = peer.pid,
            process = frame.process,
            route = frame.route,
            generation = frame.generation,
        )
        val current = entries[key]
        if (current != null && !isNewerSequence(frame.sequence, current.sequence)) {
            return@synchronized TelemetryRecordResult.STALE_SEQUENCE
        }
        if (current == null && entries.size >= maxEntries) {
            val oldest = entries.minByOrNull { it.value.receivedAtMs }?.key
            if (oldest != null) entries.remove(oldest)
        }
        val next = current ?: MutableTelemetryEntry(
            state = frame.state,
            sequence = frame.sequence,
            senderMonotonicMs = frame.senderMonotonicMs,
            receivedAtMs = now,
            lastMutationAtMs = null,
            blocks = 0L,
            frames = 0L,
            failures = 0L,
            mutations = 0L,
        )
        next.state = frame.state
        next.sequence = frame.sequence
        next.senderMonotonicMs = frame.senderMonotonicMs
        next.receivedAtMs = now
        if (frame.deltas.mutations > 0L) next.lastMutationAtMs = now
        next.blocks = saturatingAdd(next.blocks, frame.deltas.blocks)
        next.frames = saturatingAdd(next.frames, frame.deltas.frames)
        next.failures = saturatingAdd(next.failures, frame.deltas.failures)
        next.mutations = saturatingAdd(next.mutations, frame.deltas.mutations)
        entries[key] = next
        TelemetryRecordResult.ACCEPTED
    }

    fun snapshot(currentPolicyGeneration: Long): AuthenticatedTelemetrySnapshot = synchronized(lock) {
        val now = clockMs()
        pruneLocked(currentPolicyGeneration, now)
        val immutable = entries.map { (key, value) ->
            val age = elapsed(now, value.receivedAtMs)
            val mutationAge = value.lastMutationAtMs?.let { elapsed(now, it) }
            AuthenticatedTelemetryEntry(
                uid = key.uid,
                pid = key.pid,
                process = key.process,
                route = key.route.wireName,
                generation = key.generation,
                state = value.state.wireName,
                sequence = value.sequence,
                senderMonotonicMs = value.senderMonotonicMs,
                ageMs = age,
                recentMutation = mutationAge != null && mutationAge <= mutationFreshnessMs,
                blocks = value.blocks,
                frames = value.frames,
                failures = value.failures,
                mutations = value.mutations,
            )
        }
        AuthenticatedTelemetrySnapshot(currentPolicyGeneration, now, immutable)
    }

    private fun pruneLocked(currentPolicyGeneration: Long, now: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.key.generation != currentPolicyGeneration ||
                elapsed(now, item.value.receivedAtMs) > entryTtlMs
            ) {
                iterator.remove()
            }
        }
    }
}

private fun isNewerSequence(next: Long, previous: Long): Boolean {
    val distance = (next - previous) and UINT32_MAX
    return distance in 1L..0x7fff_ffffL
}

private fun elapsed(now: Long, then: Long): Long =
    if (now >= then) now - then else Long.MAX_VALUE

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private inline fun <T> Iterable<T>.saturatingSum(value: (T) -> Long): Long {
    var total = 0L
    for (item in this) total = saturatingAdd(total, value(item))
    return total
}

private fun JSONObject.keysSet(): Set<String> {
    val result = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) result += iterator.next()
    return result
}
