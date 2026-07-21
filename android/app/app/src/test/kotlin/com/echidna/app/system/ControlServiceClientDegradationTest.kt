package com.echidna.app.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import com.echidna.control.service.IEchidnaControlService
import com.echidna.control.service.IEchidnaTelemetryListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fail-closed behaviour of [ControlServiceClient]: what every call does when the privileged control
 * service is absent, dead, or throwing.
 *
 * The client is the app's only channel to privileged state, and the whole design is fail-closed —
 * an unbound or misbehaving service must degrade to "unknown"/"no" rather than propagating a
 * RemoteException into the UI or fabricating a success the user would act on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ControlServiceClientDegradationTest {
    private val clients = mutableListOf<ControlServiceClient>()

    @After
    fun tearDown() {
        clients.forEach(ControlServiceClient::shutdown)
    }

    // --- Never bound --------------------------------------------------------------------------

    @Test
    fun `every read degrades to a null or empty answer while nothing is bound`() {
        val client = client(DegradingContext())

        assertNull(client.fetchSnapshot())
        assertNull(client.exportTelemetry(includeTrends = true))
        assertNull(client.exportDiagnostics(includeTrends = false))
        assertNull(client.getModuleStatus())
        assertNull(client.refreshStatus())
        assertNull(client.getWhitelistBindings())
        assertNull(client.getControlState())
        assertEquals(emptyList<String>(), client.listProfiles())
        assertFalse("an unread opt-in must default to opted OUT", client.isTelemetryOptedIn())
    }

    @Test
    fun `every write is dropped without throwing while nothing is bound`() {
        val client = client(DegradingContext())

        // A crash here would take down whichever UI action invoked it; dropping is the contract.
        client.pushProfile("id", "{}")
        client.setProfile("{}")
        client.setTelemetryOptIn(true)
        client.updateWhitelist("com.example.app", true)
        client.pushProfileSnapshot("id", "{}")
        client.setLatencyModeOverride("id", "LL")
        client.setAppPresetBinding("com.example.app", "id")
        client.setMasterEnabled(false)
        client.setBypass(true)
        client.triggerPanic(1_000L)
        client.setSidetone(true, -12f)
        client.setEngineMode("compatibility")

        assertFalse(client.isBound())
    }

    @Test
    fun `the legacy preprocessor gate reports a disconnected service rather than a value`() {
        val client = client(DegradingContext())

        val read = client.readLegacyPreprocessorEnabled()
        val write = client.updateLegacyPreprocessorEnabled(enabled = true)

        assertEquals(
            LegacyPreprocessorServiceResult.Failure("Control service is not connected."),
            read,
        )
        assertEquals(
            "a write with no service must never report the requested value back as confirmed",
            LegacyPreprocessorServiceResult.Failure("Control service is not connected."),
            write,
        )
    }

    // --- Bound, but the service throws ------------------------------------------------------------

    @Test
    fun `a throwing service degrades every read instead of surfacing the RemoteException`() {
        val context = DegradingContext()
        val client = client(context)
        assertTrue(client.bind())
        context.connect(ThrowingControlService())

        assertNull(client.fetchSnapshot())
        assertNull(client.exportTelemetry(includeTrends = true))
        assertNull(client.exportDiagnostics(includeTrends = true))
        assertNull(client.getModuleStatus())
        assertNull(client.refreshStatus())
        assertNull(client.getWhitelistBindings())
        assertNull(client.getControlState())
        assertEquals(emptyList<String>(), client.listProfiles())
        assertFalse(client.isTelemetryOptedIn())
        assertFalse("a failed disable must not read as disabled", client.disableModule())
        assertFalse("a failed reboot request must not read as dispatched", client.rebootDevice())
    }

    @Test
    fun `a throwing service swallows every write so a control action cannot crash the caller`() {
        val context = DegradingContext()
        val client = client(context)
        assertTrue(client.bind())
        context.connect(ThrowingControlService())

        client.pushProfile("id", "{}")
        client.setProfile("{}")
        client.setTelemetryOptIn(true)
        client.updateWhitelist("com.example.app", true)
        client.pushProfileSnapshot("id", "{}")
        client.setLatencyModeOverride("id", "LL")
        client.setAppPresetBinding("com.example.app", "id")
        client.setMasterEnabled(false)
        client.setBypass(true)
        client.triggerPanic(1_000L)
        client.setSidetone(true, -12f)
        client.setEngineMode("compatibility")
        client.installModule("/tmp/x.zip")
        client.uninstallModule()

        assertTrue("a throwing RemoteException must not tear down the binding", client.isBound())
    }

    // --- Connection lifecycle ---------------------------------------------------------------------

    @Test
    fun `a null binding releases the binding and does not silently retry`() {
        val context = DegradingContext()
        val client = client(context)
        assertTrue(client.bind())
        val bindsBefore = context.bindCalls.get()

        context.nullBindCurrent()

        assertFalse(client.isBound())
        assertEquals("a null binding is terminal, not a retry loop", bindsBefore, context.bindCalls.get())
        assertEquals(1, context.unbindCalls.get())
    }

    @Test
    fun `a died binding is released and rebound automatically`() {
        val context = DegradingContext()
        val client = client(context)
        assertTrue(client.bind())

        context.bindingDiedCurrent()

        assertFalse(client.isBound())
        assertEquals("binding death must trigger a fresh bind attempt", 2, context.bindCalls.get())
        assertEquals(1, context.unbindCalls.get())
    }

    @Test
    fun `a connection carrying a null binder is rejected rather than treated as connected`() {
        val context = DegradingContext()
        val client = client(context)
        assertTrue(client.bind())

        context.connectNullBinder()

        assertFalse(client.isBound())
        assertFalse(client.connectionState.value)
    }

    @Test
    fun `unbind unregisters the telemetry listener exactly once and reports disconnected`() {
        val context = DegradingContext()
        val client = client(context)
        val service = CountingControlService()
        assertTrue(client.bind())
        context.connect(service)
        assertTrue(await { service.registerCalls.get() == 1 })

        client.unbind()

        assertFalse(client.isBound())
        assertFalse(client.connectionState.value)
        assertEquals(1, service.unregisterCalls.get())

        // A second unbind must not double-release the binding.
        client.unbind()
        assertEquals(1, context.unbindCalls.get())
        assertEquals(1, service.unregisterCalls.get())
    }

    @Test
    fun `reads after a disconnect degrade even though the service object still exists`() {
        val context = DegradingContext()
        val client = client(context)
        val service = CountingControlService()
        assertTrue(client.bind())
        context.connect(service)
        assertEquals("{\"telemetry\":true}", client.fetchSnapshot())

        context.disconnectCurrent()

        assertFalse(client.isBound())
        assertNull("a disconnected client must not keep serving the dead binder", client.fetchSnapshot())
        assertNull(client.getControlState())
    }

    @Test
    fun `state queued after a disconnect is not pushed until a new connection arrives`() {
        val context = DegradingContext()
        val client = client(context)
        val service = CountingControlService()
        client.synchronize(ControlServiceSyncSnapshot("""{"generation":1}""", telemetryOptIn = false))
        assertTrue(client.bind())
        context.connect(service)
        assertTrue(await { service.policyStates.size == 1 })
        context.disconnectCurrent()

        client.synchronize(ControlServiceSyncSnapshot("""{"generation":2}""", telemetryOptIn = true))

        Thread.sleep(100)
        assertEquals("no state may reach a disconnected service", 1, service.policyStates.size)
        assertTrue(service.policyStates.single().contains("\"generation\":1"))
    }

    // --- Telemetry fan-out ---------------------------------------------------------------------------

    @Test
    fun `telemetry pushed by the service reaches subscribers and null payloads are ignored`() {
        val context = DegradingContext()
        val client = client(context)
        val service = CountingControlService()
        assertTrue(client.bind())
        context.connect(service)
        assertTrue(await { service.listener != null })

        service.listener?.onTelemetry(null)
        service.listener?.onTelemetry("""{"totalCallbacks":7}""")

        val received = runBlocking {
            withTimeout(3_000L) { client.telemetryUpdates.first() }
        }
        assertEquals(
            "a null payload must not be forwarded as an empty snapshot",
            """{"totalCallbacks":7}""",
            received,
        )
    }

    @Test
    fun `a listener registration failure still leaves the client connected and syncing`() {
        val context = DegradingContext()
        val client = client(context)
        val service = CountingControlService(failListenerRegistration = true)
        client.synchronize(ControlServiceSyncSnapshot(policyStateJson = null, telemetryOptIn = true))
        assertTrue(client.bind())

        context.connect(service)

        assertTrue(await { service.optInWrites.get() == 1 })
        assertTrue("losing telemetry must not cost us the control channel", client.isBound())
    }

    @Test
    fun `a bind that Android refuses outright leaves the client unbound instead of throwing`() {
        val context = DegradingContext().apply { bindThrows = true }
        val client = client(context)

        assertFalse("a rejected bind must be reported, not raised", client.bind())
        assertFalse(client.isBound())
        assertFalse(client.connectionState.value)

        // The failed attempt must not consume the one-outstanding-bind token.
        context.bindThrows = false
        assertTrue(client.bind())
    }

    @Test
    fun `unbinding a binding Android already dropped does not throw`() {
        val context = DegradingContext().apply { unbindThrows = true }
        val client = client(context)
        assertTrue(client.bind())
        context.connect(CountingControlService())

        client.unbind()

        assertFalse(client.isBound())
    }

    @Test
    fun `a rejected policy is not recorded as applied and is retried on reconnect`() {
        val context = DegradingContext()
        val client = client(context)
        val rejecting = CountingControlService(rejectPolicy = true)
        client.synchronize(ControlServiceSyncSnapshot("""{"generation":9}""", telemetryOptIn = false))
        assertTrue(client.bind())

        context.connect(rejecting)
        assertTrue(await { rejecting.policyStates.size == 1 })
        assertEquals(
            "a rejected policy must not be followed by the opt-in write of the same batch",
            0,
            rejecting.optInWrites.get(),
        )

        // Reconnecting must replay the same state rather than treating it as already delivered.
        val accepting = CountingControlService()
        context.connect(accepting)

        assertTrue(await { accepting.policyStates.size == 1 })
        assertTrue(accepting.policyStates.single().contains("\"generation\":9"))
    }

    @Test
    fun `a runtime failure in the attachment gate keeps a live binding instead of rebinding`() {
        val context = DegradingContext()
        val client = client(context)
        assertTrue(client.bind())
        context.connect(CountingControlService(legacyGateThrowsRuntime = true))

        val read = client.readLegacyPreprocessorEnabled()
        val write = client.updateLegacyPreprocessorEnabled(enabled = true)

        assertTrue(read is LegacyPreprocessorServiceResult.Failure)
        assertTrue(write is LegacyPreprocessorServiceResult.Failure)
        assertEquals(
            "a live binder must not be torn down over a service-side error",
            1,
            context.bindCalls.get(),
        )
        assertTrue(client.isBound())
    }

    private fun client(context: Context): ControlServiceClient =
        ControlServiceClient(context) { true }.also(clients::add)

    private fun await(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

/** Context that hands the test direct control over every [ServiceConnection] callback. */
private class DegradingContext : ContextWrapper(
    ApplicationProvider.getApplicationContext<Context>()
) {
    val bindCalls = AtomicInteger(0)
    val unbindCalls = AtomicInteger(0)
    @Volatile var bindThrows = false
    @Volatile var unbindThrows = false
    @Volatile private var connection: ServiceConnection? = null

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        bindCalls.incrementAndGet()
        if (bindThrows) throw SecurityException("not permitted to bind")
        connection = conn
        return true
    }

    override fun unbindService(conn: ServiceConnection) {
        unbindCalls.incrementAndGet()
        if (unbindThrows) throw IllegalArgumentException("service not registered")
    }

    fun connect(service: IEchidnaControlService) =
        checkNotNull(connection).onServiceConnected(COMPONENT, service.asBinder())

    fun connectNullBinder() = checkNotNull(connection).onServiceConnected(COMPONENT, null)

    fun disconnectCurrent() = checkNotNull(connection).onServiceDisconnected(COMPONENT)

    fun nullBindCurrent() = checkNotNull(connection).onNullBinding(COMPONENT)

    fun bindingDiedCurrent() = checkNotNull(connection).onBindingDied(COMPONENT)

    companion object {
        private val COMPONENT = ComponentName("com.echidna.app", "EchidnaControlService")
    }
}

/** A live service that fails every binder call with [RemoteException]. */
private class ThrowingControlService : BaseControlService() {
    override fun getTelemetrySnapshot(): String = throw RemoteException("boom")
    override fun exportTelemetry(includeTrends: Boolean): String = throw RemoteException("boom")
    override fun exportDiagnostics(includeTrends: Boolean): String = throw RemoteException("boom")
    override fun getModuleStatus(): String = throw RemoteException("boom")
    override fun refreshStatus(): String = throw RemoteException("boom")
    override fun getWhitelistBindings(): String = throw RemoteException("boom")
    override fun getControlState(): String = throw RemoteException("boom")
    override fun listProfiles(): Array<String> = throw RemoteException("boom")
    override fun isTelemetryOptedIn(): Boolean = throw RemoteException("boom")
    override fun disableModule(): Boolean = throw RemoteException("boom")
    override fun rebootDevice(): Boolean = throw RemoteException("boom")
    override fun pushProfile(profileId: String?, profileJson: String?) = throw RemoteException("boom")
    override fun setProfile(profile: String?) = throw RemoteException("boom")
    override fun setTelemetryOptIn(enabled: Boolean) = throw RemoteException("boom")
    override fun updateWhitelist(processName: String?, enabled: Boolean) = throw RemoteException("boom")
    override fun pushProfileSnapshot(profileId: String?, profileJson: String?) = throw RemoteException("boom")
    override fun setLatencyModeOverride(profileId: String?, latencyMode: String?) = throw RemoteException("boom")
    override fun setAppPresetBinding(packageName: String?, presetId: String?) = throw RemoteException("boom")
    override fun setMasterEnabled(enabled: Boolean) = throw RemoteException("boom")
    override fun setBypass(bypass: Boolean) = throw RemoteException("boom")
    override fun triggerPanic(holdMs: Long) = throw RemoteException("boom")
    override fun setSidetone(enabled: Boolean, gainDb: Float) = throw RemoteException("boom")
    override fun setEngineMode(engineMode: String?) = throw RemoteException("boom")
    override fun installModule(archivePath: String?) = throw RemoteException("boom")
    override fun uninstallModule() = throw RemoteException("boom")
}

/** A cooperative service that records what the client did to it. */
private class CountingControlService(
    private val failListenerRegistration: Boolean = false,
    private val rejectPolicy: Boolean = false,
    private val legacyGateThrowsRuntime: Boolean = false,
) : BaseControlService() {
    val registerCalls = AtomicInteger(0)
    val unregisterCalls = AtomicInteger(0)
    val optInWrites = AtomicInteger(0)
    val policyStates = CopyOnWriteArrayList<String>()
    @Volatile var listener: IEchidnaTelemetryListener? = null

    override fun registerTelemetryListener(listener: IEchidnaTelemetryListener?) {
        registerCalls.incrementAndGet()
        if (failListenerRegistration) throw RemoteException("no listeners")
        this.listener = listener
    }

    override fun unregisterTelemetryListener(listener: IEchidnaTelemetryListener?) {
        unregisterCalls.incrementAndGet()
        this.listener = null
    }

    override fun setTelemetryOptIn(enabled: Boolean) {
        optInWrites.incrementAndGet()
    }

    override fun synchronizePolicyState(stateJson: String?): Boolean {
        policyStates += stateJson.orEmpty()
        return !rejectPolicy
    }

    override fun getTelemetrySnapshot(): String = "{\"telemetry\":true}"

    override fun isLegacyPreprocessorEnabled(): Boolean =
        if (legacyGateThrowsRuntime) throw IllegalStateException("gate unavailable") else false

    override fun setLegacyPreprocessorEnabled(enabled: Boolean): Boolean =
        if (legacyGateThrowsRuntime) throw IllegalStateException("gate unavailable") else true
}

/** Inert defaults so each fake only overrides the behaviour its test is about. */
private abstract class BaseControlService : IEchidnaControlService.Stub() {
    override fun registerTelemetryListener(listener: IEchidnaTelemetryListener?) = Unit
    override fun unregisterTelemetryListener(listener: IEchidnaTelemetryListener?) = Unit
    override fun synchronizePolicyState(stateJson: String?): Boolean = true
    override fun pushProfileSnapshot(profileId: String?, profileJson: String?) = Unit
    override fun pushProfile(profileId: String?, profileJson: String?) = Unit
    override fun installModule(archivePath: String?) = Unit
    override fun uninstallModule() = Unit
    override fun disableModule(): Boolean = true
    override fun rebootDevice(): Boolean = true
    override fun refreshStatus(): String = "{}"
    override fun getModuleStatus(): String = "{}"
    override fun updateWhitelist(processName: String?, enabled: Boolean) = Unit
    override fun listProfiles(): Array<String> = emptyArray()
    override fun getWhitelistBindings(): String = "{}"
    override fun getTelemetrySnapshot(): String = "{}"
    override fun isTelemetryOptedIn(): Boolean = false
    override fun setTelemetryOptIn(enabled: Boolean) = Unit
    override fun exportTelemetry(includeTrends: Boolean): String = "{}"
    override fun exportDiagnostics(includeTrends: Boolean): String = "{}"
    override fun setProfile(profile: String?) = Unit
    override fun setLatencyModeOverride(profileId: String?, latencyMode: String?) = Unit
    override fun setAppPresetBinding(packageName: String?, presetId: String?) = Unit
    override fun setMasterEnabled(enabled: Boolean) = Unit
    override fun setBypass(bypass: Boolean) = Unit
    override fun triggerPanic(holdMs: Long) = Unit
    override fun setSidetone(enabled: Boolean, gainDb: Float) = Unit
    override fun setEngineMode(engineMode: String?) = Unit
    override fun getControlState(): String = "{}"
    override fun getStatus(): Int = 0
    override fun processBlock(
        input: FloatArray?,
        output: FloatArray?,
        frames: Int,
        sampleRate: Int,
        channelCount: Int,
    ): Int = 0
    override fun getApiVersion(): Long = 0L
    override fun isLegacyPreprocessorEnabled(): Boolean = false
    override fun setLegacyPreprocessorEnabled(enabled: Boolean): Boolean = true
}
