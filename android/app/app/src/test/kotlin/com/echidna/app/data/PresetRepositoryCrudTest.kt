package com.echidna.app.data

import com.echidna.app.data.PresetSerializer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CRUD + import/export tests against [ControlStateRepository], the app's preset store.
 *
 * The repository is a process singleton, so every assertion is delta-based (measure
 * the list before/after) and each test cleans up the presets it creates. Without a
 * bound [android.content.Context] the repository's disk-persistence and service-push
 * paths are guarded no-ops, so the in-memory CRUD is exercised in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PresetRepositoryCrudTest {

    private val repo = ControlStateRepository

    @Test
    fun `createPreset appends a new preset and returns its id`() {
        val before = repo.presets.value.size
        val id = repo.createPreset("CRUD Create", "desc", null)
        try {
            assertEquals(before + 1, repo.presets.value.size)
            val created = repo.presets.value.firstOrNull { it.id == id }
            assertNotNull(created)
            assertEquals("CRUD Create", created!!.name)
            assertEquals("desc", created.description)
        } finally {
            repo.deletePreset(id)
        }
    }

    @Test
    fun `createPreset from a base clones the base modules`() {
        val base = repo.presets.value.first { it.modules.size > 1 }
        val id = repo.createPreset("CRUD Clone", null, base.id)
        try {
            val clone = repo.presets.value.first { it.id == id }
            assertEquals(base.modules.size, clone.modules.size)
            assertEquals(base.modules.map { it.id }, clone.modules.map { it.id })
        } finally {
            repo.deletePreset(id)
        }
    }

    @Test
    fun `renamePreset updates only the target`() {
        val id = repo.createPreset("Before Rename", null, null)
        try {
            repo.renamePreset(id, "After Rename")
            assertEquals("After Rename", repo.presets.value.first { it.id == id }.name)
        } finally {
            repo.deletePreset(id)
        }
    }

    @Test
    fun `deletePreset removes the target`() {
        val id = repo.createPreset("To Delete", null, null)
        val afterCreate = repo.presets.value.size
        repo.deletePreset(id)
        assertEquals(afterCreate - 1, repo.presets.value.size)
        assertNull(repo.presets.value.firstOrNull { it.id == id })
    }

    @Test
    fun `deletePreset refuses to remove the final preset`() {
        // Drain down to a single preset, confirm the guard holds, then restore.
        val original = repo.presets.value
        val keepId = original.first().id
        original.filter { it.id != keepId }.forEach { repo.deletePreset(it.id) }
        assertEquals(1, repo.presets.value.size)
        repo.deletePreset(keepId)
        assertEquals("last preset must be protected", 1, repo.presets.value.size)
        // Restore the removed presets so sibling tests see the original set.
        original.filter { it.id != keepId }.forEach {
            repo.importPreset(PresetSerializer.toJson(it))
        }
    }

    @Test
    fun `setDefaultPreset points at an existing preset`() {
        val id = repo.createPreset("Default Target", null, null)
        try {
            repo.setDefaultPreset(id)
            assertEquals(id, repo.defaultPresetId.value)
        } finally {
            repo.setDefaultPreset(repo.presets.value.first { it.id != id }.id)
            repo.deletePreset(id)
        }
    }

    @Test
    fun `import then export round-trips a preset bundle`() {
        val json = """
            {"name":"Imported","meta":{"tags":["FX"]},
             "engine":{"latencyMode":"HQ"},
             "modules":[{"id":"pitch","semitones":-7,"enabled":true},{"id":"mix","wet":80}]}
        """.trimIndent()
        val id = repo.importPreset(json)
        assertNotNull("valid bundle must import", id)
        try {
            val exported = repo.exportPreset(id!!)
            assertNotNull(exported)
            val reparsed = PresetSerializer.fromJson(exported!!)
            assertNotNull(reparsed)
            assertEquals("Imported", reparsed!!.name)
            assertEquals(80, reparsed.dryWet)
        } finally {
            id?.let { repo.deletePreset(it) }
        }
    }

    @Test
    fun `importPreset rejects an invalid bundle`() {
        val before = repo.presets.value.size
        assertNull(repo.importPreset("""{"name":""}"""))
        assertEquals(before, repo.presets.value.size)
    }

    @Test
    fun `exportAllPresets emits a json array covering every preset`() {
        val exported = repo.exportAllPresets()
        val array = JSONArray(exported)
        assertEquals(repo.presets.value.size, array.length())
        // Each element must be a preset the serializer can read back.
        for (i in 0 until array.length()) {
            assertNotNull(PresetSerializer.fromJson(array.getJSONObject(i).toString()))
        }
    }

    @Test
    fun `exportPreset returns null for an unknown id`() {
        assertNull(repo.exportPreset("no-such-id"))
    }
}
