package com.echidna.control.service

import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing step is the only thing that makes a capability envelope trustworthy to the native
 * effect. These tests pin what happens when it does NOT succeed: every failure mode must surface
 * as a refusal carrying an empty envelope, and must be distinguishable by the caller so a missing
 * key (recoverable, retry after provisioning) is not reported as a broken signature.
 */
class LegacyCapabilitySigningFailureTest {
    @Test
    fun anUnavailableKeystoreKeyIsReportedAsKeyUnavailableAndNeverAsAnIssuedCapability() {
        // The four exception types below are all "the key is not usable yet" on a real device:
        // AndroidKeyStore not provisioned, key destroyed by a lockscreen change, alias absent.
        listOf(
            KeyStoreException("keystore is not initialised"),
            UnrecoverableKeyException("key is not recoverable"),
            IllegalStateException("keystore entry missing"),
            IllegalArgumentException("unsupported key algorithm"),
        ).forEach { failure ->
            val result = issueWithSigner(ThrowingSigner(failure))

            assertEquals(
                "wrong status for ${failure::class.java.simpleName}",
                LegacyCapabilityStatus.KEY_UNAVAILABLE,
                result.status,
            )
            assertEquals(
                LegacyCapabilityDiagnostic.KEY_UNAVAILABLE,
                result.diagnostic,
            )
            assertEquals(0, result.envelope.size)
            assertNotEquals(LegacyCapabilityStatus.OK, result.status)
        }
    }

    @Test
    fun anUnexpectedSignerFailureIsReportedAsSigningFailedRatherThanAsAMissingKey() {
        // Anything outside the key-unavailable taxonomy is a genuine signing fault. Collapsing it
        // into KEY_UNAVAILABLE would tell the client to retry a request that can never succeed.
        val result = issueWithSigner(ThrowingSigner(RuntimeException("HAL signature engine died")))

        assertEquals(LegacyCapabilityStatus.SIGNING_FAILED, result.status)
        assertEquals(LegacyCapabilityDiagnostic.SIGNING_FAILED, result.diagnostic)
        assertEquals(0, result.envelope.size)
    }

    @Test
    fun aSignerThatReturnsNoSignatureBytesDoesNotProduceAnAcceptedEnvelope() {
        // A signer that "succeeds" with an empty/short signature must not be waved through: the
        // envelope would carry a body with nothing authenticating it.
        val result = issueWithSigner(EmptySignatureSigner())

        assertNotEquals(LegacyCapabilityStatus.OK, result.status)
        assertEquals(0, result.envelope.size)
    }

    @Test
    fun theRequestIsRefusedOutrightOnceTheIssuerHasBeenClosed() {
        val executor = BoundedCapabilityExecutor()
        try {
            val issuer = issuer(executor, ThrowingSigner(RuntimeException("unused")))
            issuer.close()

            val result = awaitResult(issuer)

            // Shutdown must refuse rather than silently drop: a dropped one-shot request would
            // leave the client waiting on a callback that never arrives.
            assertEquals(LegacyCapabilityStatus.RATE_LIMITED, result.status)
            assertEquals(LegacyCapabilityDiagnostic.SIGNING_QUEUE_FULL, result.diagnostic)
            assertEquals(0, result.envelope.size)
        } finally {
            executor.close()
        }
    }

    @Test
    fun aRateLimitReleaseHappensEvenWhenSigningThrewSoTheSessionIsNotWedged() {
        val executor = BoundedCapabilityExecutor()
        try {
            val issuer = issuer(executor, ThrowingSigner(RuntimeException("transient")))

            // Same UID and audio session, twice. If the limiter slot were leaked by the throwing
            // path, the second request would come back RATE_LIMITED instead of reaching the
            // signer again — a single signing fault would permanently disable the session.
            assertEquals(LegacyCapabilityStatus.SIGNING_FAILED, awaitResult(issuer).status)
            assertEquals(
                LegacyCapabilityStatus.SIGNING_FAILED,
                awaitResult(issuer, nonce = ByteArray(16) { (it + 40).toByte() }).status,
            )
        } finally {
            executor.close()
        }
    }

    private fun issueWithSigner(signer: LegacyCapabilitySigner): LegacyCapabilityResult {
        val executor = BoundedCapabilityExecutor()
        return try {
            awaitResult(issuer(executor, signer))
        } finally {
            executor.close()
        }
    }

    private fun issuer(
        executor: BoundedCapabilityExecutor,
        signer: LegacyCapabilitySigner,
    ) = LegacyCapabilityIssuer(
        enabled = { true },
        policySource = { _, _ -> POLICY },
        signer = signer,
        executor = executor,
        boottimeMs = { 1_000L },
    )

    private fun awaitResult(
        issuer: LegacyCapabilityIssuer,
        nonce: ByteArray = ByteArray(16) { (it + 3).toByte() },
    ): LegacyCapabilityResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<LegacyCapabilityResult>()
        issuer.request(
            LegacyCapabilityRequest(
                uid = 10_000,
                packageName = PACKAGE,
                processName = PACKAGE,
                audioSessionId = 77,
                generation = GENERATION,
                nonce = nonce,
            ),
        ) {
            result.set(it)
            latch.countDown()
        }
        assertTrue("capability callback timed out", latch.await(3, TimeUnit.SECONDS))
        return result.get()
    }

    private class ThrowingSigner(private val failure: Throwable) : LegacyCapabilitySigner {
        override fun preparePublicKey(): ByteArray = throw failure

        override fun sign(body: ByteArray): ByteArray = throw failure
    }

    private class EmptySignatureSigner : LegacyCapabilitySigner {
        override fun preparePublicKey(): ByteArray = ByteArray(0)

        override fun sign(body: ByteArray): ByteArray = ByteArray(0)
    }

    private companion object {
        const val PACKAGE = "com.example.recorder"
        const val GENERATION = 9L
        val POLICY = LegacyCapabilityPolicy(
            GENERATION,
            PACKAGE,
            "{\"engine\":{},\"modules\":[]}".toByteArray(),
        )
    }
}
