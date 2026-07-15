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
        assertTrue(service.profileIds.isEmpty())
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
        assertTrue(await { service.profileIds.size == 1 })
        assertEquals(listOf("preset-3"), service.profileIds)
        assertEquals(1, service.profileBindingStates.size)
        assertTrue(service.profileBindingStates.single().contains("preset-3"))
        assertEquals(listOf(true), service.masterValues)
        assertNotEquals(callbackThread, service.syncThreadIds.single())

        context.connectCurrent(service)
        Thread.sleep(100)
        assertEquals("duplicate onServiceConnected must not replay", 1, service.profileIds.size)
    }

    @Test
    fun `disconnect queues only newest state and reconnect replays once`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val first = RecordingControlService()
        client.synchronize(snapshot("initial"))
        assertTrue(client.bind())
        context.connectCurrent(first)
        assertTrue(await { first.profileIds == listOf("initial") })

        context.disconnectCurrent()
        client.synchronize(snapshot("stale"))
        client.synchronize(snapshot("current"))
        val replacement = RecordingControlService()
        context.connectCurrent(replacement)

        assertTrue(await { replacement.profileIds.size == 1 })
        assertEquals(listOf("current"), replacement.profileIds)
        assertEquals(1, replacement.profileBindingStates.size)
        assertTrue(replacement.profileBindingStates.single().contains("current"))
    }

    @Test
    fun `binder death during replay rebinds and preserves current state`() {
        val context = RecordingBindingContext()
        val client = client(context)
        val dying = RecordingControlService(dieOnProfilePush = true)
        client.synchronize(snapshot("survivor"))
        assertTrue(client.bind())

        context.connectCurrent(dying)
        assertTrue(await { context.bindCalls.get() == 2 })

        val replacement = RecordingControlService()
        context.connectCurrent(replacement)
        assertTrue(await { replacement.profileIds.size == 1 })
        assertEquals(listOf("survivor"), replacement.profileIds)
        assertEquals(1, replacement.profileBindingStates.size)
        assertTrue(replacement.profileBindingStates.single().contains("survivor"))
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
        assertTrue(await { first.profileBindingStates.size == 1 })

        context.disconnectCurrent()
        client.synchronize(snapshot("policy", whitelistEnabled = false))
        client.synchronize(snapshot("policy", whitelistEnabled = true))
        val replacement = RecordingControlService()
        context.connectCurrent(replacement)

        assertTrue(await { replacement.profileBindingStates.size == 1 })
        val replayed = JSONObject(replacement.profileBindingStates.single())
        assertTrue(replayed.getJSONObject("whitelist").getBoolean("com.example.recorder"))
        assertEquals("policy", replayed.getJSONObject("appBindings").getString("com.example.recorder"))
    }

    private fun client(context: Context): ControlServiceClient =
        ControlServiceClient(context).also(clients::add)

    private fun snapshot(
        presetId: String,
        masterEnabled: Boolean = true,
        whitelistEnabled: Boolean = true,
    ): ControlServiceSyncSnapshot = ControlServiceSyncSnapshot(
        profileId = presetId,
        profileJson = """{"name":"$presetId","engine":{},"modules":[]}""",
        profileBindingStateJson = """{
            "profiles":{"$presetId":{"name":"$presetId","engine":{},"modules":[]}},
            "appBindings":{"com.example.recorder":"$presetId"},
            "whitelist":{"com.example.recorder":$whitelistEnabled}
        }""".trimIndent(),
        masterEnabled = masterEnabled,
        bypass = false,
        sidetoneEnabled = false,
        sidetoneGainDb = -24f,
        engineMode = "native_first",
        latencyMode = "LL",
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
    private val dieOnProfilePush: Boolean = false,
) : IEchidnaControlService.Stub() {
    val registerCalls = AtomicInteger(0)
    val profileIds = CopyOnWriteArrayList<String>()
    val profileBindingStates = CopyOnWriteArrayList<String>()
    val masterValues = CopyOnWriteArrayList<Boolean>()
    val syncThreadIds = CopyOnWriteArrayList<Long>()

    override fun registerTelemetryListener(listener: IEchidnaTelemetryListener?) {
        registerCalls.incrementAndGet()
    }

    override fun pushProfileSnapshot(profileId: String?, profileJson: String?) {
        if (dieOnProfilePush) throw DeadObjectException()
        syncThreadIds += Thread.currentThread().id
        profileIds += profileId.orEmpty()
    }

    override fun synchronizeProfilesAndBindings(stateJson: String?) {
        profileBindingStates += stateJson.orEmpty()
    }

    override fun setMasterEnabled(enabled: Boolean) {
        masterValues += enabled
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
}
