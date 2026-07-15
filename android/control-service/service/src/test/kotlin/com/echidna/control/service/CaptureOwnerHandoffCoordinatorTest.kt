package com.echidna.control.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOwnerHandoffCoordinatorTest {
    @Test
    fun handoffTokensRemainPositiveAcrossTheSignedLongBoundary() {
        assertEquals(1L, nextCaptureHandoffToken(0L))
        assertEquals(2L, nextCaptureHandoffToken(1L))
        assertEquals(1L, nextCaptureHandoffToken(Long.MAX_VALUE))
        assertEquals(1L, nextCaptureHandoffToken(Long.MIN_VALUE))
    }

    @Test
    fun zygiskToLsposedDrainsBothRoutesBeforeExposure() {
        val harness = Harness()
        harness.establishZygisk(1L)

        harness.coordinator.publishPolicy(policy(2L, owner = "lsposed"))
        val nativeDrain = harness.native.lastPublication()
        assertEquals(CaptureHandoffPhase.WAIT_NATIVE_INACTIVE, harness.phase())
        assertNull(nativeDrain.owner(PROCESS))
        assertNull(harness.coordinator.lsposedPolicy(harness.lsposed, PACKAGE, PROCESS))
        assertFalse(harness.ackNative(1L, nativeDrain.token, active = false))
        assertFalse(harness.ackNative(2L, nativeDrain.token - 1L, active = false))

        assertTrue(harness.ackNative(2L, nativeDrain.token, active = false))
        assertEquals(CaptureHandoffPhase.WAIT_LSPOSED_INACTIVE, harness.phase())
        val lsDrain = harness.lsposed.lastRevocation()
        assertEquals(nativeDrain.token, lsDrain.token)
        assertTrue(harness.ackLsposed(2L, lsDrain.token))

        assertEquals(CaptureRouteOwner.LSPOSED, harness.owner())
        assertEquals(listOf(2L), harness.lsposed.changed.takeLast(1))
        assertEquals(
            "lsposed",
            JSONObject(harness.coordinator.lsposedPolicy(
                harness.lsposed,
                PACKAGE,
                PROCESS,
            )!!).getJSONObject("captureOwners").getString(PROCESS),
        )
    }

    @Test
    fun lsposedToZygiskRequiresLsDrainAndCurrentNativeActiveAck() {
        val harness = Harness()
        harness.establishLsposed(1L)

        harness.coordinator.publishPolicy(policy(2L, owner = "zygisk"))
        val nativeDrain = harness.native.lastPublication()
        assertTrue(harness.ackNative(2L, nativeDrain.token, active = false))
        val lsDrain = harness.lsposed.lastRevocation()
        assertTrue(harness.ackLsposed(2L, lsDrain.token))
        assertEquals(CaptureHandoffPhase.WAIT_NATIVE_ACTIVE, harness.phase())
        val activation = harness.native.lastPublication()
        assertEquals("zygisk", activation.owner(PROCESS))
        assertEquals(CaptureRouteOwner.NONE, harness.owner())

        assertFalse(harness.ackNative(2L, activation.token, active = false))
        assertTrue(harness.ackNative(2L, activation.token, active = true))
        assertEquals(CaptureRouteOwner.ZYGISK, harness.owner())
    }

    @Test
    fun rapidFlipCoalescesAndRequiresLatestGenerationEvidence() {
        val harness = Harness()
        harness.establishLsposed(1L)
        harness.coordinator.publishPolicy(policy(2L, owner = "zygisk"))
        val oldDrain = harness.native.lastPublication()
        harness.coordinator.publishPolicy(policy(3L, owner = "lsposed"))

        assertTrue(harness.ackNative(2L, oldDrain.token, active = false))
        val currentDrain = harness.native.lastPublication()
        assertEquals(3L, currentDrain.generation)
        assertTrue(currentDrain.token > oldDrain.token)
        assertFalse(harness.ackNative(3L, oldDrain.token, active = false))
        assertTrue(harness.ackNative(3L, currentDrain.token, active = false))
        val lsDrain = harness.lsposed.lastRevocation()
        assertTrue(harness.ackLsposed(3L, lsDrain.token))
        assertEquals(CaptureRouteOwner.LSPOSED, harness.owner())
    }

    @Test
    fun policyDisableWhileNativeActivationPendingExplicitlyDrainsNative() {
        val harness = Harness()
        harness.coordinator.publishPolicy(policy(1L, owner = "zygisk"))
        val firstDrain = harness.native.lastPublication()
        assertTrue(harness.ackNative(1L, firstDrain.token, active = false))
        assertTrue(harness.ackLsposed(1L, harness.lsposed.lastRevocation().token))
        val pendingActivation = harness.native.lastPublication()
        assertEquals(CaptureHandoffPhase.WAIT_NATIVE_ACTIVE, harness.phase())

        harness.coordinator.publishPolicy(policy(2L, owner = "zygisk", masterEnabled = false))
        val disabledDrain = harness.native.lastPublication()
        assertNull(disabledDrain.owner(PROCESS))
        assertTrue(disabledDrain.token > pendingActivation.token)
        assertFalse(harness.ackNative(1L, pendingActivation.token, active = true))
        assertTrue(harness.ackNative(2L, disabledDrain.token, active = false))
        assertTrue(harness.ackLsposed(2L, harness.lsposed.lastRevocation().token))
        assertEquals(CaptureHandoffPhase.INACTIVE, harness.phase())
        assertEquals(CaptureRouteOwner.NONE, harness.owner())
    }

    @Test
    fun duplicateEndpointsAreRejectedAndSameGenerationReconnectGetsFreshToken() {
        val harness = Harness()
        harness.establishZygisk(7L)
        assertFalse(harness.coordinator.registerNative(FakeNative(PROCESS)))
        assertFalse(harness.coordinator.registerLsposed(FakeLsposed(PROCESS)))

        val oldNative = harness.native
        val staleToken = oldNative.lastPublication().token
        harness.coordinator.unregisterNative(oldNative)
        assertEquals(CaptureHandoffPhase.FAILED, harness.phase())
        assertFalse(harness.coordinator.acknowledgeNative(
            oldNative,
            PROCESS,
            7L,
            staleToken,
            active = true,
        ))

        val replacement = FakeNative(PROCESS)
        assertTrue(harness.coordinator.registerNative(replacement))
        val replacementDrain = replacement.lastPublication()
        assertEquals(7L, replacementDrain.generation)
        assertTrue(replacementDrain.token > staleToken)
        assertTrue(harness.coordinator.acknowledgeNative(
            replacement,
            PROCESS,
            7L,
            replacementDrain.token,
            active = false,
        ))
        val lsDrain = harness.lsposed.lastRevocation()
        assertTrue(harness.coordinator.acknowledgeLsposedInactive(
            harness.lsposed,
            PROCESS,
            7L,
            lsDrain.token,
        ))
        val activation = replacement.lastPublication()
        assertTrue(harness.coordinator.acknowledgeNative(
            replacement,
            PROCESS,
            7L,
            activation.token,
            active = true,
        ))
        assertEquals(CaptureRouteOwner.ZYGISK, harness.owner())
    }

    @Test
    fun coldSingleTransportDenyAndLateOppositeRegistrationAreAuthoritative() {
        val nativeOnly = CaptureOwnerHandoffCoordinator(ManualScheduler(), 10L)
        val native = FakeNative(PROCESS)
        assertTrue(nativeOnly.registerNative(native))
        assertTrue(nativeOnly.publishPolicy(policy(1L, owner = "zygisk", whitelisted = false)))
        val deny = native.lastPublication()
        assertNull(deny.owner(PROCESS))
        assertFalse(JSONObject(deny.payload).getJSONObject("whitelist").getBoolean(PROCESS))
        assertTrue(nativeOnly.acknowledgeNative(
            native,
            PROCESS,
            1L,
            deny.token,
            active = false,
        ))
        assertEquals(CaptureHandoffPhase.INACTIVE, nativeOnly.phase(PROCESS))

        assertTrue(nativeOnly.publishPolicy(policy(2L, owner = "zygisk")))
        val drain = native.lastPublication()
        assertTrue(nativeOnly.acknowledgeNative(
            native,
            PROCESS,
            2L,
            drain.token,
            active = false,
        ))
        val activation = native.lastPublication()
        assertEquals("zygisk", activation.owner(PROCESS))
        assertTrue(nativeOnly.acknowledgeNative(
            native,
            PROCESS,
            2L,
            activation.token,
            active = true,
        ))

        val lateLsposed = FakeLsposed(PROCESS)
        assertTrue(nativeOnly.registerLsposed(lateLsposed))
        val lateNativeDrain = native.lastPublication()
        assertNull(lateNativeDrain.owner(PROCESS))
        assertEquals(CaptureRouteOwner.NONE, nativeOnly.effectiveOwner(PROCESS))
        assertTrue(nativeOnly.acknowledgeNative(
            native,
            PROCESS,
            2L,
            lateNativeDrain.token,
            active = false,
        ))
        assertTrue(nativeOnly.acknowledgeLsposedInactive(
            lateLsposed,
            PROCESS,
            2L,
            lateLsposed.lastRevocation().token,
        ))
        val reactivation = native.lastPublication()
        assertTrue(nativeOnly.acknowledgeNative(
            native,
            PROCESS,
            2L,
            reactivation.token,
            active = true,
        ))
        assertEquals(CaptureRouteOwner.ZYGISK, nativeOnly.effectiveOwner(PROCESS))

        val lsOnly = CaptureOwnerHandoffCoordinator(ManualScheduler(), 10L)
        val lsposed = FakeLsposed(PROCESS)
        assertTrue(lsOnly.registerLsposed(lsposed))
        assertTrue(lsOnly.publishPolicy(policy(1L, owner = "lsposed")))
        assertTrue(lsOnly.acknowledgeLsposedInactive(
            lsposed,
            PROCESS,
            1L,
            lsposed.lastRevocation().token,
        ))
        assertEquals(CaptureRouteOwner.LSPOSED, lsOnly.effectiveOwner(PROCESS))
    }

    @Test
    fun controlsAndPanicBoundaryMatchTransportAdmission() {
        val deniedPolicies = listOf(
            policy(1L, owner = "zygisk", masterEnabled = false),
            policy(2L, owner = "zygisk", bypass = true),
            policy(3L, owner = "zygisk", panicUntilEpochMs = 1_001L),
            policy(4L, owner = "zygisk", engineMode = "compatibility"),
            policy(5L, owner = "lsposed", engineMode = "native_first"),
        )
        deniedPolicies.forEach { denied ->
            val harness = Harness(nowEpochMs = { 1_000L })
            harness.coordinator.publishPolicy(denied)
            harness.completeInactive(denied.generation)
            assertEquals(CaptureRouteOwner.NONE, harness.owner())
        }

        val boundary = Harness(nowEpochMs = { 1_000L })
        boundary.coordinator.publishPolicy(policy(
            6L,
            owner = "zygisk",
            panicUntilEpochMs = 1_000L,
        ))
        boundary.completeNativeDrainAndLs(6L)
        val activation = boundary.native.lastPublication()
        assertTrue(boundary.ackNative(6L, activation.token, active = true))
        assertEquals(CaptureRouteOwner.ZYGISK, boundary.owner())
    }

    @Test
    fun timeoutRejectionDisconnectAndBlockedOtherProcessRemainIsolated() {
        val timeout = Harness(ManualScheduler())
        timeout.coordinator.publishPolicy(policy(1L, owner = "zygisk"))
        timeout.scheduler.runAll()
        assertEquals(CaptureHandoffPhase.FAILED, timeout.phase())
        assertTrue(timeout.native.closed)

        val rejected = Harness(ManualScheduler(reject = true))
        rejected.coordinator.publishPolicy(policy(1L, owner = "zygisk"))
        assertEquals(CaptureHandoffPhase.FAILED, rejected.phase())

        val coordinator = CaptureOwnerHandoffCoordinator(ManualScheduler(), 10L)
        coordinator.publishPolicy(policy(1L, owner = "zygisk", packageName = "com.blocked"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = BlockingNative("com.blocked", entered, release)
        val registration = Thread { coordinator.registerNative(blocked) }
        registration.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val healthy = FakeNative("com.healthy")
        assertTrue(coordinator.registerNative(healthy))
        assertEquals(CaptureHandoffPhase.WAIT_NATIVE_INACTIVE, coordinator.phase("com.healthy"))
        val healthyDrain = healthy.lastPublication()
        assertTrue(coordinator.acknowledgeNative(
            healthy,
            "com.healthy",
            1L,
            healthyDrain.token,
            active = false,
        ))
        assertEquals(CaptureHandoffPhase.INACTIVE, coordinator.phase("com.healthy"))
        release.countDown()
        registration.join(2_000L)
        assertFalse(registration.isAlive)
    }

    @Test
    fun exactOwnerOverridesBaseAndWrongProcessOrTransitionCannotAdvance() {
        val process = "$PACKAGE:worker"
        val harness = Harness(process = process)
        harness.coordinator.publishPolicy(policy(
            1L,
            owner = "zygisk",
            exactOwner = "lsposed",
            engineMode = "compatibility",
        ))
        val nativeDrain = harness.native.lastPublication()
        assertFalse(harness.coordinator.acknowledgeNative(
            harness.native,
            PACKAGE,
            1L,
            nativeDrain.token,
            active = false,
        ))
        assertTrue(harness.ackNative(1L, nativeDrain.token, active = false))
        val lsDrain = harness.lsposed.lastRevocation()
        assertTrue(harness.ackLsposed(1L, lsDrain.token))
        assertEquals(CaptureRouteOwner.LSPOSED, harness.coordinator.effectiveOwner(process))
    }

    @Test
    fun capabilityRequiresTheExactActiveLsposedEndpoint() {
        val harness = Harness()
        harness.establishLsposed(1L)

        val capability = harness.coordinator.capabilityPolicy(
            harness.lsposed,
            PACKAGE,
            PROCESS,
            10_000L,
        )
        assertEquals(1L, capability!!.generation)
        assertNull(harness.coordinator.capabilityPolicy(
            FakeLsposed(PROCESS),
            PACKAGE,
            PROCESS,
            10_000L,
        ))

        harness.coordinator.publishPolicy(policy(2L, owner = "zygisk"))
        assertNull(harness.coordinator.capabilityPolicy(
            harness.lsposed,
            PACKAGE,
            PROCESS,
            10_000L,
        ))
    }

    @Test
    fun closeRevokesAnActiveLsposedOwnerWithAFreshToken() {
        val harness = Harness()
        harness.establishLsposed(1L)
        val consumedToken = harness.lsposed.lastRevocation().token

        harness.coordinator.close()

        val shutdown = harness.lsposed.lastRevocation()
        assertEquals(1L, shutdown.generation)
        assertTrue(shutdown.token > consumedToken)
        assertTrue(harness.native.closed)
    }

    private class Harness(
        val scheduler: ManualScheduler = ManualScheduler(),
        process: String = PROCESS,
        nowEpochMs: () -> Long = { 10_000L },
    ) {
        val coordinator = CaptureOwnerHandoffCoordinator(scheduler, 10L, nowEpochMs)
        val native = FakeNative(process)
        val lsposed = FakeLsposed(process)

        init {
            assertTrue(coordinator.registerNative(native))
            assertTrue(coordinator.registerLsposed(lsposed))
        }

        fun phase() = coordinator.phase(native.processName)
        fun owner() = coordinator.effectiveOwner(native.processName)

        fun ackNative(generation: Long, token: Long, active: Boolean) =
            coordinator.acknowledgeNative(
                native,
                native.processName,
                generation,
                token,
                active,
            )

        fun ackLsposed(generation: Long, token: Long) =
            coordinator.acknowledgeLsposedInactive(
                lsposed,
                lsposed.processName,
                generation,
                token,
            )

        fun completeNativeDrainAndLs(generation: Long) {
            val nativeDrain = native.lastPublication()
            assertTrue(ackNative(generation, nativeDrain.token, active = false))
            assertTrue(ackLsposed(generation, lsposed.lastRevocation().token))
        }

        fun completeInactive(generation: Long) {
            completeNativeDrainAndLs(generation)
            assertEquals(CaptureHandoffPhase.INACTIVE, phase())
        }

        fun establishZygisk(generation: Long) {
            assertTrue(coordinator.publishPolicy(policy(generation, owner = "zygisk")))
            completeNativeDrainAndLs(generation)
            val activation = native.lastPublication()
            assertTrue(ackNative(generation, activation.token, active = true))
        }

        fun establishLsposed(generation: Long) {
            assertTrue(coordinator.publishPolicy(policy(generation, owner = "lsposed")))
            completeNativeDrainAndLs(generation)
            assertEquals(CaptureRouteOwner.LSPOSED, owner())
        }
    }

    private data class NativePublication(
        val payload: String,
        val token: Long,
    ) {
        val generation: Long get() = JSONObject(payload).getLong("generation")
        fun owner(processName: String): String? = JSONObject(payload)
            .getJSONObject("captureOwners")
            .optString(processName, null)
    }

    private open class FakeNative(override val processName: String) : NativeCaptureEndpoint {
        val publications = mutableListOf<NativePublication>()
        var closed = false
        var accept = true

        override fun publishPolicy(payload: String, handoffToken: Long): Boolean {
            if (!accept || closed) return false
            publications += NativePublication(payload, handoffToken)
            return true
        }

        override fun close() {
            closed = true
        }

        fun lastPublication(): NativePublication = publications.last()
    }

    private class BlockingNative(
        processName: String,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : FakeNative(processName) {
        override fun publishPolicy(payload: String, handoffToken: Long): Boolean {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            return super.publishPolicy(payload, handoffToken)
        }
    }

    private data class Revocation(val generation: Long, val token: Long)

    private class FakeLsposed(override val processName: String) : LsposedCaptureEndpoint {
        val revocations = mutableListOf<Revocation>()
        val changed = mutableListOf<Long>()
        var accept = true

        override fun revoke(generation: Long, handoffToken: Long): Boolean {
            if (!accept) return false
            revocations += Revocation(generation, handoffToken)
            return true
        }

        override fun policyChanged(generation: Long) {
            changed += generation
        }

        fun lastRevocation(): Revocation = revocations.last()
    }

    private class ManualScheduler(private val reject: Boolean = false) : CaptureHandoffScheduler {
        private data class Task(val block: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, task: () -> Unit): CaptureHandoffTask? {
            if (reject) return null
            val queued = Task(task)
            tasks += queued
            return CaptureHandoffTask { queued.cancelled = true }
        }

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.filterNot { it.cancelled }.forEach { it.block() }
        }
    }

    companion object {
        private const val PACKAGE = "com.example.recorder"
        private const val PROCESS = PACKAGE

        private fun policy(
            generation: Long,
            owner: String,
            exactOwner: String? = null,
            packageName: String = PACKAGE,
            whitelisted: Boolean = true,
            masterEnabled: Boolean = true,
            bypass: Boolean = false,
            panicUntilEpochMs: Long = 0L,
            engineMode: String = if (owner == "lsposed") "compatibility" else "native_first",
        ): VersionedPolicyEnvelope {
            val owners = linkedMapOf(packageName to owner)
            if (exactOwner != null) owners["$packageName:worker"] = exactOwner
            return VersionedPolicyEnvelope(
                generation,
                PolicyEnvelope(
                    profiles = linkedMapOf(
                        "default" to JSONObject("{\"engine\":{},\"modules\":[]}"),
                    ),
                    defaultProfileId = "default",
                    appBindings = linkedMapOf(),
                    whitelist = linkedMapOf(
                        packageName to whitelisted,
                        "$packageName:worker" to whitelisted,
                    ),
                    captureOwners = owners,
                    control = PolicyControl(
                        masterEnabled = masterEnabled,
                        bypass = bypass,
                        panicUntilEpochMs = panicUntilEpochMs,
                        sidetoneEnabled = false,
                        sidetoneGainDb = 0.0,
                        engineMode = engineMode,
                    ),
                ),
            )
        }
    }
}
