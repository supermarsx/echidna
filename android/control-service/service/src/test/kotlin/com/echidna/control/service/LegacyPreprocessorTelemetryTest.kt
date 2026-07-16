package com.echidna.control.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreprocessorTelemetryTest {
    @Test
    fun `v2 codec accepts the native golden proof and rejects tamper wrong key and framing`() {
        val key = ByteArray(32) { it.toByte() }
        val golden = hex(
            "4543485400020002007000070000002901020304050607080102030405060708" +
                "090a0b0c0d0e0f10fffffffffffffffe00000005ffffffff80000000630dcd29" +
                "66c4336691125448bbb25b4f00000000416d73deaf6412d3bd08f9f905d69f07" +
                "aace87ae5f58ab1a73935083f8581a0d",
        )
        val decoded = LegacyPreprocessorTelemetryProofCodec.verify(golden, key)
        assertNotNull(decoded)
        assertEquals(41, decoded!!.sessionId)
        assertEquals(0x0102_0304_0506_0708L, decoded.generation)
        assertEquals(0xffff_ffffL, decoded.sequence)
        assertEquals(0x8000_0000L, decoded.mutations)

        listOf(0, 4, 6, 8, 11, 24, 60, 76, 80, 111).forEach { offset ->
            assertNull(
                LegacyPreprocessorTelemetryProofCodec.verify(
                    golden.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() },
                    key,
                ),
            )
        }
        assertNull(LegacyPreprocessorTelemetryProofCodec.verify(golden, ByteArray(32) { 7 }))
        assertNull(LegacyPreprocessorTelemetryProofCodec.verify(golden.copyOf(111), key))
        assertNull(LegacyPreprocessorTelemetryProofCodec.verify(golden, ByteArray(31)))
    }

    @Test
    fun `missing key rotation and key id mismatch fail closed`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val rotated = ByteArray(32) { (it + 33).toByte() }
        val proof = proof(key, 41, 7L, nonce(1), 1L, 3, 0L, 0L, 0L, 0L)
        val missing = LegacyPreprocessorTelemetryProofVerifier { null }
        assertFalse(missing.prepare())
        assertFalse(missing.available())
        assertNull(missing.verify(proof))

        var selected = key
        val verifier = LegacyPreprocessorTelemetryProofVerifier { selected.clone() }
        assertTrue(verifier.prepare())
        assertNotNull(verifier.verify(proof))
        selected = rotated
        assertTrue(verifier.prepare())
        assertNull(verifier.verify(proof))
        assertNotNull(
            verifier.verify(proof(rotated, 41, 7L, nonce(1), 1L, 3, 0, 0, 0, 0)),
        )

        val wrongId = proof.clone().also {
            MessageDigest.getInstance("SHA-256").digest(key).copyOf(16).forEachIndexed { index, byte ->
                it[60 + index] = (byte.toInt() xor 0x55).toByte()
            }
            signProof(it, key)
        }
        assertNull(LegacyPreprocessorTelemetryProofCodec.verify(wrongId, key))
        verifier.close()
        assertFalse(verifier.available())
    }

    @Test
    fun `hmac proof requires a live incarnation and a positive current delta to process`() {
        val fixture = Fixture()
        val firstNonce = fixture.nonce(1)
        assertTrue(fixture.issue(firstNonce))
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.reportProof(proof(fixture.proofKey, 41, 7L, firstNonce, 100L, 3, 50, 200, 0, 9)),
        )
        var telemetry = fixture.store.snapshot(7L)
        assertFalse(telemetry.processing)
        assertEquals("effect_hmac_v1", telemetry.entries.single().verification)
        assertEquals(0L, telemetry.totalMutations)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.reportProof(proof(fixture.proofKey, 41, 7L, firstNonce, 101L, 3, 52, 208, 0, 10)),
        )
        telemetry = fixture.store.snapshot(7L)
        assertTrue(telemetry.processing)
        assertEquals(2L, telemetry.totalBlocks)
        assertEquals(8L, telemetry.totalFrames)
        assertEquals(1L, telemetry.totalMutations)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.STALE_SEQUENCE,
            fixture.reportProof(proof(fixture.proofKey, 41, 7L, firstNonce, 101L, 3, 52, 208, 0, 10)),
        )

        val secondNonce = fixture.nonce(2)
        assertTrue(fixture.issue(secondNonce))
        assertEquals(
            LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY,
            fixture.reportProof(proof(fixture.proofKey, 41, 7L, firstNonce, 102L, 3, 53, 212, 0, 11)),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.reportProof(proof(fixture.proofKey, 41, 7L, secondNonce, 1L, 3, 53, 212, 0, 11)),
        )
        telemetry = fixture.store.snapshot(7L)
        assertFalse(telemetry.processing)
        assertEquals(0L, telemetry.totalMutations)

        fixture.advance(250L)
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.reportProof(proof(fixture.proofKey, 41, 7L, secondNonce, 2L, 3, 54, 216, 0, 12)),
        )
        assertTrue(fixture.store.snapshot(7L).processing)
        assertEquals(1L, fixture.store.snapshot(7L).totalMutations)
    }

    @Test
    fun `proof relay rejects tamper stale policy and v1 downgrade while retaining diagnostics`() {
        val fixture = Fixture()
        assertTrue(fixture.issue())
        val valid = proof(fixture.proofKey, 41, 7L, fixture.activeNonce, 1L, 3, 0, 0, 0, 0)
        assertEquals(
            LegacyPreprocessorTelemetryResult.AUTHENTICATION_FAILED,
            fixture.reportProof(valid.clone().also { it[111] = (it[111].toInt() xor 1).toByte() }),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.AUTHENTICATION_FAILED,
            fixture.reportProof(snapshot(41, 7L, 1L, 3, 0, 0, 0, 0)),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.SESSION_MISMATCH,
            fixture.reportProof(
                proof(fixture.proofKey, 42, 7L, fixture.activeNonce, 1L, 3, 0, 0, 0, 0),
            ),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.GENERATION_MISMATCH,
            fixture.reportProof(
                proof(fixture.proofKey, 41, 8L, fixture.activeNonce, 1L, 3, 0, 0, 0, 0),
            ),
        )
        assertEquals(
            LegacyPreprocessorTelemetryResult.NO_LIVE_CAPABILITY,
            fixture.reportProof(
                proof(fixture.proofKey, 41, 7L, fixture.nonce(9), 1L, 3, 0, 0, 0, 0),
            ),
        )
        fixture.policyGeneration = 8L
        assertEquals(
            LegacyPreprocessorTelemetryResult.STALE_POLICY,
            fixture.reportProof(valid),
        )

        fixture.policyGeneration = 7L
        assertEquals(
            LegacyPreprocessorTelemetryResult.ACCEPTED,
            fixture.report(snapshot(41, 7L, 1L, 3, 1, 4, 0, 1)),
        )
        assertFalse(fixture.store.snapshot(7L).processing)
        assertEquals("caller_attested_binder_v1", fixture.store.snapshot(7L).entries.single().verification)
    }

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

    private class Fixture {
        var now = 1_000L
        var policyGeneration = 7L
        val uid = 10_123
        val pid = 77
        val process = "com.example.recorder"
        val session = 41
        val generation = 7L
        var activeNonce = nonce(1)
        val proofKey = ByteArray(32) { (it + 1).toByte() }
        val ledger = LegacyCapabilityIssuanceLedger(clockMs = { now })
        val store = AuthenticatedTelemetryStore(clockMs = { now })
        val verifier = LegacyPreprocessorTelemetryProofVerifier { proofKey.clone() }
        val relay = LegacyPreprocessorTelemetryRelay(
            ledger,
            store,
            { policyGeneration },
            verifier,
        )

        init {
            assertTrue(verifier.prepare())
        }

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

        fun reportProof(
            proof: ByteArray,
            uid: Int = this.uid,
            pid: Int = this.pid,
            process: String = this.process,
            session: Int = this.session,
            generation: Long = this.generation,
        ): LegacyPreprocessorTelemetryResult = relay.reportProof(
            uid,
            pid,
            process,
            session,
            generation,
            proof,
            now,
        )

        fun advance(milliseconds: Long) {
            now += milliseconds
        }
    }

    companion object {
        private val PROOF_DOMAIN =
            "ECHIDNA_PREPROCESSOR_TELEMETRY_PROOF_V2".toByteArray(StandardCharsets.US_ASCII)

        private fun nonce(seed: Int): ByteArray = ByteArray(16) { (seed + it).toByte() }

        private fun proof(
            key: ByteArray,
            sessionId: Int,
            generation: Long,
            capabilityNonce: ByteArray,
            sequence: Long,
            flags: Int,
            blocks: Long,
            frames: Long,
            failures: Long,
            mutations: Long,
        ): ByteArray {
            val value = ByteBuffer.allocate(PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(0x45434854)
                .putShort(2.toShort())
                .putShort(2.toShort())
                .putShort(PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES.toShort())
                .putShort(flags.toShort())
                .putInt(sessionId)
                .putLong(generation)
                .put(capabilityNonce)
                .putInt(sequence.toInt())
                .putInt(blocks.toInt())
                .putInt(frames.toInt())
                .putInt(failures.toInt())
                .putInt(mutations.toInt())
                .put(MessageDigest.getInstance("SHA-256").digest(key).copyOf(16))
                .putInt(0)
                .array()
            signProof(value, key)
            return value
        }

        private fun signProof(value: ByteArray, key: ByteArray) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update(PROOF_DOMAIN)
            mac.update(value, 0, 80)
            val tag = mac.doFinal()
            tag.copyInto(value, 80)
            tag.fill(0)
        }

        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

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
