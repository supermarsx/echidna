package com.echidna.control.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreprocessorTelemetryTest {
    @Test
    fun `codec accepts only the exact committed ECHT value schema`() {
        val encoded = snapshot(
            sessionId = 41,
            generation = 0x0102_0304_0506_0708L,
            sequence = 0xffff_ffffL,
            flags = 7,
            blocks = 0xffff_fffeL,
            frames = 5L,
            failures = 0xffff_ffffL,
            mutations = 0x8000_0000L,
        )
        val decoded = LegacyPreprocessorTelemetryCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(41, decoded!!.sessionId)
        assertEquals(0x0102_0304_0506_0708L, decoded.generation)
        assertEquals(0xffff_ffffL, decoded.sequence)
        assertEquals(0x8000_0000L, decoded.mutations)

        listOf(0, 4, 6, 8, 44).forEach { offset ->
            assertNull(
                LegacyPreprocessorTelemetryCodec.decode(
                    encoded.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() },
                ),
            )
        }
        assertNull(
            LegacyPreprocessorTelemetryCodec.decode(
                encoded.clone().also { it[11] = (it[11].toInt() or 8).toByte() },
            ),
        )
        assertNull(LegacyPreprocessorTelemetryCodec.decode(ByteArray(47)))
        assertNull(LegacyPreprocessorTelemetryCodec.decode(null))
    }

    @Test
    fun `issuance ledger binds live lease to exact caller tuple and capability nonce`() {
        val fixture = Fixture()
        assertTrue(fixture.issue())
        assertTrue(fixture.hasLease())
        assertFalse(fixture.hasLease(uid = 10_124))
        assertFalse(fixture.hasLease(pid = 78))
        assertFalse(fixture.hasLease(process = "com.example.recorder:other"))
        assertFalse(fixture.hasLease(session = 42))
        assertFalse(fixture.hasLease(generation = 8L))
        assertFalse(fixture.hasLease(capabilityNonce = fixture.nonce(2)))

        fixture.now += LEGACY_CAPABILITY_LIFETIME_MS
        assertFalse(fixture.hasLease())

        val wrongResult = fixture.result(session = 42)
        assertFalse(fixture.ledger.record(fixture.pid, fixture.request(), wrongResult))
        assertFalse(
            fixture.ledger.record(
                fixture.pid,
                fixture.request(capabilityNonce = fixture.nonce(3)),
                fixture.result(capabilityNonce = fixture.nonce(4)),
            ),
        )
    }

    @Test
    fun `fresh caller attested mutations remain diagnostics and never prove processing`() {
        val fixture = Fixture()
        assertTrue(fixture.issue())
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 1L, 3, 100L, 1_000L, 4L, 9L)),
        )
        var diagnostics = fixture.store.snapshot(7L)
        assertFalse(diagnostics.processing)
        assertEquals(0L, diagnostics.totalBlocks)
        assertEquals("preprocessor", diagnostics.entries.single().route)
        assertEquals(41, diagnostics.entries.single().audioSessionId)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 2L, 3, 102L, 1_008L, 4L, 10L)),
        )
        diagnostics = fixture.store.snapshot(7L)
        assertFalse(diagnostics.processing)
        assertEquals(2L, diagnostics.totalBlocks)
        assertEquals(8L, diagnostics.totalFrames)
        assertEquals(1L, diagnostics.totalMutations)
        assertEquals("caller_attested_binder_v1", diagnostics.entries.single().verification)
        assertEquals("processing", diagnostics.entries.single().state)
        val json = diagnostics.toDiagnosticsJson(false, null).toString()
        assertTrue(json.contains("preprocessor"))
        assertTrue(json.contains("\"audioSessionId\":41"))
        assertTrue(json.contains("\"verification\":\"caller_attested_binder_v1\""))

        fixture.advance(1_501L)
        assertFalse(fixture.store.snapshot(7L).processing)
    }

    @Test
    fun `relay rejects rate replay ordering binding and policy mismatches`() {
        val fixture = Fixture()
        assertTrue(fixture.issue())
        assertEquals(
            LegacyPreprocessorTelemetryResult.SESSION_MISMATCH,
            fixture.report(snapshot(42, 7L, 1L, 3, 0, 0, 0, 0)),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.GENERATION_MISMATCH,
            fixture.report(snapshot(41, 8L, 1L, 3, 0, 0, 0, 0)),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY,
            fixture.report(snapshot(41, 7L, 1L, 3, 0, 0, 0, 0), pid = 78),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 10L, 3, 0, 0, 0, 0)),
        )
        fixture.advance(249L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.RATE_LIMITED,
            fixture.report(snapshot(41, 7L, 11L, 3, 0, 0, 0, 0)),
        )
        fixture.advance(1L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 11L, 3, 0, 0, 0, 0)),
        )
        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.STALE_SEQUENCE,
            fixture.report(snapshot(41, 7L, 11L, 3, 0, 0, 0, 0)),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.STALE_SEQUENCE,
            fixture.report(snapshot(41, 7L, 10L, 3, 0, 0, 0, 0)),
        )
        fixture.policyGeneration = 8L
        assertEquals(
            LegacyPreprocessorTelemetryResult.STALE_POLICY,
            fixture.report(snapshot(41, 7L, 12L, 3, 0, 0, 0, 0)),
        )
    }

    @Test
    fun `modular sequence and counters wrap while implausible deltas are bounded`() {
        val fixture = Fixture()
        assertTrue(fixture.issue())
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(
                snapshot(
                    41,
                    7L,
                    0xffff_ffffL,
                    3,
                    0xffff_ffffL,
                    0xffff_ffffL,
                    0xffff_ffffL,
                    0xffff_ffffL,
                ),
            ),
        )
        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 0L, 3, 0L, 0L, 0L, 0L)),
        )
        assertFalse(fixture.store.snapshot(7L).processing)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.COUNTER_BOUNDS,
            fixture.report(snapshot(41, 7L, 1L, 3, 100_000L, 0L, 0L, 0L)),
        )
    }

    @Test
    fun `identical tuple restart rebases only after a newly issued exact nonce`() {
        val fixture = Fixture()
        val firstNonce = fixture.nonce(1)
        val secondNonce = fixture.nonce(2)
        assertTrue(fixture.issue(firstNonce))
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 100L, 3, 500L, 2_000L, 0L, 10L)),
        )
        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 101L, 3, 501L, 2_004L, 0L, 11L)),
        )
        assertEquals(101L, fixture.store.snapshot(7L).entries.single().sequence)
        assertEquals(1L, fixture.store.snapshot(7L).totalMutations)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.STALE_SEQUENCE,
            fixture.report(snapshot(41, 7L, 1L, 3, 0L, 0L, 0L, 0L)),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY,
            fixture.report(
                snapshot(41, 7L, 1L, 3, 0L, 0L, 0L, 0L),
                capabilityNonce = secondNonce,
            ),
        )

        assertTrue(fixture.issue(secondNonce))
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 1L, 3, 0L, 0L, 0L, 0L)),
        )
        val rebased = fixture.store.snapshot(7L)
        assertEquals(1, rebased.entries.size)
        assertEquals(1L, rebased.entries.single().sequence)
        assertEquals(0L, rebased.totalBlocks)
        assertEquals(0L, rebased.totalMutations)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 2L, 3, 1L, 4L, 0L, 1L)),
        )
        assertEquals(1L, fixture.store.snapshot(7L).totalMutations)
        assertEquals(
            LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY,
            fixture.report(
                snapshot(41, 7L, 3L, 3, 2L, 8L, 0L, 2L),
                capabilityNonce = firstNonce,
            ),
        )
    }

    @Test
    fun `expired unauthorized and error snapshots never prove processing`() {
        val fixture = Fixture()
        assertTrue(fixture.issue())
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 1L, 3, 0L, 0L, 0L, 0L)),
        )
        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 2L, 7, 1L, 1L, 0L, 1L)),
        )
        assertFalse(fixture.store.snapshot(7L).processing)
        assertEquals("bypassed", fixture.store.snapshot(7L).entries.single().state)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 3L, 1, 2L, 2L, 0L, 2L)),
        )
        assertFalse(fixture.store.snapshot(7L).processing)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 4L, 3, 3L, 3L, 1L, 2L)),
        )
        val error = fixture.store.snapshot(7L)
        assertFalse(error.processing)
        assertEquals("error", error.entries.single().state)
    }

    @Test
    fun `telemetry caller authentication uses exact Binder uid pid process evidence`() {
        val running = listOf(
            CallerPolicyAuthorizer.RunningProcess(
                pid = 77,
                uid = 10_123,
                processName = "com.example.recorder",
                packageNames = setOf("com.example.recorder"),
            ),
        )
        assertEquals(
            "com.example.recorder",
            CallerPolicyAuthorizer.authorizeCapability(
                10_123,
                77,
                listOf("com.example.recorder"),
                running,
                "com.example.recorder",
            ),
        )
        assertNull(
            CallerPolicyAuthorizer.authorizeCapability(
                10_123,
                78,
                listOf("com.example.recorder"),
                running,
                "com.example.recorder",
            ),
        )
    }

    private class Fixture {
        var now = 1_000L
        var policyGeneration = 7L
        val uid = 10_123
        val pid = 77
        val process = "com.example.recorder"
        val session = 41
        val generation = 7L
        var activeNonce = nonce(1)
        val ledger = LegacyCapabilityIssuanceLedger(clockMs = { now })
        val store = AuthenticatedTelemetryStore(clockMs = { now })
        val relay = LegacyPreprocessorTelemetryRelay(ledger, store, { policyGeneration })

        fun request(
            session: Int = this.session,
            generation: Long = this.generation,
            capabilityNonce: ByteArray = activeNonce,
        ) = LegacyCapabilityRequest(
            uid = uid,
            packageName = process,
            processName = process,
            audioSessionId = session,
            generation = generation,
            nonce = capabilityNonce.clone(),
        )

        fun result(
            session: Int = this.session,
            generation: Long = this.generation,
            capabilityNonce: ByteArray = activeNonce,
        ): LegacyCapabilityResult {
            val request = request(session, generation, capabilityNonce)
            val body = LegacyCapabilityCodec.encodeBody(
                session,
                uid,
                generation,
                now,
                now + LEGACY_CAPABILITY_LIFETIME_MS,
                request.nonce,
                process,
                "{\"engine\":{},\"modules\":[]}".toByteArray(),
            )!!
            val envelope = LegacyCapabilityCodec.appendSignature(body, ByteArray(64) { 1 })!!
            return LegacyCapabilityResult(
                LegacyCapabilityStatus.OK,
                generation,
                envelope,
                LegacyCapabilityDiagnostic.ACCEPTED,
            )
        }

        fun issue(capabilityNonce: ByteArray = activeNonce): Boolean {
            activeNonce = capabilityNonce.clone()
            return ledger.record(pid, request(), result())
        }

        fun nonce(seed: Int): ByteArray = ByteArray(16) { (seed + it).toByte() }

        fun hasLease(
            uid: Int = this.uid,
            pid: Int = this.pid,
            process: String = this.process,
            session: Int = this.session,
            generation: Long = this.generation,
            capabilityNonce: ByteArray = activeNonce,
        ): Boolean = ledger.hasLive(
            uid,
            pid,
            process,
            session,
            generation,
            capabilityNonce,
            now,
        )

        fun report(
            snapshot: ByteArray,
            uid: Int = this.uid,
            pid: Int = this.pid,
            process: String = this.process,
            session: Int = this.session,
            generation: Long = this.generation,
            capabilityNonce: ByteArray = activeNonce,
        ): LegacyPreprocessorTelemetryResult = relay.report(
            uid,
            pid,
            process,
            session,
            generation,
            capabilityNonce,
            snapshot,
            now,
        )

        fun advance(milliseconds: Long) {
            now += milliseconds
        }
    }

    companion object {
        private fun snapshot(
            sessionId: Int,
            generation: Long,
            sequence: Long,
            flags: Int,
            blocks: Long,
            frames: Long,
            failures: Long,
            mutations: Long,
        ): ByteArray = ByteBuffer.allocate(PREPROCESSOR_TELEMETRY_VALUE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0x45434854)
            .putShort(1.toShort())
            .putShort(1.toShort())
            .putShort(PREPROCESSOR_TELEMETRY_VALUE_BYTES.toShort())
            .putShort(flags.toShort())
            .putInt(sessionId)
            .putLong(generation)
            .putInt(sequence.toInt())
            .putInt(blocks.toInt())
            .putInt(frames.toInt())
            .putInt(failures.toInt())
            .putInt(mutations.toInt())
            .putInt(0)
            .array()
    }
}
