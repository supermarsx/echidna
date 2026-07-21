package com.echidna.control.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * Contract tests for the ONE exported Binder surface in this module.
 *
 * Everything here is written from the attacker's side of the boundary: a target process reaches
 * this endpoint with a UID it cannot forge and a process name it can. The tests therefore pin
 * refusal as hard as they pin success, and the "authorized" cases assert the exact scoped
 * document the caller receives rather than merely that a document came back.
 *
 * SDK 27 is used so [AndroidPublishedAppIdentityResolver] takes its GET_SIGNATURES branch, which
 * is the branch Robolectric's package manager can furnish real signing material for. The module's
 * minSdk is 26, so this is a configuration the service genuinely ships on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class PolicySnapshotServiceBinderTest {
    private lateinit var context: Context
    private var controller: ServiceController<PolicySnapshotService>? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PublishedPolicyRegistry.resetForTests()
        CaptureOwnerHandoffRegistry.resetForTests()
        installPackage(RECORDER, RECORDER_UID)
        installPackage(OTHER, OTHER_UID)
        ShadowBinder.setCallingUid(RECORDER_UID)
        ShadowBinder.setCallingPid(CALLER_PID)
        persistedPolicyFile().parentFile?.deleteRecursively()
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        ShadowBinder.reset()
        PublishedPolicyRegistry.resetForTests()
        CaptureOwnerHandoffRegistry.resetForTests()
    }

    // --- Caller authorisation -------------------------------------------------------------

    @Test
    fun anUnauthorisedUidIsRefusedEveryPolicyAndCapabilitySurface() {
        publish(policyJson(generation = 5L))
        val binder = startService()

        // The claimed process is real and whitelisted; only the caller UID is wrong. Nothing
        // about the request is malformed, so a service that authorised on the claim alone would
        // hand this caller the recorder's policy.
        ShadowBinder.setCallingUid(RECORDER_UID + 876)

        assertNull(binder.getPolicySnapshot(RECORDER))
        assertFalse(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener()),
        )
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 5L, 1L))
        assertFalse(
            binder.reportLegacyPreprocessorTelemetryV7(
                7,
                RECORDER,
                5L,
                ByteArray(PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES),
                ByteArray(PREPROCESSOR_TELEMETRY_VALUE_BYTES),
            ),
        )
        assertFalse(
            binder.reportLegacyPreprocessorTelemetryProofV7(
                7,
                RECORDER,
                5L,
                ByteArray(PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES),
            ),
        )

        val callback = RecordingCapabilityCallback()
        assertFalse(
            binder.requestLegacyPreprocessorCapabilityV7(7, RECORDER, 5L, ByteArray(16), callback),
        )
        val denial = callback.await()
        assertEquals(LegacyCapabilityStatus.DENIED, denial.status)
        assertEquals(LegacyCapabilityDiagnostic.CALLER_UNAUTHORIZED, denial.diagnostic)
        assertEquals(0, denial.envelope.size)
    }

    @Test
    fun aCallerCannotClaimAProcessBelongingToAnotherPublishedPackage() {
        publish(policyJson(generation = 5L))
        val binder = startService()

        // com.example.other is published, whitelisted and owned by lsposed — but by UID 10124.
        // The recorder's UID must not be able to speak for it.
        assertNull(binder.getPolicySnapshot(OTHER))
        assertFalse(
            binder.registerCaptureOwnerClient(OTHER, CAPABILITY_PROVIDER_API_VERSION, listener()),
        )

        // A subprocess of a package this UID does own is fine, but only if it is in the policy.
        assertNull(binder.getPolicySnapshot("$RECORDER:unpublished"))
        assertNull(binder.getPolicySnapshot("com.not.installed"))
        assertNull(binder.getPolicySnapshot(null))
    }

    @Test
    fun everyPolicySurfaceFailsClosedWhileNoPolicyHasBeenPublished() {
        // Cold service, empty registry: this is the state on every boot before the app pushes.
        val binder = startService()
        assertEquals(0L, PublishedPolicyRegistry.generation())

        assertNull(binder.getPolicySnapshot(RECORDER))
        assertFalse(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener()),
        )
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 1L, 1L))

        val callback = RecordingCapabilityCallback()
        assertFalse(
            binder.requestLegacyPreprocessorCapabilityV7(7, RECORDER, 1L, ByteArray(16), callback),
        )
        assertEquals(LegacyCapabilityDiagnostic.CALLER_UNAUTHORIZED, callback.await().diagnostic)
    }

    @Test
    fun registrationIsRefusedForAnUnsupportedClientApiVersion() {
        publish(policyJson(generation = 5L))
        val binder = startService()

        // Version skew is refused rather than served a document the client may misparse.
        assertEquals(CAPABILITY_PROVIDER_API_VERSION, binder.apiVersion)
        assertFalse(
            binder.registerCaptureOwnerClient(
                RECORDER,
                CAPABILITY_PROVIDER_API_VERSION - 1,
                listener(),
            ),
        )
        assertFalse(
            binder.registerCaptureOwnerClient(
                RECORDER,
                CAPABILITY_PROVIDER_API_VERSION + 1,
                listener(),
            ),
        )
        assertFalse(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, null),
        )
    }

    // --- Policy exposure ------------------------------------------------------------------

    @Test
    fun anActiveOwnerReceivesOnlyItsOwnExactAndBaseScopedView() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        val listener = becomeCaptureOwner(binder, generation = 5L)

        val snapshot = JSONObject(binder.getPolicySnapshot(RECORDER)!!)

        // The document describes this process and nothing else. A leak of com.example.other's
        // entries would be a cross-app policy disclosure across the exported boundary.
        assertEquals(5L, snapshot.getLong("generation"))
        assertTrue(snapshot.getJSONObject("whitelist").getBoolean(RECORDER))
        assertFalse(snapshot.getJSONObject("whitelist").has(OTHER))
        assertFalse(snapshot.getJSONObject("profiles").has("other"))
        assertEquals("lsposed", snapshot.getJSONObject("captureOwners").getString(RECORDER))
        // Published identities are registry-internal and must never cross the Binder.
        assertFalse(snapshot.has("appIdentities"))
        // The owner is invalidated for its own generation and never told about any other one.
        assertEquals(setOf(5L), listener.awaitChange(5L).let { listener.changes().toSet() })
    }

    @Test
    fun policyIsWithheldUntilTheOwnerHasAcknowledgedTheDrainForThatGeneration() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        val listener = RecordingListener()

        assertTrue(
            binder.registerCaptureOwnerClient(
                RECORDER,
                CAPABILITY_PROVIDER_API_VERSION,
                listener,
            ),
        )
        // Registered, but mid-handoff: the route is not yet this client's, so no activating
        // document may be exposed.
        assertNull(binder.getPolicySnapshot(RECORDER))

        val revocation = listener.lastRevocation()
        assertEquals(5L, revocation.generation)
        // A guessed or replayed handoff token cannot complete the drain.
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 5L, revocation.token + 1))
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 4L, revocation.token))
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 0L, revocation.token))
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 5L, 0L))
        assertNull(binder.getPolicySnapshot(RECORDER))

        assertTrue(binder.reportCaptureOwnerInactiveV7(RECORDER, 5L, revocation.token))
        assertNotNull(binder.getPolicySnapshot(RECORDER))
        // The acknowledgement is single-use; a replay must not re-advance the state machine.
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 5L, revocation.token))
    }

    @Test
    fun aSecondRegistrationForTheSameProcessAndPidIsRefused() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)

        // A second endpoint claiming the same process incarnation would give two live owners.
        assertFalse(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener()),
        )
    }

    @Test
    fun unregisteringTheOwnerImmediatelyWithdrawsItsPolicyAccess() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        val listener = becomeCaptureOwner(binder, generation = 5L)
        assertNotNull(binder.getPolicySnapshot(RECORDER))

        binder.unregisterListener(listener)

        assertNull(binder.getPolicySnapshot(RECORDER))
        assertFalse(binder.reportCaptureOwnerInactiveV7(RECORDER, 5L, 1L))
        // Unregistering an unknown/null listener is a no-op rather than a crash on the boundary.
        binder.unregisterListener(null)
        binder.unregisterListener(RecordingListener())
    }

    // --- Publication and generation monotonicity -------------------------------------------

    @Test
    fun generationNeverGoesBackwardsAndTheSameGenerationCannotBeRewritten() {
        publish(policyJson(generation = 9L))
        startService()

        assertFalse(PublishedPolicyRegistry.publish(policyJson(generation = 8L)))
        assertEquals(9L, PublishedPolicyRegistry.generation())

        // Same generation, same bytes is idempotent...
        assertTrue(PublishedPolicyRegistry.publish(policyJson(generation = 9L)))
        // ...but the same generation carrying DIFFERENT policy is a conflict, not an update.
        assertFalse(
            PublishedPolicyRegistry.publish(policyJson(generation = 9L, masterEnabled = false)),
        )
        assertEquals(9L, PublishedPolicyRegistry.generation())

        assertTrue(PublishedPolicyRegistry.publish(policyJson(generation = 10L)))
        assertEquals(10L, PublishedPolicyRegistry.generation())
    }

    @Test
    fun aRegisteredListenerIsInvalidatedWithTheNewGenerationWhenPolicyIsRepublished() {
        publish(policyJson(generation = 9L))
        val binder = startService()
        val listener = RecordingListener()

        binder.registerListener(RECORDER, listener)
        // Registration itself delivers the current generation so a late client is never stale.
        assertEquals(9L, listener.awaitChange(9L))

        assertTrue(PublishedPolicyRegistry.publish(policyJson(generation = 11L)))

        assertEquals(11L, listener.awaitChange(11L))
    }

    @Test
    fun listenerRegistrationIsBoundedPerUidAndRejectsDuplicates() {
        publish(policyJson(generation = 9L))
        val binder = startService()

        val accepted = (0 until MAX_PER_UID).map { RecordingListener() }
        accepted.forEach { binder.registerListener(RECORDER, it) }
        accepted.forEach { assertEquals(9L, it.awaitChange(9L)) }

        // The per-UID cap is a denial-of-service bound on the exported endpoint: one app must
        // not be able to fill the global callback list.
        val overflow = RecordingListener()
        binder.registerListener(RECORDER, overflow)
        assertTrue(overflow.changes().isEmpty())

        // Re-registering an already registered binder is refused (it would double-notify).
        binder.registerListener(RECORDER, accepted[0])
        assertEquals(listOf(9L), accepted[0].changes())

        // Freeing a slot lets a new listener in, proving the cap is a live count not a latch.
        binder.unregisterListener(accepted[0])
        val replacement = RecordingListener()
        binder.registerListener(RECORDER, replacement)
        assertEquals(9L, replacement.awaitChange(9L))
    }

    @Test
    fun listenerRegistrationRefusesAProcessTheCallingUidDoesNotOwn() {
        publish(policyJson(generation = 9L))
        val binder = startService()

        val impostor = RecordingListener()
        binder.registerListener(OTHER, impostor)
        binder.registerListener(null, impostor)
        binder.registerListener("$RECORDER/../escape", impostor)
        binder.registerListener(RECORDER, null)

        assertTrue(impostor.changes().isEmpty())
    }

    // --- Cold start -----------------------------------------------------------------------

    @Test
    fun coldStartRepublishesThePersistedPolicyWithIdentitiesRevalidatedAgainstTheDevice() {
        writePersisted(policyJson(generation = 21L))

        startService()

        assertEquals(21L, awaitGeneration(21L))
        val reloaded = PublishedPolicyRegistry.current()!!
        // Identities are re-resolved from the live package manager on reload, so a package that
        // was uninstalled or re-signed while the service was down cannot be trusted from disk.
        assertEquals(
            setOf(RECORDER, OTHER),
            reloaded.envelope.appIdentities.keys.toSet(),
        )
        assertEquals(RECORDER_UID, reloaded.envelope.appIdentities[RECORDER]!!.uid)
    }

    @Test
    fun coldStartDropsAPersistedIdentityThatNoLongerMatchesTheInstalledPackage() {
        writePersisted(policyJson(generation = 21L))
        // The recorder was re-signed (or replaced) while the service was down.
        installPackage(RECORDER, RECORDER_UID, signatureHex = "ddeeff")

        startService()

        assertEquals(21L, awaitGeneration(21L))
        val reloaded = PublishedPolicyRegistry.current()!!
        assertFalse(reloaded.envelope.appIdentities.containsKey(RECORDER))
        assertTrue(reloaded.envelope.appIdentities.containsKey(OTHER))

        // And with no bound identity the process can no longer be authorised at all.
        val binder = binderOf()
        assertNull(binder.getPolicySnapshot(RECORDER))
        assertFalse(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener()),
        )
    }

    @Test
    fun coldStartPublishesNothingWhenThePersistedPayloadIsUnusable() {
        // Truncated / attacker-tampered / half-written state must leave the service with no
        // policy at all rather than with a partially parsed one.
        writePersisted("{\"generation\":21,\"profiles\":")

        startService()

        assertFalse(awaitSettled())
        assertEquals(0L, PublishedPolicyRegistry.generation())
        assertNull(PublishedPolicyRegistry.current())
        assertNull(binderOf().getPolicySnapshot(RECORDER))
    }

    @Test
    fun coldStartPublishesNothingWhenThePersistedPolicyBindsNoIdentities() {
        // Structurally valid, but carries no appIdentities: nothing in it can be authorised, and
        // publishing it would expose an unbound policy document.
        val unbound = JSONObject(policyJson(generation = 21L)).apply { remove("appIdentities") }
        writePersisted(unbound.toString())

        startService()

        assertFalse(awaitSettled())
        assertEquals(0L, PublishedPolicyRegistry.generation())
    }

    @Test
    fun coldStartIgnoresAnOversizedPersistedFileWithoutReadingIt() {
        val padded = JSONObject(policyJson(generation = 21L))
        padded.getJSONObject("profiles")
            .getJSONObject("default")
            .put("pad", "x".repeat(MAX_POLICY_ENVELOPE_BYTES + 1024))
        writePersisted(padded.toString())

        startService()

        assertFalse(awaitSettled())
        assertEquals(0L, PublishedPolicyRegistry.generation())
    }

    @Test
    fun coldStartLeavesAlreadyLivePolicyAloneRatherThanRewindingToDisk() {
        // Disk holds an older generation than the one already published in this process.
        writePersisted(policyJson(generation = 4L))
        publish(policyJson(generation = 30L))

        startService()

        assertFalse(awaitSettled())
        assertEquals(30L, PublishedPolicyRegistry.generation())
    }

    // --- Capability and telemetry surfaces --------------------------------------------------

    @Test
    fun anAuthorisedOwnerIsStillRefusedACapabilityWhileTheFeatureGateIsOff() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)

        val callback = RecordingCapabilityCallback()
        // Fully authorised, fully owning the route — and still refused, because the legacy
        // preprocessor gate defaults off. Authorisation alone must never issue a capability.
        assertTrue(
            binder.requestLegacyPreprocessorCapabilityV7(7, RECORDER, 5L, ByteArray(16), callback),
        )

        val result = callback.await()
        assertEquals(LegacyCapabilityStatus.DENIED, result.status)
        assertEquals(LegacyCapabilityDiagnostic.FEATURE_DISABLED, result.diagnostic)
        assertEquals(0, result.envelope.size)
    }

    @Test
    fun theCapabilitySurfaceRejectsAMissingCallbackWithoutTouchingTheIssuer() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)

        assertFalse(binder.requestLegacyPreprocessorCapabilityV7(7, RECORDER, 5L, ByteArray(16), null))
    }

    @Test
    fun telemetryIsRejectedUnlessEveryFixedWidthFieldAndTheGenerationMatch() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)

        val nonce = ByteArray(PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES)
        val snapshot = ByteArray(PREPROCESSOR_TELEMETRY_VALUE_BYTES)

        // Fixed-width payloads: a short or long buffer is a protocol violation, not a resize.
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 5L, ByteArray(15), snapshot))
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 5L, ByteArray(17), snapshot))
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 5L, null, snapshot))
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 5L, nonce, ByteArray(47)))
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 5L, nonce, null))
        // Generation is the capability incarnation: evidence for another incarnation is refused.
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 4L, nonce, snapshot))
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 6L, nonce, snapshot))
        assertFalse(binder.reportLegacyPreprocessorTelemetryV7(7, null, 5L, nonce, snapshot))

        // The well-formed report for the live generation is accepted for relay.
        assertTrue(binder.reportLegacyPreprocessorTelemetryV7(7, RECORDER, 5L, nonce, snapshot))
    }

    @Test
    fun proofIngestionEnforcesTheExactProofWidthAndLiveGeneration() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)

        val proof = ByteArray(PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES)
        assertFalse(binder.reportLegacyPreprocessorTelemetryProofV7(7, RECORDER, 5L, null))
        assertFalse(
            binder.reportLegacyPreprocessorTelemetryProofV7(
                7,
                RECORDER,
                5L,
                ByteArray(PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES - 1),
            ),
        )
        assertFalse(binder.reportLegacyPreprocessorTelemetryProofV7(7, RECORDER, 4L, proof))
        assertTrue(binder.reportLegacyPreprocessorTelemetryProofV7(7, RECORDER, 5L, proof))
    }

    @Test
    fun theRetiredV3TelemetryTransactionIngestsNothingAtAll() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)
        val before = AuthenticatedTelemetryRegistry.store.snapshot(5L)

        // v3 is retained purely for APK/shim version skew. It carries no incarnation binding,
        // so it must remain a no-op rather than a second, weaker ingestion path.
        binder.reportLegacyPreprocessorTelemetry(
            7,
            RECORDER,
            5L,
            ByteArray(PREPROCESSOR_TELEMETRY_VALUE_BYTES),
        )

        assertEquals(before.toString(), AuthenticatedTelemetryRegistry.store.snapshot(5L).toString())
    }

    @Test
    fun theOnewayV6DrainReportIsAuthorisedIdenticallyToItsV7Replacement() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        val listener = RecordingListener()
        assertTrue(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener),
        )
        val token = listener.lastRevocation().token

        // The oneway variant returns nothing, so its refusal must be observable in the fact that
        // the route stays closed.
        ShadowBinder.setCallingUid(RECORDER_UID + 876)
        binder.reportCaptureOwnerInactive(RECORDER, 5L, token)
        ShadowBinder.setCallingUid(RECORDER_UID)
        assertNull(binder.getPolicySnapshot(RECORDER))

        binder.reportCaptureOwnerInactive(RECORDER, 5L, token)
        assertNotNull(binder.getPolicySnapshot(RECORDER))
    }

    @Test
    fun theOnewayV2CapabilityRequestDeniesAnUnauthorisedCallerThroughTheCallback() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        ShadowBinder.setCallingUid(RECORDER_UID + 876)

        val callback = RecordingCapabilityCallback()
        binder.requestLegacyPreprocessorCapability(7, RECORDER, 5L, ByteArray(16), callback)

        val denial = callback.await()
        assertEquals(LegacyCapabilityStatus.DENIED, denial.status)
        assertEquals(LegacyCapabilityDiagnostic.CALLER_UNAUTHORIZED, denial.diagnostic)

        // A null callback has nowhere to report to and must be dropped, not dereferenced.
        binder.requestLegacyPreprocessorCapability(7, RECORDER, 5L, ByteArray(16), null)
    }

    @Test
    fun theOnewayV4AndV5TelemetrySurfacesEnforceTheSameWidthAndOwnershipRules() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)
        val before = AuthenticatedTelemetryRegistry.store.snapshot(5L).toString()

        // Wrong widths and a stale generation on the oneway surfaces: nothing may be ingested.
        binder.reportLegacyPreprocessorTelemetryV4(7, RECORDER, 5L, ByteArray(15), ByteArray(48))
        binder.reportLegacyPreprocessorTelemetryV4(7, RECORDER, 4L, ByteArray(16), ByteArray(48))
        binder.reportLegacyPreprocessorTelemetryV4(7, null, 5L, ByteArray(16), ByteArray(48))
        binder.reportLegacyPreprocessorTelemetryProofV5(7, RECORDER, 5L, ByteArray(111))
        binder.reportLegacyPreprocessorTelemetryProofV5(7, RECORDER, 4L, ByteArray(112))
        binder.reportLegacyPreprocessorTelemetryProofV5(7, null, 5L, ByteArray(112))

        assertEquals(before, AuthenticatedTelemetryRegistry.store.snapshot(5L).toString())
    }

    @Test
    fun destroyingTheServiceRevokesTheLiveOwnerAndStopsServingPolicy() {
        publish(policyJson(generation = 5L))
        val binder = startService()
        becomeCaptureOwner(binder, generation = 5L)
        assertNotNull(binder.getPolicySnapshot(RECORDER))

        controller!!.destroy()
        controller = null

        // Teardown must withdraw the route rather than leave a hooked process holding policy.
        assertNull(binder.getPolicySnapshot(RECORDER))
        assertFalse(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener()),
        )
    }

    // --- Harness ---------------------------------------------------------------------------

    private fun startService(): IEchidnaPolicyProvider {
        controller = Robolectric.buildService(PolicySnapshotService::class.java).create()
        return binderOf()
    }

    private fun binderOf(): IEchidnaPolicyProvider =
        IEchidnaPolicyProvider.Stub.asInterface(controller!!.get().onBind(Intent()))

    /** Drives a caller all the way to being the acknowledged, active LSPosed capture owner. */
    private fun becomeCaptureOwner(
        binder: IEchidnaPolicyProvider,
        generation: Long,
    ): RecordingListener {
        val listener = RecordingListener()
        assertTrue(
            binder.registerCaptureOwnerClient(RECORDER, CAPABILITY_PROVIDER_API_VERSION, listener),
        )
        assertTrue(
            binder.reportCaptureOwnerInactiveV7(
                RECORDER,
                generation,
                listener.lastRevocation().token,
            ),
        )
        return listener
    }

    private fun listener() = RecordingListener()

    private fun publish(payload: String) {
        assertTrue(PublishedPolicyRegistry.publish(payload))
    }

    private fun persistedPolicyFile(): File = File(File(context.filesDir, "profiles"), "profiles.json")

    private fun writePersisted(payload: String) {
        val file = persistedPolicyFile()
        file.parentFile!!.mkdirs()
        writeProfileStoreAtomic(file, payload)
    }

    /** The reload runs on the service's own executor; poll rather than assume a scheduling order. */
    private fun awaitGeneration(expected: Long): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (PublishedPolicyRegistry.generation() == expected) return expected
            Thread.sleep(5)
        }
        return PublishedPolicyRegistry.generation()
    }

    /** True if any publication happened within the settling window. */
    private fun awaitSettled(): Boolean {
        val before = PublishedPolicyRegistry.generation()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400)
        while (System.nanoTime() < deadline) {
            if (PublishedPolicyRegistry.generation() != before) return true
            Thread.sleep(5)
        }
        return false
    }

    private fun installPackage(
        packageName: String,
        uid: Int,
        signatureHex: String = SIGNATURE_HEX,
    ) {
        val info = PackageInfo()
        info.packageName = packageName
        @Suppress("DEPRECATION")
        info.signatures = arrayOf(Signature(signatureHex))
        info.applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.uid = uid
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(uid, packageName)
    }

    private fun policyJson(
        generation: Long,
        masterEnabled: Boolean = true,
    ): String = JSONObject()
        .put("schemaVersion", POLICY_SCHEMA_VERSION)
        .put("generation", generation)
        .put(
            "profiles",
            JSONObject()
                .put("default", preset("default"))
                .put("other", preset("other")),
        )
        .put("defaultProfileId", "default")
        .put("appBindings", JSONObject().put(RECORDER, "default").put(OTHER, "other"))
        .put("whitelist", JSONObject().put(RECORDER, true).put(OTHER, true))
        .put("captureOwners", JSONObject().put(RECORDER, "lsposed").put(OTHER, "lsposed"))
        .put(
            "control",
            JSONObject()
                .put("masterEnabled", masterEnabled)
                .put("bypass", false)
                .put("panicUntilEpochMs", 0L)
                .put("sidetoneEnabled", false)
                .put("sidetoneGainDb", 0.0)
                .put("engineMode", "compatibility"),
        )
        .put(
            "appIdentities",
            JSONObject()
                .put(RECORDER, identityJson(RECORDER, RECORDER_UID))
                .put(OTHER, identityJson(OTHER, OTHER_UID)),
        )
        .toString()

    private fun identityJson(packageName: String, uid: Int): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("uid", uid)
        .put("userId", androidUserId(uid))
        .put("signingSha256", JSONArray().put(SIGNATURE_DIGEST))

    private fun preset(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("engine", JSONObject())
        .put("modules", JSONArray())

    private data class Revocation(val generation: Long, val token: Long)

    private class RecordingListener : IEchidnaPolicyListener.Stub() {
        private val lock = Any()
        private val changed = mutableListOf<Long>()
        private val revoked = mutableListOf<Revocation>()
        @Volatile private var latch = CountDownLatch(1)

        override fun onPolicyChanged(generation: Long) {
            synchronized(lock) { changed += generation }
            latch.countDown()
        }

        override fun onCaptureOwnerRevoked(generation: Long, handoffToken: Long) {
            synchronized(lock) { revoked += Revocation(generation, handoffToken) }
        }

        fun changes(): List<Long> = synchronized(lock) { changed.toList() }

        fun lastRevocation(): Revocation = synchronized(lock) { revoked.last() }

        fun awaitChange(expected: Long): Long {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (changes().contains(expected)) return expected
                Thread.sleep(5)
            }
            return changes().lastOrNull() ?: -1L
        }
    }

    private data class CapabilityResult(
        val status: Int,
        val generation: Long,
        val envelope: ByteArray,
        val diagnostic: String,
    )

    private class RecordingCapabilityCallback : IEchidnaCapabilityCallback.Stub() {
        private val latch = CountDownLatch(1)
        @Volatile private var result: CapabilityResult? = null

        override fun onCapabilityResult(
            status: Int,
            generation: Long,
            envelope: ByteArray?,
            diagnostic: String?,
        ) {
            result = CapabilityResult(status, generation, envelope ?: ByteArray(0), diagnostic ?: "")
            latch.countDown()
        }

        fun await(): CapabilityResult {
            assertTrue("no capability result was delivered", latch.await(5, TimeUnit.SECONDS))
            return result!!
        }
    }

    private companion object {
        const val RECORDER = "com.example.recorder"
        const val OTHER = "com.example.other"
        const val RECORDER_UID = 10_123
        const val OTHER_UID = 10_124
        const val CALLER_PID = 4_321
        const val MAX_PER_UID = 4
        const val SIGNATURE_HEX = "aabbcc"

        val SIGNATURE_DIGEST: String = MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }
}
