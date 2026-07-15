package com.echidna.control.service

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreprocessorCapabilityTest {
    @Test
    fun `codec matches the frozen native unsigned fixture byte for byte`() {
        val preset = (
            "{\"name\":\"F\",\"engine\":{\"latencyMode\":\"LL\",\"blockMs\":10}," +
                "\"modules\":[{\"id\":\"mix\",\"wet\":100,\"outGain\":-12}]}"
            ).toByteArray()
        val body = LegacyCapabilityCodec.encodeBody(
            audioSessionId = 0x01020304,
            uid = 10_000,
            generation = 1L,
            issuedBoottimeMs = 100_000L,
            expiryBoottimeMs = 105_000L,
            nonce = ByteArray(16) { (it + 1).toByte() },
            processName = "com.example.recorder",
            preset = preset,
        )!!

        assertEquals(104, preset.size)
        assertEquals(234, body.size)
        assertEquals(FROZEN_BODY_HEX, body.toHex())
        assertEquals(
            "24f6e2d400359721a2066c755d48e5deb83171e07d429b7c26c82bca8d7fa69a",
            MessageDigest.getInstance("SHA-256").digest(preset).toHex(),
        )
        assertEquals(
            "b1c81db05b2c941cfae0ae8c2f403c83585a3bd8431f76a78627c0004841c2af",
            MessageDigest.getInstance("SHA-256").digest(body).toHex(),
        )
    }

    @Test
    fun `codec rejects noncanonical identity lifetime nonce and preset`() {
        val valid = requestBody()
        assertTrue(valid != null)
        assertEquals(null, requestBody(processName = "singlecomponent"))
        assertEquals(null, requestBody(processName = "1com.example.recorder"))
        assertEquals(null, requestBody(expiryMs = 105_001L))
        assertEquals(null, requestBody(nonce = ByteArray(16)))
        assertEquals(null, requestBody(preset = ByteArray(LEGACY_CAPABILITY_MAX_PRESET_BYTES + 1)))
    }

    @Test
    fun `issuer signs asynchronously from current policy and binds every request field`() {
        val clock = AtomicLong(25_000L)
        val policy = AtomicReference(policy(9L))
        val signer = RecordingSigner()
        val executor = BoundedCapabilityExecutor()
        try {
            val issuer = LegacyCapabilityIssuer(
                enabled = { true },
                policySource = { _, _ -> policy.get() },
                signer = signer,
                executor = executor,
                boottimeMs = clock::get,
            )
            val callerThread = Thread.currentThread().id
            val result = awaitResult(issuer, request(generation = 9L))

            assertEquals(LegacyCapabilityStatus.OK, result.status)
            assertEquals(LegacyCapabilityDiagnostic.ACCEPTED, result.diagnostic)
            assertNotEquals(callerThread, signer.threadId)
            assertTrue(result.envelope.size > LEGACY_CAPABILITY_FIXED_BODY_BYTES)
            val signedBody = signer.bodies.single()
            assertArrayEquals(signedBody, result.envelope.copyOf(signedBody.size))
            assertEquals(9L, java.nio.ByteBuffer.wrap(signedBody, 32, 8).long)
            assertEquals(25_000L, java.nio.ByteBuffer.wrap(signedBody, 40, 8).long)
            assertEquals(30_000L, java.nio.ByteBuffer.wrap(signedBody, 48, 8).long)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `issuer fails closed for disabled stale and post-sign generation changes`() {
        val disabledExecutor = BoundedCapabilityExecutor()
        try {
            val disabled = LegacyCapabilityIssuer(
                enabled = { false },
                policySource = { _, _ -> policy(3L) },
                signer = RecordingSigner(),
                executor = disabledExecutor,
                boottimeMs = { 1_000L },
            )
            assertEquals(LegacyCapabilityStatus.DENIED, awaitResult(disabled, request(3L)).status)
        } finally {
            disabledExecutor.close()
        }

        val current = AtomicReference(policy(3L))
        val signer = RecordingSigner { current.set(policy(4L)) }
        val executor = BoundedCapabilityExecutor()
        try {
            val issuer = LegacyCapabilityIssuer(
                enabled = { true },
                policySource = { _, _ -> current.get() },
                signer = signer,
                executor = executor,
                boottimeMs = { 1_000L },
            )
            assertEquals(LegacyCapabilityStatus.STALE, awaitResult(issuer, request(2L)).status)
            assertEquals(LegacyCapabilityStatus.STALE, awaitResult(issuer, request(3L)).status)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `issuer suppresses revoked dead and shutdown work`() {
        val enabled = AtomicBoolean(true)
        val revokeSigner = RecordingSigner { enabled.set(false) }
        val revokeExecutor = BoundedCapabilityExecutor()
        try {
            val issuer = LegacyCapabilityIssuer(
                enabled = enabled::get,
                policySource = { _, _ -> policy(5L) },
                signer = revokeSigner,
                executor = revokeExecutor,
                boottimeMs = { 2_000L },
            )
            val revoked = awaitResult(issuer, request(5L))
            assertEquals(LegacyCapabilityStatus.DENIED, revoked.status)
            assertEquals(LegacyCapabilityDiagnostic.FEATURE_DISABLED, revoked.diagnostic)
        } finally {
            revokeExecutor.close()
        }

        val alive = AtomicBoolean(true)
        val signed = CountDownLatch(1)
        val callback = AtomicReference<LegacyCapabilityResult?>()
        val deathExecutor = BoundedCapabilityExecutor()
        try {
            val issuer = LegacyCapabilityIssuer(
                enabled = { true },
                policySource = { _, _ -> policy(6L) },
                signer = RecordingSigner {
                    alive.set(false)
                    signed.countDown()
                },
                executor = deathExecutor,
                boottimeMs = { 3_000L },
            )
            issuer.request(
                request(6L),
                object : LegacyCapabilityResultSink {
                    override fun isAlive(): Boolean = alive.get()
                    override fun complete(result: LegacyCapabilityResult) {
                        callback.set(result)
                    }
                },
            )
            assertTrue(signed.await(3, TimeUnit.SECONDS))
            Thread.sleep(50L)
            assertEquals(null, callback.get())
            issuer.close()
            val shutdown = AtomicReference<LegacyCapabilityResult>()
            issuer.request(request(6L).copy(nonce = ByteArray(16) { 99 }), shutdown::set)
            assertEquals(LegacyCapabilityDiagnostic.SIGNING_QUEUE_FULL, shutdown.get().diagnostic)
        } finally {
            deathExecutor.close()
        }
    }

    @Test
    fun `rate limiter is nonce fresh session bounded and uid bounded`() {
        val limiter = LegacyCapabilityRateLimiter(maxPerSession = 2, maxPendingPerUid = 2)
        val first = ByteArray(16) { 1 }
        assertTrue(limiter.acquire(10_000, 1, first, 100L))
        assertFalse(limiter.acquire(10_000, 1, ByteArray(16) { 2 }, 101L))
        assertTrue(limiter.acquire(10_000, 2, ByteArray(16) { 3 }, 102L))
        assertFalse(limiter.acquire(10_000, 3, ByteArray(16) { 4 }, 103L))
        limiter.release(10_000, 1)
        limiter.release(10_000, 2)
        assertFalse(limiter.acquire(10_000, 1, first, 104L))
        assertTrue(limiter.acquire(10_000, 1, ByteArray(16) { 5 }, 105L))
        limiter.release(10_000, 1)
        assertFalse(limiter.acquire(10_000, 1, ByteArray(16) { 6 }, 106L))
        assertTrue(limiter.acquire(10_000, 1, ByteArray(16) { 7 }, 20_000L))
    }

    @Test
    fun `default limiter sustains jittered renewals while retaining rolling abuse bounds`() {
        val limiter = LegacyCapabilityRateLimiter()
        val startMs = 100_000L

        repeat(17) { index ->
            val nowMs = startMs + index * 2_000L + (index % 3) * 125L
            val nonce = ByteArray(16) { byte -> (index + byte + 1).toByte() }
            assertTrue("renewal $index rejected at $nowMs", limiter.acquire(10_000, 77, nonce, nowMs))
            limiter.release(10_000, 77)
        }

        val burst = LegacyCapabilityRateLimiter()
        repeat(8) { index ->
            val nonce = ByteArray(16) { byte -> (index + byte + 33).toByte() }
            assertTrue(burst.acquire(10_000, 88, nonce, startMs + index))
            burst.release(10_000, 88)
        }
        assertFalse(burst.acquire(10_000, 88, ByteArray(16) { 99 }, startMs + 9L))
        assertTrue(burst.acquire(10_000, 88, ByteArray(16) { 100 }, startMs + 10_001L))
    }

    @Test
    fun `limiter enforces bounded uid and global signing concurrency`() {
        val limiter = LegacyCapabilityRateLimiter(
            maxPendingPerUid = 2,
            maxPendingGlobal = 3,
        )
        assertTrue(limiter.acquire(10_000, 1, ByteArray(16) { 1 }, 1_000L))
        assertTrue(limiter.acquire(10_000, 2, ByteArray(16) { 2 }, 1_000L))
        assertFalse(limiter.acquire(10_000, 3, ByteArray(16) { 3 }, 1_000L))
        assertTrue(limiter.acquire(10_001, 4, ByteArray(16) { 4 }, 1_000L))
        assertFalse(limiter.acquire(10_002, 5, ByteArray(16) { 5 }, 1_000L))
    }

    @Test
    fun `issuer sustains live overlapping renewals beyond thirty seconds with signing jitter`() {
        val clock = AtomicLong(100_000L)
        val jitterMs = longArrayOf(0L, 700L, 150L, 900L, 300L, 50L)
        val renewalIndex = AtomicInteger()
        val signer = RecordingSigner {
            clock.addAndGet(jitterMs[renewalIndex.getAndIncrement() % jitterMs.size])
        }
        val executor = BoundedCapabilityExecutor()
        try {
            val issuer = LegacyCapabilityIssuer(
                enabled = { true },
                policySource = { _, _ -> policy(12L) },
                signer = signer,
                executor = executor,
                boottimeMs = clock::get,
            )
            var priorExpiry = 0L
            repeat(17) { index ->
                clock.set(100_000L + index * 2_000L)
                val nonce = ByteArray(16) { byte -> (index + byte + 1).toByte() }
                val result = awaitResult(issuer, request(12L).copy(nonce = nonce))
                assertEquals("renewal $index", LegacyCapabilityStatus.OK, result.status)
                val body = ByteBuffer.wrap(result.envelope)
                val issued = body.getLong(40)
                val expiry = body.getLong(48)
                assertTrue("renewal $index expired in signer queue", clock.get() < expiry)
                if (priorExpiry > 0L) {
                    assertTrue("renewal $index lost overlap", issued < priorExpiry)
                }
                assertEquals(LEGACY_CAPABILITY_LIFETIME_MS, expiry - issued)
                priorExpiry = expiry
            }
            assertTrue(clock.get() >= 132_000L)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `provider capability API is append-only version two`() {
        assertEquals(3L, CAPABILITY_PROVIDER_API_VERSION)
        assertEquals(-1, LegacyCapabilityStatus.DENIED)
        assertEquals(-116, LegacyCapabilityStatus.STALE)
        assertEquals(-126, LegacyCapabilityStatus.KEY_UNAVAILABLE)
        assertEquals(-127, LegacyCapabilityStatus.EXPIRED)
        assertEquals(-129, LegacyCapabilityStatus.SIGNING_FAILED)
    }

    private fun awaitResult(
        issuer: LegacyCapabilityIssuer,
        request: LegacyCapabilityRequest,
    ): LegacyCapabilityResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<LegacyCapabilityResult>()
        issuer.request(request) {
            result.set(it)
            latch.countDown()
        }
        assertTrue("capability callback timed out", latch.await(3, TimeUnit.SECONDS))
        return result.get()
    }

    private fun request(generation: Long) = LegacyCapabilityRequest(
        uid = 10_000,
        packageName = "com.example.recorder",
        processName = "com.example.recorder",
        audioSessionId = 77,
        generation = generation,
        nonce = ByteArray(16) { (it + generation.toInt()).toByte() },
    )

    private fun policy(generation: Long) = LegacyCapabilityPolicy(
        generation,
        "com.example.recorder",
        "{\"engine\":{},\"modules\":[]}".toByteArray(),
    )

    private fun requestBody(
        processName: String = "com.example.recorder",
        expiryMs: Long = 105_000L,
        nonce: ByteArray = ByteArray(16) { (it + 1).toByte() },
        preset: ByteArray = "{\"engine\":{},\"modules\":[]}".toByteArray(),
    ) = LegacyCapabilityCodec.encodeBody(
        1,
        10_000,
        1L,
        100_000L,
        expiryMs,
        nonce,
        processName,
        preset,
    )

    private class RecordingSigner(private val beforeReturn: () -> Unit = {}) :
        LegacyCapabilitySigner {
        val bodies = mutableListOf<ByteArray>()
        @Volatile var threadId = -1L

        override fun preparePublicKey(): ByteArray = byteArrayOf(1)

        override fun sign(body: ByteArray): ByteArray {
            threadId = Thread.currentThread().id
            bodies += body.clone()
            beforeReturn()
            return ByteArray(70).also { it[0] = 0x30 }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val FROZEN_BODY_HEX =
            "45434843000100013e66a36edee95d81a0d649fc3b8635300102030400002710" +
                "000000000000000100000000000186a00000000000019a280102030405060708" +
                "090a0b0c0d0e0f1024f6e2d400359721a2066c755d48e5deb83171e07d429b7c" +
                "26c82bca8d7fa69a001400000068636f6d2e6578616d706c652e7265636f7264" +
                "65727b226e616d65223a2246222c22656e67696e65223a7b226c6174656e6379" +
                "4d6f6465223a224c4c222c22626c6f636b4d73223a31307d2c226d6f64756c" +
                "6573223a5b7b226964223a226d6978222c22776574223a3130302c226f757447" +
                "61696e223a2d31327d5d7d"
    }
}
