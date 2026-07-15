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
