package com.echidna.control.service

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val VALID_PROFILE_ID = "p1"
private const val VALID_PROFILE_JSON = """{
    "profiles": {
        "p1": {
            "name": "Test",
            "engine": {"latencyMode": "LL", "blockMs": 10},
            "modules": [
                {"id":"mix","wet":50,"outGain":0.0}
            ]
        }
    },
    "whitelist": {"com.example.app": true}
}"""

class ProfileStoreTest {
    private lateinit var tempDir: File
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var syncBridge: RecordingSyncBridge
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "profiles")
        executor = Executors.newSingleThreadExecutor()
        syncBridge = RecordingSyncBridge()
        store = ProfileStore(tempDir, syncBridge, executor)
    }

    @After
    fun tearDown() {
        store.close()
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    @Test
    fun `pushes valid profile payload to sync bridge`() {
        store.saveProfile(VALID_PROFILE_ID, VALID_PROFILE_JSON)
        assertTrue(syncBridge.awaitPush())
        val payload = syncBridge.lastPayload()
        assertTrue(payload?.contains(VALID_PROFILE_ID) == true)
        // Expect persisted file to exist.
        assertTrue(File(tempDir, "profiles.json").exists())
    }

    @Test
    fun `rejects invalid profile payload`() {
        store.saveProfile("bad", """{"name":"Bad"}""")
        // No push should have happened.
        assertFalse(syncBridge.awaitPush(timeoutMs = 200))
    }

    @Test
    fun `whitelist updates are persisted and pushed`() {
        store.updateWhitelist("com.test.app", true)
        assertTrue(syncBridge.awaitPush())
        val payload = syncBridge.lastPayload()
        assertTrue(payload?.contains("com.test.app") == true)
        val stored = File(tempDir, "profiles.json").readText(StandardCharsets.UTF_8)
        val json = JSONObject(stored)
        val whitelist = json.optJSONObject("whitelist")
        assertTrue(whitelist?.optBoolean("com.test.app") == true)
    }
}

private class RecordingSyncBridge : ProfileSyncChannel {
    private val latch = CountDownLatch(1)
    @Volatile private var payload: String? = null

    override fun pushProfiles(json: String) {
        payload = json
        latch.countDown()
    }

    fun lastPayload(): String? = payload

    fun awaitPush(timeoutMs: Long = 1000): Boolean =
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
