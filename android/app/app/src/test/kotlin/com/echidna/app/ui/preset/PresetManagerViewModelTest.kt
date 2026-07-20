package com.echidna.app.ui.preset

import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.Preset
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PresetManagerViewModel] is mostly a pass-through to [ControlStateRepository] (whose CRUD is
 * covered by PresetRepositoryCrudTest). The behaviour that lives in the view model itself is
 * `duplicatePreset`, which is pinned here along with the selection surface the manager screen
 * drives. Created presets are removed in teardown because the repository is a process singleton.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PresetManagerViewModelTest {

    private val repo = ControlStateRepository
    private val vm = PresetManagerViewModel()
    private lateinit var originalActiveId: String
    private lateinit var originalDefaultId: String
    private val created = mutableListOf<String>()

    @Before
    fun setUp() {
        originalActiveId = repo.activePreset.value.id
        originalDefaultId = repo.defaultPresetId.value
    }

    @After
    fun tearDown() {
        repo.selectPreset(originalActiveId)
        repo.setDefaultPreset(originalDefaultId)
        created.forEach(repo::deletePreset)
    }

    private fun track(id: String): String = id.also(created::add)

    private fun presetsById(id: String): Preset? = vm.presets.value.firstOrNull { it.id == id }

    /** Waits for the repository's Dispatchers.Default-backed `activePreset` combine to catch up. */
    private fun awaitActive(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            if (vm.activePreset.value.id == id) return
            Thread.sleep(2L)
        }
        fail("activePreset never became $id (was ${vm.activePreset.value.id})")
    }

    @Test
    fun `duplicatePreset clones the chain under a copy-suffixed name`() {
        val sourceId = track(vm.createPreset("Manager Source", "source notes", null))
        val source = presetsById(sourceId)!!

        vm.duplicatePreset(sourceId)

        val copy = vm.presets.value.last()
        track(copy.id)
        assertEquals("Manager Source (copy)", copy.name)
        assertEquals("the copy keeps the source description", "source notes", copy.description)
        assertNotEquals("a duplicate must get its own id", sourceId, copy.id)
        assertEquals(source.modules.map { it.id }, copy.modules.map { it.id })
        assertEquals(source.modules.size, copy.modules.size)
    }

    @Test
    fun `duplicating an unknown id adds nothing`() {
        val before = vm.presets.value.size
        vm.duplicatePreset("no-such-preset-id")
        assertEquals(before, vm.presets.value.size)
    }

    @Test
    fun `duplicating twice produces two independent copies`() {
        val sourceId = track(vm.createPreset("Twice Source", null, null))

        vm.duplicatePreset(sourceId)
        val first = vm.presets.value.last().also { track(it.id) }
        vm.duplicatePreset(sourceId)
        val second = vm.presets.value.last().also { track(it.id) }

        assertNotEquals(first.id, second.id)
        assertEquals("Twice Source (copy)", first.name)
        assertEquals("Twice Source (copy)", second.name)
        // Editing one copy must not reach the other — the modules were deep-cloned, not shared.
        repo.renamePreset(first.id, "Renamed Copy")
        assertEquals("Renamed Copy", presetsById(first.id)!!.name)
        assertEquals("Twice Source (copy)", presetsById(second.id)!!.name)
    }

    @Test
    fun `selectPreset moves the active preset the editor reads`() {
        val a = track(vm.createPreset("Select A", null, null))
        val b = track(vm.createPreset("Select B", null, null))

        vm.selectPreset(a)
        awaitActive(a)
        vm.selectPreset(b)
        awaitActive(b)
    }

    @Test
    fun `setDefaultPreset is exposed through the manager's default id flow`() {
        val id = track(vm.createPreset("Default Via Manager", null, null))
        vm.setDefaultPreset(id)
        assertEquals(id, vm.defaultPresetId.value)
    }

    @Test
    fun `share and export refuse an unknown preset instead of emitting a stub`() {
        assertNull(vm.exportPreset("no-such-preset-id"))
        assertNull(vm.sharePreset("no-such-preset-id"))
    }

    @Test
    fun `an exported preset can be imported back through the manager`() {
        val sourceId = track(vm.createPreset("Export Round Trip", "notes", null))
        val exported = vm.exportPreset(sourceId)
        assertNotNull(exported)

        val importedId = vm.importPreset(exported!!)
        assertNotNull("a self-exported bundle must import", importedId)
        track(importedId!!)

        assertEquals("Export Round Trip", presetsById(importedId)!!.name)
        assertNotEquals(sourceId, importedId)
    }

    @Test
    fun `importPreset rejects malformed json without touching the list`() {
        val before = vm.presets.value.size
        assertNull(vm.importPreset("{ not json"))
        assertNull(vm.importPreset("""{"name":""}"""))
        assertEquals(before, vm.presets.value.size)
    }

    @Test
    fun `exportAllPresets covers every preset the manager lists`() {
        track(vm.createPreset("Export All Member", null, null))
        val exported = org.json.JSONArray(vm.exportAllPresets())
        assertEquals(vm.presets.value.size, exported.length())
        assertTrue(exported.length() > 0)
    }
}
