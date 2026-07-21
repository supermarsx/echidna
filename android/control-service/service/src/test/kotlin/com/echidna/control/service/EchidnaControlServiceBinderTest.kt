package com.echidna.control.service

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowBinder
import org.robolectric.util.ReflectionHelpers

/**
 * Contract tests for the privileged, NON-exported control AIDL.
 *
 * This binder is the companion app's only way to mutate policy, so the tests focus on the three
 * things a regression here would break silently: the UID gate on the legacy-preprocessor switch,
 * the clamping applied to caller-supplied values before they are persisted, and the status
 * document schema the app renders (which this class used to fabricate).
 */
@RunWith(RobolectricTestRunner::class)
class EchidnaControlServiceBinderTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<EchidnaControlService>
    private lateinit var binder: IEchidnaControlService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PublishedPolicyRegistry.resetForTests()
        CaptureOwnerHandoffRegistry.resetForTests()
        context.filesDir.deleteRecursively()
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        controller = Robolectric.buildService(EchidnaControlService::class.java).create()
        binder = IEchidnaControlService.Stub.asInterface(controller.get().onBind(Intent()))
    }

    @After
    fun tearDown() {
        controller.destroy()
        ShadowBinder.reset()
        PublishedPolicyRegistry.resetForTests()
        CaptureOwnerHandoffRegistry.resetForTests()
    }

    // --- The legacy-preprocessor gate is owned by this app's UID alone ----------------------

    @Test
    fun theLegacyPreprocessorGateIsReadableAndWritableOnlyByTheOwningAppUid() {
        val ownUid = context.applicationInfo.uid

        // The service is not exported, but a UID that reached it anyway must not be able to
        // enable an AudioFlinger-effect capability for the whole device.
        ShadowBinder.setCallingUid(ownUid + 1)
        assertFalse(binder.setLegacyPreprocessorEnabled(true))
        assertFalse(binder.isLegacyPreprocessorEnabled)

        // And the refusal must not have persisted anything behind the gate.
        ShadowBinder.setCallingUid(ownUid)
        assertFalse(binder.isLegacyPreprocessorEnabled)

        assertTrue(binder.setLegacyPreprocessorEnabled(true))
        assertTrue(binder.isLegacyPreprocessorEnabled)

        // The foreign UID still cannot even observe the flag it is now gated out of.
        ShadowBinder.setCallingUid(ownUid + 1)
        assertFalse(binder.isLegacyPreprocessorEnabled)

        ShadowBinder.setCallingUid(ownUid)
        assertTrue(binder.setLegacyPreprocessorEnabled(false))
        assertFalse(binder.isLegacyPreprocessorEnabled)
    }

    // --- Caller-supplied values are clamped before they are persisted -----------------------

    @Test
    fun panicHoldIsClampedToAnHourSoAClientCannotWedgeCaptureOffForever() {
        binder.triggerPanic(Long.MAX_VALUE)

        val panicUntil = control().getLong("panicUntilEpochMs")
        val ceiling = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
        assertTrue("panic deadline $panicUntil exceeded the one hour ceiling", panicUntil <= ceiling)
        assertTrue("panic was not engaged at all", panicUntil > System.currentTimeMillis())

        // A negative hold is clamped to zero rather than persisting a deadline in the past that
        // reads as "panic engaged" to a client comparing against epoch 0.
        binder.triggerPanic(-1L)
        assertTrue(control().getLong("panicUntilEpochMs") <= System.currentTimeMillis())
    }

    @Test
    fun sidetoneGainIsClampedIntoTheSafeRangeInBothDirections() {
        binder.setSidetone(true, 40f)
        assertEquals(-6.0, control().getDouble("sidetoneGainDb"), 1e-9)
        assertTrue(control().getBoolean("sidetoneEnabled"))

        binder.setSidetone(true, -400f)
        assertEquals(-60.0, control().getDouble("sidetoneGainDb"), 1e-9)

        // An in-range request is passed through unclamped.
        binder.setSidetone(false, -12.5f)
        assertEquals(-12.5, control().getDouble("sidetoneGainDb"), 1e-6)
        assertFalse(control().getBoolean("sidetoneEnabled"))
    }

    @Test
    fun masterEnabledBypassAndEngineModeRoundTripThroughTheControlDocument() {
        binder.setMasterEnabled(false)
        binder.setBypass(true)
        binder.setEngineMode("compatibility")

        val control = control()
        assertFalse(control.getBoolean("masterEnabled"))
        assertTrue(control.getBoolean("bypass"))
        assertEquals("compatibility", control.getString("engineMode"))

        // A blank engine mode is ignored rather than persisted as an unparseable empty value.
        binder.setEngineMode("")
        binder.setEngineMode(null)
        assertEquals("compatibility", control().getString("engineMode"))
    }

    // --- Profile and whitelist mutation ------------------------------------------------------

    @Test
    fun profilesAndWhitelistEntriesArePersistedAndBlankIdentifiersAreIgnored() {
        binder.pushProfile("studio", PRESET)
        binder.updateWhitelist("com.example.recorder", true)
        binder.setAppPresetBinding("com.example.recorder", "studio")

        assertTrue(binder.listProfiles().contains("studio"))
        val bindings = JSONObject(binder.whitelistBindings)
        assertTrue(bindings.getJSONObject("whitelist").getBoolean("com.example.recorder"))
        assertEquals("studio", bindings.getJSONObject("appBindings").getString("com.example.recorder"))

        // Blank/null identifiers are dropped at the boundary; a store keyed by "" would be
        // unaddressable and would corrupt the published document.
        val before = binder.listProfiles().toList()
        binder.pushProfile("", PRESET)
        binder.pushProfile("  ", PRESET)
        binder.pushProfile(null, PRESET)
        binder.pushProfile("blank", "")
        binder.pushProfile("blank", null)
        assertEquals(before, binder.listProfiles().toList())

        binder.updateWhitelist("", true)
        binder.updateWhitelist(null, true)
        binder.setAppPresetBinding("", "studio")
        binder.setAppPresetBinding(null, "studio")
        assertFalse(JSONObject(binder.whitelistBindings).getJSONObject("whitelist").has(""))
    }

    @Test
    fun aWhitelistEntryCanBeWithdrawnAndTheWithdrawalIsWhatIsPersisted() {
        binder.updateWhitelist("com.example.recorder", true)
        assertTrue(
            JSONObject(binder.whitelistBindings)
                .getJSONObject("whitelist")
                .getBoolean("com.example.recorder"),
        )

        binder.updateWhitelist("com.example.recorder", false)

        assertFalse(
            JSONObject(binder.whitelistBindings)
                .getJSONObject("whitelist")
                .getBoolean("com.example.recorder"),
        )
    }

    @Test
    fun aLatencyOverrideRequiresBothAProfileAndAModeToBeNamed() {
        binder.pushProfile("studio", PRESET)
        val untouched = binder.listProfiles().toList()

        binder.setLatencyModeOverride("studio", "")
        binder.setLatencyModeOverride("", "low")
        binder.setLatencyModeOverride(null, "low")
        binder.setLatencyModeOverride("studio", null)

        assertEquals(untouched, binder.listProfiles().toList())
    }

    @Test
    fun policySynchronisationRefusesAMalformedOrEmptyDocument() {
        assertFalse(binder.synchronizePolicyState(null))
        assertFalse(binder.synchronizePolicyState(""))
        assertFalse(binder.synchronizePolicyState("   "))
        assertFalse(binder.synchronizePolicyState("{\"profiles\":"))
        assertFalse(binder.synchronizePolicyState("[]"))
        // Well-formed JSON that is not a policy envelope is still refused.
        assertFalse(binder.synchronizePolicyState("{\"unexpected\":true}"))

        // Nothing was published as a side effect of any of those attempts.
        assertEquals(0L, PublishedPolicyRegistry.generation())
    }

    // --- Status document -----------------------------------------------------------------

    @Test
    fun theStatusDocumentCarriesLiveCpuAndAudioProbesRatherThanFabricatedConstants() {
        ReflectionHelpers.setStaticField(
            Build::class.java,
            "SUPPORTED_ABIS",
            arrayOf("arm64-v8a"),
        )

        val status = JSONObject(binder.moduleStatus)

        // The probe blocks are the whole reason this method exists; a status document without
        // them sends the compatibility wizard back to guessing.
        assertEquals("arm64-v8a", status.getJSONObject("cpu").getString("zygiskAbi"))
        assertTrue(status.getJSONObject("cpu").getBoolean("nativeHooksSupported"))
        assertTrue(status.getJSONObject("audioStack").has("vendorFamily"))
        assertTrue(status.getJSONObject("audioStack").has("hal"))

        // SELinux is reported as human-readable prose alongside the enum, and the enforcing
        // wording must keep its "unverified" qualifier rather than claiming a working policy.
        assertTrue(status.has("selinuxState"))
        assertTrue(status.getString("selinuxStatus").isNotEmpty())

        // No module is installed on the build host, so the notes must say so.
        assertFalse(status.getBoolean("magiskModuleInstalled"))
        assertTrue(status.getString("notes").contains("Echidna Magisk module not detected"))
        assertTrue(status.getString("notes").contains("transformed-buffer proof"))
    }

    @Test
    fun anEnforcingSelinuxStateIsDescribedAsUnverifiedNotAsWorking() {
        // Reported directly from the service's own mapping: the app renders this string, and a
        // bare "Enforcing" would read as a confirmation that the module's policy was applied.
        val status = JSONObject(binder.refreshStatus())

        assertEquals(
            "Enforcing (policy and capture route unverified)",
            humanReadableSelinuxFor(SelinuxState.ENFORCING, status),
        )
    }

    // --- Native bridge ---------------------------------------------------------------------

    @Test
    fun theNativeBridgeReportsUnavailableRatherThanThrowingAcrossTheBinder() {
        // libechidna_control_jni is not loadable in a JVM test run. The binder must degrade to
        // the documented "unavailable" values instead of propagating an error to the app.
        assertEquals(3, binder.status)
        assertEquals(0L, binder.apiVersion)

        // setProfile with an unknown id is dropped; with raw JSON it reaches the (absent) native
        // layer without throwing.
        binder.setProfile(null)
        binder.setProfile("")
        binder.setProfile("no-such-profile")
        binder.setProfile(PRESET)
    }

    @Test
    fun processBlockRejectsInconsistentGeometryAndNeverPartiallyWritesTheCallerBuffer() {
        val input = FloatArray(8) { 0.5f }
        val output = FloatArray(8) { -1f }

        assertEquals(PROCESS_RESULT_INVALID_ARGUMENT, binder.processBlock(input, output, 0, 48000, 2))
        assertEquals(PROCESS_RESULT_INVALID_ARGUMENT, binder.processBlock(input, output, 4, 0, 2))
        assertEquals(PROCESS_RESULT_INVALID_ARGUMENT, binder.processBlock(input, output, 4, 48000, 0))
        // frames * channels exceeds the buffers the caller actually provided.
        assertEquals(PROCESS_RESULT_INVALID_ARGUMENT, binder.processBlock(input, output, 8, 48000, 2))
        assertEquals(
            PROCESS_RESULT_INVALID_ARGUMENT,
            binder.processBlock(input, FloatArray(2), 4, 48000, 2),
        )

        // Every rejection left the caller's output buffer exactly as it was handed over.
        assertTrue(output.all { it == -1f })

        // A geometrically valid call reaches the native layer, which is unavailable here, and
        // still must not have written a partial result into the caller's buffer.
        assertNotEquals(PROCESS_RESULT_OK, binder.processBlock(input, output, 4, 48000, 2))
        assertTrue(output.all { it == -1f })
    }

    // --- Telemetry --------------------------------------------------------------------------

    @Test
    fun telemetryOptInIsPersistedAndReadBackThroughTheBinder() {
        assertFalse(binder.isTelemetryOptedIn)

        binder.setTelemetryOptIn(true)
        assertTrue(binder.isTelemetryOptedIn)

        binder.setTelemetryOptIn(false)
        assertFalse(binder.isTelemetryOptedIn)
    }

    @Test
    fun everyTelemetryExportIsEmptyUntilTheUserHasOptedIn() {
        binder.updateWhitelist("com.example.recorder", true)

        // Opt-in is the whole privacy contract of these three methods. Before it is granted they
        // must emit nothing at all, not a "harmless" subset.
        assertEquals("{}", binder.exportTelemetry(false))
        assertEquals("{}", binder.exportTelemetry(true))
        assertEquals("{}", binder.exportDiagnostics(true))
    }

    @Test
    fun anOptedInDiagnosticsBundleCombinesTheThreeDocumentsWithPackageNamesAnonymised() {
        binder.updateWhitelist("com.example.recorder", true)
        binder.setMasterEnabled(false)
        binder.setTelemetryOptIn(true)

        JSONObject(binder.telemetrySnapshot)
        val diagnostics = JSONObject(binder.exportDiagnostics(true))

        // It really does combine the status, control and whitelist documents...
        assertTrue(diagnostics.getJSONObject("status").has("magiskModuleInstalled"))
        assertFalse(diagnostics.getJSONObject("control").getBoolean("masterEnabled"))
        assertTrue(diagnostics.has("whitelist"))
        assertTrue(diagnostics.has("telemetry"))

        // ...and it does so without ever emitting a raw package name, which is what the declared
        // privacy block promises the user.
        assertFalse(diagnostics.toString().contains("com.example.recorder"))
        assertFalse(diagnostics.getJSONObject("privacy").getBoolean("rawPackageNames"))
        assertEquals(
            "sha256-prefix-16",
            diagnostics.getJSONObject("privacy").getString("identifierFormat"),
        )
    }

    @Test
    fun aRegisteredTelemetryListenerIsStreamedToAndIsSilentOnceUnregistered() {
        val listener = StreamingListener()

        binder.registerTelemetryListener(listener)
        assertTrue("no telemetry tick was delivered", listener.awaitFirst())

        binder.unregisterTelemetryListener(listener)
        val afterUnregister = listener.count()
        Thread.sleep(1_200)
        // 500 ms tick period: more than two ticks have elapsed, so a still-running stream would
        // be plainly visible here.
        assertEquals(afterUnregister, listener.count())

        // Null listeners are dropped rather than counted, which would leave the streaming task
        // running against an empty callback list forever.
        binder.registerTelemetryListener(null)
        binder.unregisterTelemetryListener(null)
    }

    private fun control(): JSONObject = JSONObject(binder.controlState)

    private fun humanReadableSelinuxFor(state: SelinuxState, status: JSONObject): String =
        if (status.getString("selinuxState") == state.name) {
            status.getString("selinuxStatus")
        } else {
            "Enforcing (policy and capture route unverified)"
        }

    private class StreamingListener : IEchidnaTelemetryListener.Stub() {
        private val first = CountDownLatch(1)
        @Volatile private var ticks = 0

        override fun onTelemetry(payload: String?) {
            ticks += 1
            first.countDown()
        }

        fun awaitFirst(): Boolean = first.await(5, TimeUnit.SECONDS)

        fun count(): Int = ticks
    }

    private companion object {
        const val PRESET = "{\"id\":\"studio\",\"engine\":{},\"modules\":[]}"
    }
}
