package com.echidna.control.service

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedTelemetryWireTest {
    @Test
    fun `strict parser accepts exact v2 schema and all negotiated routes`() {
        listOf(
            "aaudio",
            "audiorecord",
            "opensl",
            "tinyalsa",
            "libc_read",
            "api",
            "unknown",
            "lsposed",
            "preprocessor",
        ).forEach { route ->
            val parsed = AuthenticatedTelemetryWire.parse(validJson(route = route))

            assertEquals(route, parsed?.route?.wireName)
        }
    }

    @Test
    fun `strict parser rejects malformed unknown spoofable and inconsistent fields`() {
        assertNull(AuthenticatedTelemetryWire.parse(validJson(extraRoot = ",\"uid\":10001")))
        assertNull(AuthenticatedTelemetryWire.parse(validJson(route = "made_up")))
        assertNull(AuthenticatedTelemetryWire.parse(validJson(process = "com.example.voice/evil")))
        assertNull(AuthenticatedTelemetryWire.parse(validJson(mutations = 0L)))
        assertNull(
            AuthenticatedTelemetryWire.parse(
                validJson(state = "installed", mutations = 1L),
            ),
        )
        assertNull(
            AuthenticatedTelemetryWire.parse(
                validJson().replace("\"type\":\"telemetry\"", "type:'telemetry'"),
            ),
        )
        assertNull(
            AuthenticatedTelemetryWire.parse(
                validJson(extraRoot = ",\"type\":\"telemetry\""),
            ),
        )
        assertNull(AuthenticatedTelemetryWire.parse("not-json"))
    }

    @Test
    fun `framed reader rejects oversize truncated and malformed utf8`() {
        val oversize = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAX_TELEMETRY_FRAME_BYTES + 1)
            .array()
        assertThrowsIOException { AuthenticatedTelemetryWire.readFrame(ByteArrayInputStream(oversize)) }

        val truncated = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(20)
            .putInt(1)
            .array()
        assertThrowsEof { AuthenticatedTelemetryWire.readFrame(ByteArrayInputStream(truncated)) }

        val malformedUtf8 = byteArrayOf(0, 0, 0, 2, 0xc3.toByte(), 0x28)
        assertThrowsIOException {
            AuthenticatedTelemetryWire.readFrame(ByteArrayInputStream(malformedUtf8))
        }
    }

    @Test
    fun `process association rejects a process spoofed across peer uid packages`() {
        val packages = setOf("com.example.voice", "com.example.shared")

        assertTrue(processBelongsToPeerPackages("com.example.voice", packages))
        assertTrue(processBelongsToPeerPackages("com.example.voice:recording", packages))
        assertFalse(processBelongsToPeerPackages("com.example.voicemail", packages))
        assertFalse(processBelongsToPeerPackages("com.attacker.voice", packages))
    }

    @Test
    fun `per-peer receive limiter rejects sustained frame flood`() {
        val limiter = PeerTelemetryRateLimiter(windowMs = 2_000L, maxFrames = 8)

        repeat(8) { index -> assertTrue(limiter.allow(index * 200L)) }
        assertFalse(limiter.allow(1_600L))
        assertTrue(limiter.allow(2_000L))
    }

    // --- Schema v3 -------------------------------------------------------------

    @Test
    fun `strict parser accepts v3 and threads the added evidence fields`() {
        val parsed = AuthenticatedTelemetryWire.parse(
            validJsonV3(
                bypasses = 2L,
                installEvents = 4L,
                installFailures = 5L,
                installed = true,
            ),
        )

        assertEquals(2L, parsed?.deltas?.bypasses)
        assertEquals(4L, parsed?.deltas?.installEvents)
        assertEquals(5L, parsed?.deltas?.installFailures)
        assertEquals(true, parsed?.installed)
        // v2 fields still parse unchanged in a v3 frame.
        assertEquals(1L, parsed?.deltas?.mutations)
        assertEquals("aaudio", parsed?.route?.wireName)
    }

    @Test
    fun `strict parser still rejects unknown keys under v3 on root and deltas`() {
        assertNull(AuthenticatedTelemetryWire.parse(validJsonV3(extraRoot = ",\"uid\":10001")))
        assertNull(AuthenticatedTelemetryWire.parse(validJsonV3(extraDelta = ",\"attacker\":1")))
        // The latched level must be a real boolean, not a coercible string.
        assertNull(
            AuthenticatedTelemetryWire.parse(
                validJsonV3().replace("\"installed\":false", "\"installed\":\"true\""),
            ),
        )
    }

    @Test
    fun `version and its key-set must agree exactly`() {
        // schemaVersion 3 but a v2 key-set (missing the added fields) is rejected.
        assertNull(AuthenticatedTelemetryWire.parse(validJson().replace("\"schemaVersion\":2", "\"schemaVersion\":3")))
        // schemaVersion 2 but carrying v3 delta keys is rejected.
        assertNull(
            AuthenticatedTelemetryWire.parse(
                validJson(mutations = 0L, state = "bypassed")
                    .replace(
                        "\"mutations\":0",
                        "\"mutations\":0,\"bypasses\":1,\"installEvents\":0,\"installFailures\":0",
                    ),
            ),
        )
        // An unsupported future version is rejected outright.
        assertNull(AuthenticatedTelemetryWire.parse(validJsonV3().replace("\"schemaVersion\":3", "\"schemaVersion\":4")))
    }

    @Test
    fun `v3 rejects a block-outcome partition that exceeds the block count`() {
        assertNull(
            AuthenticatedTelemetryWire.parse(
                validJsonV3(blocks = 2L, mutations = 1L, bypasses = 1L, failures = 1L),
            ),
        )
        // Exactly summing to blocks is still valid.
        assertEquals(
            2L,
            AuthenticatedTelemetryWire.parse(
                validJsonV3(blocks = 2L, mutations = 1L, bypasses = 1L, failures = 0L),
            )?.deltas?.blocks,
        )
    }

    @Test
    fun `v2 frames remain accepted with the added counters defaulted to zero`() {
        val parsed = AuthenticatedTelemetryWire.parse(validJson())

        assertEquals(0L, parsed?.deltas?.bypasses)
        assertEquals(0L, parsed?.deltas?.installEvents)
        assertEquals(0L, parsed?.deltas?.installFailures)
        assertEquals(false, parsed?.installed)
    }

    private fun assertThrowsIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun assertThrowsEof(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected EOFException")
        } catch (_: EOFException) {
            // Expected.
        }
    }
}

class AuthenticatedTelemetryStoreTest {
    @Test
    fun `processing requires current generation and trusted recent mutation receipt`() {
        var now = 10_000L
        val store = AuthenticatedTelemetryStore(clockMs = { now })
        val peer = AuthenticatedPeer(uid = 10_123, pid = 321)

        assertEquals(
            TelemetryRecordResult.ACCEPTED,
            store.record(frame(sequence = 1L, state = "installed", mutations = 0L), peer, 7L),
        )
        assertFalse(store.snapshot(7L).processing)

        now += 250L
        assertEquals(
            TelemetryRecordResult.ACCEPTED,
            store.record(frame(sequence = 2L), peer, 7L),
        )
        assertTrue(store.snapshot(7L).processing)

        now += 1_501L
        assertFalse(store.snapshot(7L).processing)
        assertTrue(store.snapshot(7L).entries.isNotEmpty())

        assertEquals(
            TelemetryRecordResult.STALE_GENERATION,
            store.record(frame(sequence = 3L, generation = 7L), peer, 8L),
        )
        assertTrue(store.snapshot(8L).entries.isEmpty())
    }

    @Test
    fun `aggregation accepts uint32 sequence wrap and rejects replay`() {
        var now = 1_000L
        val store = AuthenticatedTelemetryStore(clockMs = { now })
        val peer = AuthenticatedPeer(uid = 10_001, pid = 99)

        listOf(0xffff_fffeL, 0xffff_ffffL, 0L).forEach { sequence ->
            assertEquals(
                TelemetryRecordResult.ACCEPTED,
                store.record(frame(sequence = sequence), peer, 7L),
            )
            now += 10L
        }
        assertEquals(
            TelemetryRecordResult.STALE_SEQUENCE,
            store.record(frame(sequence = 0xffff_ffffL), peer, 7L),
        )
        val entry = store.snapshot(7L).entries.single()
        assertEquals(3L, entry.blocks)
        assertEquals(3L, entry.mutations)
        assertEquals(0L, entry.sequence)
    }

    @Test
    fun `store expires disconnected peers and remains entry bounded`() {
        var now = 1_000L
        val store = AuthenticatedTelemetryStore(
            clockMs = { now },
            maxEntries = 2,
            entryTtlMs = 2_000L,
        )
        val peer = AuthenticatedPeer(uid = 10_001, pid = 99)
        store.record(frame(sequence = 1L, process = "com.example.one"), peer, 7L)
        now += 1L
        store.record(frame(sequence = 2L, process = "com.example.two"), peer, 7L)
        now += 1L
        store.record(frame(sequence = 3L, process = "com.example.three"), peer, 7L)

        assertEquals(2, store.snapshot(7L).entries.size)
        assertFalse(store.snapshot(7L).entries.any { it.process == "com.example.one" })

        now += 2_001L
        assertTrue(store.snapshot(7L).entries.isEmpty())
    }

    @Test
    fun `v3 counters aggregate into the entry and snapshot json and still reject replay`() {
        var now = 4_000L
        val store = AuthenticatedTelemetryStore(clockMs = { now })
        val peer = AuthenticatedPeer(uid = 10_055, pid = 202)

        assertEquals(
            TelemetryRecordResult.ACCEPTED,
            store.record(
                v3Frame(sequence = 1L, bypasses = 2L, installEvents = 3L, installFailures = 1L),
                peer,
                7L,
            ),
        )
        now += 10L
        assertEquals(
            TelemetryRecordResult.ACCEPTED,
            store.record(
                v3Frame(sequence = 2L, bypasses = 1L, installEvents = 1L, installFailures = 4L),
                peer,
                7L,
            ),
        )
        // A replayed sequence is still refused after v3 fields were added.
        assertEquals(
            TelemetryRecordResult.STALE_SEQUENCE,
            store.record(v3Frame(sequence = 2L), peer, 7L),
        )

        val snapshot = store.snapshot(7L)
        val entry = snapshot.entries.single()
        assertEquals(3L, entry.bypasses)
        assertEquals(4L, entry.installEvents)
        assertEquals(5L, entry.installFailures)
        assertTrue(entry.installed)
        assertEquals(3L, snapshot.totalBypasses)
        assertEquals(4L, snapshot.totalInstallEvents)
        assertEquals(5L, snapshot.totalInstallFailures)
        assertTrue(snapshot.anyInstalled)

        val json = snapshot.toDiagnosticsJson(includeTrends = false, legacy = null).toString()
        assertTrue(json.contains("\"totalBypasses\":3"))
        assertTrue(json.contains("\"totalInstallEvents\":4"))
        assertTrue(json.contains("\"totalInstallFailures\":5"))
        assertTrue(json.contains("\"bypasses\":3"))
        assertTrue(json.contains("\"installEvents\":4"))
        assertTrue(json.contains("\"installFailures\":5"))
        assertTrue(json.contains("\"installed\":true"))
    }

    @Test
    fun `caller attested routes remain diagnostic while mixed trusted routes retain proof`() {
        var now = 5_000L
        val peer = AuthenticatedPeer(uid = 10_001, pid = 99)
        val callerStore = AuthenticatedTelemetryStore(clockMs = { now })
        val caller = frame(sequence = 1L).copy(
            route = AuthenticatedTelemetryRoute.PREPROCESSOR,
            audioSessionId = 41,
            verification = AuthenticatedTelemetryVerification.CALLER_ATTESTED_BINDER_V1,
        )

        assertEquals(TelemetryRecordResult.ACCEPTED, callerStore.record(caller, peer, 7L))
        val callerSnapshot = callerStore.snapshot(7L)
        assertFalse(callerSnapshot.processing)
        assertEquals("processing", callerSnapshot.entries.single().state)
        assertEquals(
            "caller_attested_binder_v1",
            callerSnapshot.entries.single().verification,
        )
        assertTrue(
            callerSnapshot.toLiveJson(null)
                .contains("\"verification\":\"caller_attested_binder_v1\""),
        )

        val mixedStore = AuthenticatedTelemetryStore(clockMs = { now })
        assertEquals(
            TelemetryRecordResult.ACCEPTED,
            mixedStore.record(frame(sequence = 1L), peer, 7L),
        )
        now += 1L
        assertEquals(TelemetryRecordResult.ACCEPTED, mixedStore.record(caller, peer, 7L))
        val mixed = mixedStore.snapshot(7L)
        assertTrue(mixed.processing)
        val json = mixed.toLiveJson(null)
        assertTrue(json.contains("\"verification\":\"mixed_route_verification_v1\""))
        assertTrue(json.contains("\"verification\":\"authenticated_socket_v2\""))
        assertTrue(json.contains("\"verification\":\"caller_attested_binder_v1\""))
    }
}

private fun validJson(
    sequence: Long = 1L,
    process: String = "com.example.voice",
    route: String = "aaudio",
    generation: Long = 7L,
    state: String = "processing",
    mutations: Long = 1L,
    extraRoot: String = "",
): String = """
    {
      "schemaVersion":2,
      "type":"telemetry",
      "sequence":$sequence,
      "senderMonotonicMs":1234,
      "process":"$process",
      "route":"$route",
      "generation":$generation,
      "state":"$state",
      "deltas":{"blocks":1,"frames":192,"failures":0,"mutations":$mutations}
      $extraRoot
    }
""".trimIndent()

private fun frame(
    sequence: Long,
    process: String = "com.example.voice",
    generation: Long = 7L,
    state: String = "processing",
    mutations: Long = 1L,
): AuthenticatedTelemetryFrame = AuthenticatedTelemetryWire.parse(
    validJson(
        sequence = sequence,
        process = process,
        generation = generation,
        state = state,
        mutations = mutations,
    ),
)!!

private fun validJsonV3(
    sequence: Long = 1L,
    process: String = "com.example.voice",
    route: String = "aaudio",
    generation: Long = 7L,
    state: String = "processing",
    blocks: Long = 9L,
    frames: Long = 1728L,
    failures: Long = 0L,
    mutations: Long = 1L,
    bypasses: Long = 0L,
    installEvents: Long = 0L,
    installFailures: Long = 0L,
    installed: Boolean = false,
    extraRoot: String = "",
    extraDelta: String = "",
): String = """
    {
      "schemaVersion":3,
      "type":"telemetry",
      "sequence":$sequence,
      "senderMonotonicMs":1234,
      "process":"$process",
      "route":"$route",
      "generation":$generation,
      "state":"$state",
      "deltas":{"blocks":$blocks,"frames":$frames,"failures":$failures,"mutations":$mutations,"bypasses":$bypasses,"installEvents":$installEvents,"installFailures":$installFailures$extraDelta},
      "installed":$installed
      $extraRoot
    }
""".trimIndent()

private fun v3Frame(
    sequence: Long,
    process: String = "com.example.voice",
    generation: Long = 7L,
    bypasses: Long = 1L,
    installEvents: Long = 2L,
    installFailures: Long = 0L,
    installed: Boolean = true,
): AuthenticatedTelemetryFrame = AuthenticatedTelemetryWire.parse(
    validJsonV3(
        sequence = sequence,
        process = process,
        generation = generation,
        bypasses = bypasses,
        installEvents = installEvents,
        installFailures = installFailures,
        installed = installed,
    ),
)!!
