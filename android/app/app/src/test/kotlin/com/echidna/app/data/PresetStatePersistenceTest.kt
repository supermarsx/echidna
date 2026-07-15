package com.echidna.app.data

import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PresetStatePersistenceTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("echidna-preset-state").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `restart round trip preserves preset ids and active selection`() {
        val first = preset("first")
        val selected = preset("selected")
        val file = File(tempDir, "presets.json")
        val encoded = PresetStoreCodec.encode(
            PersistedPresetStore(
                listOf(first, selected),
                selected.id,
                first.id,
                mapOf("com.example.recorder" to selected.id),
            )
        )
        writeAtomicUtf8(file, encoded)

        val restarted = PresetStoreCodec.decode(readAtomicUtf8(file))

        assertNotNull(restarted)
        assertEquals(listOf(first.id, selected.id), restarted!!.presets.map(Preset::id))
        assertEquals(selected.id, restarted.activePresetId)
        assertEquals(first.id, restarted.defaultPresetId)
        assertEquals(selected.id, restarted.appBindings?.get("com.example.recorder"))
    }

    @Test
    fun `active default and app binding resolve same preset after two restarts`() {
        val target = preset("stable-bound-id")
        val appBindings = mapOf("com.example.recorder" to target.id)
        val initial = PersistedPresetStore(
            listOf(preset("other"), target),
            target.id,
            target.id,
            appBindings,
        )

        val firstRestart = PresetStoreCodec.decode(PresetStoreCodec.encode(initial))!!
        val secondRestart = PresetStoreCodec.decode(PresetStoreCodec.encode(firstRestart))!!
        val boundId = secondRestart.appBindings!!.getValue("com.example.recorder")

        assertEquals(target.id, secondRestart.activePresetId)
        assertEquals(target.id, secondRestart.defaultPresetId)
        assertEquals(target.name, secondRestart.presets.single { it.id == boundId }.name)
    }

    @Test
    fun `legacy preset without id migrates once and then remains stable`() {
        val legacyPreset = JSONObject()
            .put("name", "Legacy")
            .put("engine", JSONObject().put("latencyMode", "LL"))
            .put("modules", JSONArray().put(JSONObject().put("id", "mix").put("wet", 50)))
        val legacyStore = JSONObject()
            .put("activePresetId", "missing-legacy-id")
            .put("presets", JSONArray().put(legacyPreset))
            .toString()

        val migrated = PresetStoreCodec.decode(legacyStore)
        assertNotNull(migrated)
        val generatedId = migrated!!.presets.single().id
        assertTrue(generatedId.isNotBlank())

        val restarted = PresetStoreCodec.decode(PresetStoreCodec.encode(migrated))
        assertEquals(generatedId, restarted!!.presets.single().id)
        assertEquals(generatedId, restarted.activePresetId)

        // Bind only after the one-time legacy id assignment; subsequent restarts preserve it.
        val binding = mapOf("com.example.legacy" to generatedId)
        val secondRestart = PresetStoreCodec.decode(PresetStoreCodec.encode(
            restarted.copy(appBindings = binding)
        ))!!
        assertEquals(
            "Legacy",
            secondRestart.presets.single { it.id == binding.getValue("com.example.legacy") }.name,
        )
        assertEquals(generatedId, secondRestart.appBindings?.get("com.example.legacy"))
    }

    @Test
    fun `missing active and default ids fall back to first valid preset`() {
        val first = preset("first")
        val second = preset("second")
        val root = JSONObject(PresetStoreCodec.encode(
            PersistedPresetStore(listOf(first, second), second.id, second.id)
        ))
            .put("activePresetId", "deleted")
            .put("defaultPresetId", "deleted")

        val restored = PresetStoreCodec.decode(root.toString())

        assertEquals(first.id, restored!!.activePresetId)
        assertEquals(first.id, restored.defaultPresetId)
    }

    @Test
    fun `dangling app binding ids are removed during restart decode`() {
        val first = preset("first")
        val encoded = JSONObject(PresetStoreCodec.encode(
            PersistedPresetStore(
                listOf(first),
                first.id,
                first.id,
                mapOf("com.example.recorder" to first.id),
            )
        ))
        encoded.getJSONObject("appBindings").put("com.example.stale", "deleted")

        val restarted = PresetStoreCodec.decode(encoded.toString())!!

        assertEquals(mapOf("com.example.recorder" to first.id), restarted.appBindings)
    }

    @Test
    fun `corrupt or truncated JSON is rejected without inventing state`() {
        assertNull(PresetStoreCodec.decode("{"))
        assertNull(PresetStoreCodec.decode("""{"presets":[{"name":"cut"}]}"""))
    }

    @Test
    fun `failed atomic write preserves previous complete state`() {
        val file = File(tempDir, "atomic.json")
        writeAtomicUtf8(file, "previous-complete-state")

        val failure = runCatching {
            writeAtomicUtf8(file, "replacement") { throw IOException("injected failure") }
        }

        assertTrue(failure.isFailure)
        assertEquals("previous-complete-state", readAtomicUtf8(file))
    }

    @Test
    fun `concurrent rapid updates leave the highest submitted version`() {
        val file = File(tempDir, "rapid.json")
        val writer = LatestAtomicTextFileWriter(file)
        val valuesByVersion = ConcurrentHashMap<Long, String>()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        repeat(32) { index ->
            pool.execute {
                ready.countDown()
                start.await()
                val value = "payload-$index"
                valuesByVersion[writer.submit(value)] = value
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(writer.awaitIdle())

        val newestVersion = valuesByVersion.keys.maxOrNull()
        assertNotNull(newestVersion)
        assertEquals(valuesByVersion[newestVersion], readAtomicUtf8(file))
        assertFalse(readAtomicUtf8(file).isBlank())
    }

    private fun preset(id: String): Preset = Preset(
        id = id,
        name = id,
        description = null,
        tags = emptySet(),
        latencyMode = LatencyMode.LOW_LATENCY,
        dryWet = 50,
        modules = listOf(EffectModule.Mix(true, 50f, 0f)),
    )
}
