package com.echidna.app.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.echidna.control.service.IEchidnaControlService
import com.echidna.control.service.IEchidnaTelemetryListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ControlServiceClientTest {
    private val clients = mutableListOf<ControlServiceClient>()

    @After
    fun tearDown() {
        clients.forEach(ControlServiceClient::shutdown)
    }

    @Test
    fun `failed bind clears pending state and can be retried`() {
        val context = RecordingBindingContext().apply { acceptBinds = false }
        val client = client(context)

        assertFalse(client.bind())
        assertFalse(client.isBound())
        assertEquals(1, context.bindCalls.get())

        context.acceptBinds = true
        assertTrue(client.bind())
        assertEquals(2, context.bindCalls.get())
    }

    @Test
    fun `late service callback after unbind is ignored`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val service = RecordingControlService()

        assertTrue(client.bind())
        client.unbind()
        context.connectLast(service)

        Thread.sleep(100)
        assertFalse(client.isBound())
        assertEquals(0, service.registerCalls.get())
        assertTrue(service.policyStates.isEmpty())
    }

    @Test
    fun `cold start coalesces rapid state and duplicate callback does not replay`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val service = RecordingControlService()
        val callbackThread = Thread.currentThread().id

        client.synchronize(snapshot("preset-1", masterEnabled = true))
        client.synchronize(snapshot("preset-2", masterEnabled = false))
        client.synchronize(snapshot("preset-3", masterEnabled = true))
        assertTrue(client.bind())

        context.connectCurrent(service)
        assertTrue(await { service.policyStates.size == 1 })
        val applied = JSONObject(service.policyStates.single())
        assertTrue(applied.getJSONObject("profiles").has("preset-3"))
        assertTrue(applied.getJSONObject("control").getBoolean("masterEnabled"))
        assertNotEquals(callbackThread, service.syncThreadIds.single())

        context.connectCurrent(service)
        Thread.sleep(100)
        assertEquals("duplicate onServiceConnected must not replay", 1, service.policyStates.size)
    }

    @Test
    fun `disconnect queues only newest state and reconnect replays once`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val first = RecordingControlService()
        client.synchronize(snapshot("initial"))
        assertTrue(client.bind())
        context.connectCurrent(first)
        assertTrue(await { first.policyStates.singleOrNull()?.contains("initial") == true })

        context.disconnectCurrent()
        client.synchronize(snapshot("stale"))
        client.synchronize(snapshot("current"))
        val replacement = RecordingControlService()
        context.connectCurrent(replacement)

        assertTrue(await { replacement.policyStates.size == 1 })
        assertTrue(replacement.policyStates.single().contains("current"))
    }

    @Test
    fun `binder death during replay rebinds and preserves current state`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val dying = RecordingControlService(dieOnPolicySync = true)
        client.synchronize(snapshot("survivor"))
        assertTrue(client.bind())

        context.connectCurrent(dying)
        assertTrue(await { context.bindCalls.get() == 2 })

        val replacement = RecordingControlService()
        context.connectCurrent(replacement)
        assertTrue(await { replacement.policyStates.size == 1 })
        assertTrue(replacement.policyStates.single().contains("survivor"))
        assertEquals(1, context.unbindCalls.get())
    }

    @Test
    fun `legacy preprocessor load returns the service persisted state`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val service = RecordingControlService(initialLegacyPreprocessorEnabled = true)

        assertTrue(client.bind())
        context.connectCurrent(service)

        assertEquals(
            LegacyPreprocessorServiceResult.Success(enabled = true),
            client.readLegacyPreprocessorEnabled(),
        )
        assertEquals(1, service.legacyPreprocessorReadCalls.get())
    }

    @Test
    fun `legacy preprocessor is unavailable outside Android user zero without binder access`() {
        val context = RecordingBindingContext()
        val client = client(context, legacyPreprocessorSupported = { false })
        val service = RecordingControlService(initialLegacyPreprocessorEnabled = true)
        assertTrue(client.bind())
        context.connectCurrent(service)

        val expected = LegacyPreprocessorServiceResult.Failure(
            message = "Experimental capture attachment is available only in Android user 0.",
            confirmedEnabled = false,
            available = false,
        )
        assertEquals(expected, client.readLegacyPreprocessorEnabled())
        assertEquals(expected, client.updateLegacyPreprocessorEnabled(enabled = true))
        assertEquals(0, service.legacyPreprocessorReadCalls.get())
        assertEquals(0, service.legacyPreprocessorSetCalls.get())
    }

    @Test
    fun `legacy preprocessor update reports only read back confirmed state`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val service = RecordingControlService()

        assertTrue(client.bind())
        context.connectCurrent(service)

        assertEquals(
            LegacyPreprocessorServiceResult.Success(enabled = true),
            client.updateLegacyPreprocessorEnabled(enabled = true),
        )
        assertTrue(service.legacyPreprocessorEnabled)
        assertEquals(1, service.legacyPreprocessorSetCalls.get())
        assertEquals(1, service.legacyPreprocessorReadCalls.get())
    }

    @Test
    fun `legacy preprocessor rejected or mismatched update never returns optimistic state`() {
        val rejectedContext = RecordingBindingContext()
        val rejectedClient = client(rejectedContext)
        val rejectedService = RecordingControlService(acceptLegacyPreprocessorUpdates = false)
        assertTrue(rejectedClient.bind())
        rejectedContext.connectCurrent(rejectedService)

        assertEquals(
            LegacyPreprocessorServiceResult.Failure(
                message = "The control service rejected the attachment setting.",
                confirmedEnabled = false,
            ),
            rejectedClient.updateLegacyPreprocessorEnabled(enabled = true),
        )

        val mismatchContext = RecordingBindingContext()
        val mismatchClient = client(mismatchContext)
        val mismatchService = RecordingControlService(persistLegacyPreprocessorUpdates = false)
        assertTrue(mismatchClient.bind())
        mismatchContext.connectCurrent(mismatchService)

        assertEquals(
            LegacyPreprocessorServiceResult.Failure(
                message = "The control service did not persist the requested attachment setting.",
                confirmedEnabled = false,
            ),
            mismatchClient.updateLegacyPreprocessorEnabled(enabled = true),
        )
        assertFalse(mismatchService.legacyPreprocessorEnabled)
    }

    @Test
    fun `legacy preprocessor binder death rebinds and reloads replacement state`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val dying = RecordingControlService(dieOnLegacyPreprocessorRead = true)
        assertTrue(client.bind())
        context.connectCurrent(dying)

        assertTrue(
            client.readLegacyPreprocessorEnabled() is LegacyPreprocessorServiceResult.Failure,
        )
        assertTrue(await { context.bindCalls.get() == 2 })
        assertFalse(client.isBound())

        val replacement = RecordingControlService(initialLegacyPreprocessorEnabled = true)
        context.connectCurrent(replacement)

        assertTrue(client.isBound())
        assertEquals(
            LegacyPreprocessorServiceResult.Success(enabled = true),
            client.readLegacyPreprocessorEnabled(),
        )
        assertEquals(1, context.unbindCalls.get())
    }

    @Test
    fun `disconnected whitelist mutations coalesce and replay newest coherent state`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val first = RecordingControlService()
        client.synchronize(snapshot("policy", whitelistEnabled = true))
        assertTrue(client.bind())
        context.connectCurrent(first)
        assertTrue(await { first.policyStates.size == 1 })

        context.disconnectCurrent()
        client.synchronize(snapshot("policy", whitelistEnabled = false))
        client.synchronize(snapshot("policy", whitelistEnabled = true))
        val replacement = RecordingControlService()
        context.connectCurrent(replacement)

        assertTrue(await { replacement.policyStates.size == 1 })
        val replayed = JSONObject(replacement.policyStates.single())
        assertTrue(replayed.getJSONObject("whitelist").getBoolean("com.example.recorder"))
        assertEquals("policy", replayed.getJSONObject("appBindings").getString("com.example.recorder"))
    }

    private fun client(
        context: Context,
        legacyPreprocessorSupported: () -> Boolean = { true },
    ): ControlServiceClient =
        ControlServiceClient(context, legacyPreprocessorSupported).also(clients::add)

    private fun snapshot(
        presetId: String,
        masterEnabled: Boolean = true,
        whitelistEnabled: Boolean = true,
    ): ControlServiceSyncSnapshot = ControlServiceSyncSnapshot(
        policyStateJson = """{
            "schemaVersion":2,
            "profiles":{"$presetId":{"name":"$presetId","engine":{},"modules":[]}},
            "defaultProfileId":"$presetId",
            "appBindings":{"com.example.recorder":"$presetId"},
            "whitelist":{"com.example.recorder":$whitelistEnabled},
            "captureOwners":{"com.example.recorder":"zygisk"},
            "control":{
                "masterEnabled":$masterEnabled,
                "bypass":false,
                "panicUntilEpochMs":0,
                "sidetoneEnabled":false,
                "sidetoneGainDb":-24.0,
                "engineMode":"native_first"
            }
        }""".trimIndent(),
        telemetryOptIn = false,
    )

    private fun await(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

private class RecordingBindingContext : ContextWrapper(
    ApplicationProvider.getApplicationContext<Context>()
) {
    @Volatile var acceptBinds = true
    val bindCalls = AtomicInteger(0)
    val unbindCalls = AtomicInteger(0)
    @Volatile private var currentConnection: ServiceConnection? = null
    @Volatile private var lastConnection: ServiceConnection? = null

    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
        bindCalls.incrementAndGet()
        lastConnection = connection
        if (acceptBinds) {
            currentConnection = connection
            return true
        }
        return false
    }

    override fun unbindService(connection: ServiceConnection) {
        unbindCalls.incrementAndGet()
        if (currentConnection === connection) {
            currentConnection = null
        }
    }

    fun connectCurrent(service: IEchidnaControlService) {
        checkNotNull(currentConnection).onServiceConnected(COMPONENT, service.asBinder())
    }

    fun connectLast(service: IEchidnaControlService) {
        checkNotNull(lastConnection).onServiceConnected(COMPONENT, service.asBinder())
    }

    fun disconnectCurrent() {
        checkNotNull(currentConnection).onServiceDisconnected(COMPONENT)
    }

    companion object {
        private val COMPONENT = ComponentName("com.echidna.app", "EchidnaControlService")
    }
}

private class RecordingControlService(
    private val dieOnPolicySync: Boolean = false,
    initialLegacyPreprocessorEnabled: Boolean = false,
    private val acceptLegacyPreprocessorUpdates: Boolean = true,
    private val persistLegacyPreprocessorUpdates: Boolean = true,
    private val dieOnLegacyPreprocessorRead: Boolean = false,
    private val dieOnLegacyPreprocessorUpdate: Boolean = false,
) : IEchidnaControlService.Stub() {
    val registerCalls = AtomicInteger(0)
    val policyStates = CopyOnWriteArrayList<String>()
    val syncThreadIds = CopyOnWriteArrayList<Long>()
    val legacyPreprocessorReadCalls = AtomicInteger(0)
    val legacyPreprocessorSetCalls = AtomicInteger(0)
    @Volatile var legacyPreprocessorEnabled = initialLegacyPreprocessorEnabled

    override fun registerTelemetryListener(listener: IEchidnaTelemetryListener?) {
        registerCalls.incrementAndGet()
    }

    override fun pushProfileSnapshot(profileId: String?, profileJson: String?) {
        // Legacy binder path is intentionally unused by complete policy replay.
    }

    override fun synchronizePolicyState(stateJson: String?): Boolean {
        if (dieOnPolicySync) throw DeadObjectException()
        syncThreadIds += Thread.currentThread().id
        policyStates += stateJson.orEmpty()
        return true
    }

    override fun installModule(archivePath: String?) = Unit
    override fun uninstallModule() = Unit
    override fun refreshStatus(): String = "{}"
    override fun getModuleStatus(): String = "{}"
    override fun updateWhitelist(processName: String?, enabled: Boolean) = Unit
    override fun pushProfile(profileId: String?, profileJson: String?) = Unit
    override fun listProfiles(): Array<String> = emptyArray()
    override fun getWhitelistBindings(): String = "{}"
    override fun getTelemetrySnapshot(): String = "{}"
    override fun isTelemetryOptedIn(): Boolean = false
    override fun unregisterTelemetryListener(listener: IEchidnaTelemetryListener?) = Unit
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
    override fun isLegacyPreprocessorEnabled(): Boolean {
        legacyPreprocessorReadCalls.incrementAndGet()
        if (dieOnLegacyPreprocessorRead) throw DeadObjectException()
        return legacyPreprocessorEnabled
    }

    override fun setLegacyPreprocessorEnabled(enabled: Boolean): Boolean {
        legacyPreprocessorSetCalls.incrementAndGet()
        if (dieOnLegacyPreprocessorUpdate) throw DeadObjectException()
        if (acceptLegacyPreprocessorUpdates && persistLegacyPreprocessorUpdates) {
            legacyPreprocessorEnabled = enabled
        }
        return acceptLegacyPreprocessorUpdates
    }
}
