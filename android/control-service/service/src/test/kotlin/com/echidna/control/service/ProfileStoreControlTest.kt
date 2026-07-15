package com.echidna.control.service

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val PRESET_JSON = """{
    "name": "Test",
    "engine": {"latencyMode": "LL", "blockMs": 10},
    "modules": [ {"id":"mix","wet":50,"outGain":0.0} ]
}"""

/**
 * Extends [ProfileStoreTest] coverage to the snapshot fields added for the unified
 * control plane (t2-e6): the global `control` object, per-app `appBindings`, and the
 * fail-closed whitelist default. Asserts what the LSPosed shim + native engine read off
 * the ProfileSyncBridge snapshot.
 */
class ProfileStoreControlTest {
    private lateinit var tempDir: File
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var syncBridge: CountingSyncBridge
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("profiles-control").toFile()
        executor = Executors.newSingleThreadExecutor()
        syncBridge = CountingSyncBridge()
        store = ProfileStore(tempDir, syncBridge, executor)
    }

    @After
    fun tearDown() {
        store.close()
        store.awaitClosed()
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    /** Returns the most recent snapshot the store pushed, waiting for the next push. */
    private fun awaitNextSnapshot(): JSONObject {
        assertTrue("expected a snapshot push", syncBridge.awaitPush())
        return JSONObject(syncBridge.lastPayload()!!)
    }

    @Test
    fun `snapshot always carries the four top-level sections`() {
        store.saveProfile("p1", PRESET_JSON)
        val snapshot = awaitNextSnapshot()
        assertTrue(snapshot.has("profiles"))
        assertTrue(snapshot.has("whitelist"))
        assertTrue(snapshot.has("appBindings"))
        assertTrue(snapshot.has("control"))
    }

    @Test
    fun `control object defaults to master-enabled and no panic`() {
        store.saveProfile("p1", PRESET_JSON)
        val control = awaitNextSnapshot().getJSONObject("control")
        assertTrue("master defaults on", control.getBoolean("masterEnabled"))
        assertFalse(control.getBoolean("bypass"))
        assertEquals(0L, control.getLong("panicUntilEpochMs"))
        assertFalse(control.getBoolean("sidetoneEnabled"))
        assertEquals("native_first", control.getString("engineMode"))
    }

    @Test
    fun `setMasterEnabled and setBypass propagate into the control object`() {
        store.setMasterEnabled(false)
        assertFalse(awaitNextSnapshot().getJSONObject("control").getBoolean("masterEnabled"))
        store.setBypass(true)
        assertTrue(awaitNextSnapshot().getJSONObject("control").getBoolean("bypass"))
        // Read-back mirror stays in sync.
        val readback = JSONObject(store.buildControlStateJson())
        assertFalse(readback.getBoolean("masterEnabled"))
        assertTrue(readback.getBoolean("bypass"))
    }

    @Test
    fun `panic forces bypass, disables master and records a future expiry`() {
        val before = System.currentTimeMillis()
        store.panic(60_000L)
        val control = awaitNextSnapshot().getJSONObject("control")
        assertTrue(control.getBoolean("bypass"))
        assertFalse(control.getBoolean("masterEnabled"))
        assertTrue("expiry must be in the future", control.getLong("panicUntilEpochMs") >= before + 60_000L)
    }

    @Test
    fun `sidetone settings are published`() {
        store.setSidetone(true, -18.0)
        val control = awaitNextSnapshot().getJSONObject("control")
        assertTrue(control.getBoolean("sidetoneEnabled"))
        assertEquals(-18.0, control.getDouble("sidetoneGainDb"), 1e-6)
    }

    @Test
    fun `engine mode is published, read back and sanitized`() {
        store.setEngineMode("compatibility")
        val compatibility = awaitNextSnapshot().getJSONObject("control")
        assertEquals("compatibility", compatibility.getString("engineMode"))
        assertEquals("compatibility", JSONObject(store.buildControlStateJson()).getString("engineMode"))

        store.setEngineMode("unexpected")
        val sanitized = awaitNextSnapshot().getJSONObject("control")
        assertEquals("native_first", sanitized.getString("engineMode"))
    }

    @Test
    fun `app bindings are stored, read back and cleared on blank preset`() {
        store.saveProfile("preset-7", PRESET_JSON)
        awaitNextSnapshot()
        store.setAppBinding("com.example.app", "preset-7")
        awaitNextSnapshot()
        assertEquals("preset-7", store.getAppBindings()["com.example.app"])
        val readback = JSONObject(store.buildWhitelistBindingsJson())
        assertEquals("preset-7", readback.getJSONObject("appBindings").getString("com.example.app"))

        // Blank preset id removes the binding.
        store.setAppBinding("com.example.app", "")
        awaitNextSnapshot()
        assertNull(store.getAppBindings()["com.example.app"])
    }

    @Test
    fun `whitelist is fail-closed by default and honours explicit entries`() {
        store.updateWhitelist("com.allowed.app", true)
        val whitelist = awaitNextSnapshot().getJSONObject("whitelist")
        assertTrue(whitelist.getBoolean("com.allowed.app"))
        // A process that was never whitelisted is absent — the shim treats absence as not-hooked.
        assertFalse("unknown process must be absent (fail-closed)", whitelist.has("com.unknown.app"))

        store.updateWhitelist("com.allowed.app", false)
        val disabled = awaitNextSnapshot().getJSONObject("whitelist")
        assertFalse(disabled.getBoolean("com.allowed.app"))
    }

    @Test
    fun `invalid app binding names are rejected`() {
        store.setAppBinding("bad name!", "preset-1")
        // Rejected before any snapshot push; the binding must not appear.
        assertFalse(syncBridge.awaitPush(timeoutMs = 200))
        assertNull(store.getAppBindings()["bad name!"])
    }

    @Test
    fun `atomic profile binding sync rejects dangling ids without changing state`() {
        val valid = JSONObject()
            .put("profiles", JSONObject().put("p1", JSONObject(PRESET_JSON)))
            .put("appBindings", JSONObject().put("com.example.app", "p1"))
        assertTrue(store.synchronizeProfilesAndBindings(valid.toString()))
        awaitNextSnapshot()

        val dangling = JSONObject(valid.toString())
        dangling.getJSONObject("appBindings").put("com.example.app", "deleted")
        assertFalse(store.synchronizeProfilesAndBindings(dangling.toString()))
        assertFalse(syncBridge.awaitPush(timeoutMs = 200))
        assertEquals("p1", store.getAppBindings()["com.example.app"])
    }

    @Test
    fun `deleting a profile also removes every binding to it`() {
        store.saveProfile("bound", PRESET_JSON)
        awaitNextSnapshot()
        store.setAppBinding("com.example.app", "bound")
        awaitNextSnapshot()

        store.deleteProfile("bound")
        val deleted = awaitNextSnapshot()

        assertFalse(deleted.getJSONObject("profiles").has("bound"))
        assertFalse(deleted.getJSONObject("appBindings").has("com.example.app"))
        assertNull(store.getAppBindings()["com.example.app"])
    }
}

/** Records every pushed payload and lets a test await the next push. */
private class CountingSyncBridge : ProfileSyncChannel {
    private val permits = Semaphore(0)
    @Volatile private var payload: String? = null

    override fun pushProfiles(json: String) {
        payload = json
        permits.release()
    }

    fun lastPayload(): String? = payload

    fun awaitPush(timeoutMs: Long = 1000): Boolean =
        permits.tryAcquire(1, timeoutMs, TimeUnit.MILLISECONDS)
}
