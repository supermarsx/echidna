package com.echidna.control.service

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileStorePersistenceTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        PublishedPolicyRegistry.resetForTests()
        tempDir = kotlin.io.path.createTempDirectory("profile-store-persistence").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `non-active binding survives cold restart and bound edits stay current`() {
        val firstBridge = PayloadBridge()
        val first = ProfileStore(tempDir, firstBridge)
        assertTrue(first.synchronizePolicyState(syncState(boundName = "Bound v1")))
        first.close()
        assertTrue(first.awaitClosed())

        val restartedBridge = PayloadBridge()
        val restarted = ProfileStore(tempDir, restartedBridge)
        assertEquals("bound", restarted.getAppBindings()["com.example.recorder"])
        assertTrue(restarted.getWhitelist().getValue("com.example.recorder"))
        assertTrue(restarted.resolveProfilePayload("bound")!!.contains("Bound v1"))

        assertTrue(restarted.synchronizePolicyState(syncState(boundName = "Bound v2")))
        restarted.close()
        assertTrue(restarted.awaitClosed())

        val secondRestart = ProfileStore(tempDir, PayloadBridge())
        assertEquals("bound", secondRestart.getAppBindings()["com.example.recorder"])
        assertTrue(secondRestart.resolveProfilePayload("bound")!!.contains("Bound v2"))
        secondRestart.close()
        assertTrue(secondRestart.awaitClosed())
    }

    @Test
    fun `rapid bind edit delete coalesces to newest complete snapshot`() {
        val blockerStarted = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            blockerStarted.countDown()
            unblock.await()
        }
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS))
        val bridge = PayloadBridge()
        val store = ProfileStore(tempDir, bridge, executor)

        assertTrue(store.synchronizePolicyState(syncState(boundName = "Bound v1")))
        assertTrue(store.synchronizePolicyState(syncState(boundName = "Bound v2")))
        assertTrue(store.synchronizePolicyState(syncState(includeBound = false)))
        unblock.countDown()
        store.close()
        assertTrue(store.awaitClosed())

        assertEquals(1, bridge.payloads.size)
        val newest = JSONObject(bridge.payloads.single())
        assertFalse(newest.getJSONObject("profiles").has("bound"))
        assertFalse(newest.getJSONObject("appBindings").has("com.example.recorder"))
    }

    @Test
    fun `bounded close drains newest pending state`() {
        val bridge = PayloadBridge()
        val store = ProfileStore(tempDir, bridge)
        repeat(100) { index ->
            assertTrue(store.synchronizePolicyState(syncState(boundName = "Bound $index")))
        }

        val closeStartedAt = System.nanoTime()
        store.close()
        val closeElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedAt)

        assertTrue("lifecycle close must not join the writer", closeElapsedMs < 250L)
        assertTrue("worker drain must be bounded", store.awaitClosed(2_000L))
        val restarted = ProfileStore(tempDir, PayloadBridge())
        assertTrue(restarted.resolveProfilePayload("bound")!!.contains("Bound 99"))
        restarted.close()
        assertTrue(restarted.awaitClosed())
    }

    @Test
    fun `panic deadline survives restart then clears without changing base controls`() {
        val first = ProfileStore(tempDir, PayloadBridge())
        assertTrue(first.synchronizePolicyState(syncState()))
        first.panic(1_000L)
        first.close()
        assertTrue(first.awaitClosed())

        val restarted = ProfileStore(tempDir, PayloadBridge())
        val restored = JSONObject(restarted.buildControlStateJson())
        assertTrue(restored.getBoolean("masterEnabled"))
        assertFalse(restored.getBoolean("bypass"))
        assertTrue(restored.getLong("panicUntilEpochMs") > System.currentTimeMillis())

        val expiryWaitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (
            JSONObject(restarted.buildControlStateJson()).getLong("panicUntilEpochMs") != 0L &&
            System.nanoTime() < expiryWaitDeadline
        ) {
            Thread.sleep(10L)
        }
        val recovered = JSONObject(restarted.buildControlStateJson())
        assertEquals(0L, recovered.getLong("panicUntilEpochMs"))
        assertTrue(recovered.getBoolean("masterEnabled"))
        assertFalse(recovered.getBoolean("bypass"))
        restarted.close()
        assertTrue(restarted.awaitClosed())

        val secondRestart = ProfileStore(tempDir, PayloadBridge())
        assertEquals(
            0L,
            JSONObject(secondRestart.buildControlStateJson()).getLong("panicUntilEpochMs"),
        )
        secondRestart.close()
        assertTrue(secondRestart.awaitClosed())
    }

    @Test
    fun `main lifecycle close returns before queued IO and persistence stays off caller thread`() {
        val blockerStarted = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "profile-io-test")
        }
        executor.execute {
            blockerStarted.countDown()
            unblock.await()
        }
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS))
        val bridge = PayloadBridge()
        val store = ProfileStore(tempDir, bridge, executor)
        assertTrue(store.synchronizePolicyState(syncState()))
        val lifecycleThread = Thread.currentThread().name

        val startedAt = System.nanoTime()
        store.close()
        val closeElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("close joined queued IO on lifecycle thread", closeElapsedMs < 250L)
        assertTrue(bridge.payloads.isEmpty())
        unblock.countDown()
        assertTrue(store.awaitClosed(1_000L))
        assertEquals(listOf("profile-io-test"), bridge.threadNames)
        assertFalse(bridge.threadNames.contains(lifecycleThread))
    }

    @Test
    fun `atomic write failure preserves previous complete snapshot`() {
        val file = File(tempDir, "profiles.json")
        writeProfileStoreAtomic(file, "previous-complete")

        val failure = runCatching {
            writeProfileStoreAtomic(file, "replacement") {
                throw IOException("injected commit failure")
            }
        }

        assertTrue(failure.isFailure)
        assertEquals("previous-complete", readProfileStoreAtomic(file))
    }

    private fun syncState(boundName: String = "Bound", includeBound: Boolean = true): String {
        val profiles = JSONObject()
            .put("active", preset("Active"))
        val bindings = JSONObject()
        if (includeBound) {
            profiles.put("bound", preset(boundName))
            bindings.put("com.example.recorder", "bound")
        }
        return JSONObject()
            .put("schemaVersion", POLICY_SCHEMA_VERSION)
            .put("profiles", profiles)
            .put("defaultProfileId", "active")
            .put("appBindings", bindings)
            .put("whitelist", JSONObject().put("com.example.recorder", true))
            .put("captureOwners", JSONObject().put("com.example.recorder", "zygisk"))
            .put(
                "control",
                JSONObject()
                    .put("masterEnabled", true)
                    .put("bypass", false)
                    .put("panicUntilEpochMs", 0L)
                    .put("sidetoneEnabled", false)
                    .put("sidetoneGainDb", 0.0)
                    .put("engineMode", "native_first"),
            )
            .toString()
    }

    private fun preset(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("engine", JSONObject().put("latencyMode", "LL"))
        .put("modules", org.json.JSONArray())
}

private class PayloadBridge : ProfileSyncChannel {
    val payloads = CopyOnWriteArrayList<String>()
    val threadNames = CopyOnWriteArrayList<String>()

    override fun pushProfiles(json: String) {
        payloads += json
        threadNames += Thread.currentThread().name
    }
}
