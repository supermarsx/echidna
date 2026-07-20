package com.echidna.app.data

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Durability contract for the app's state files. [PresetStatePersistenceTest] covers the
 * preset-store round trip on top of these helpers; this pins the primitives themselves —
 * what survives a process death mid-write, what a failed write leaves behind, and that a
 * write failure cannot wedge the coalescing writer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AtomicStateFileTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("echidna-atomic-state").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `round trip preserves multi-byte utf-8 payloads`() {
        val file = File(tempDir, "utf8.json")
        // Preset names are user-supplied: the encoding must be pinned to UTF-8 rather than the
        // platform default, or non-ASCII names come back mangled on restart.
        val payload = """{"name":"Ünïcödé ✓ 日本語 🎛"}"""
        writeAtomicUtf8(file, payload)

        assertEquals(payload, readAtomicUtf8(file))
        assertEquals(payload, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `an abandoned write leaves the previous complete state readable`() {
        val file = File(tempDir, "interrupted.json")
        writeAtomicUtf8(file, "complete-v1")

        // Simulate process death between startWrite() and finishWrite(): the base file is left
        // half-written and never committed. Nothing calls failWrite.
        val abandoned = AtomicFile(file)
        val stream = abandoned.startWrite()
        stream.write("half-writ".toByteArray(Charsets.UTF_8))
        stream.flush()
        stream.close()

        assertEquals("complete-v1", readAtomicUtf8(file))
    }

    @Test
    fun `a rolled-back write is fully recovered and the next write still commits`() {
        val file = File(tempDir, "rollback.json")
        writeAtomicUtf8(file, "complete-v1")

        val failure = runCatching {
            writeAtomicUtf8(file, "doomed-v2") { throw IOException("power loss") }
        }
        assertTrue(failure.isFailure)
        // The original exception must propagate, not a wrapped/substituted one.
        assertEquals("power loss", failure.exceptionOrNull()?.message)
        assertEquals("complete-v1", readAtomicUtf8(file))

        // The file must not be left in a state that blocks subsequent writes.
        writeAtomicUtf8(file, "complete-v3")
        assertEquals("complete-v3", readAtomicUtf8(file))
    }

    @Test
    fun `shrinking payloads do not leave trailing bytes from the previous write`() {
        val file = File(tempDir, "shrink.json")
        writeAtomicUtf8(file, """{"presets":["a","b","c","d","e","f","g","h"]}""")
        writeAtomicUtf8(file, """{"presets":[]}""")

        assertEquals("""{"presets":[]}""", readAtomicUtf8(file))
    }

    @Test
    fun `writer persists the last sequential submission`() {
        val file = File(tempDir, "sequential.json")
        val writer = LatestAtomicTextFileWriter(file)

        val versions = (1..20).map { writer.submit("payload-$it") }
        assertTrue(writer.awaitIdle(5L, TimeUnit.SECONDS))

        // Versions are monotonic and the file holds the newest submission.
        assertEquals(versions.sorted(), versions)
        assertEquals(versions.size, versions.distinct().size)
        assertEquals("payload-20", readAtomicUtf8(file))
    }

    @Test
    fun `a failing write does not wedge the writer for later submissions`() {
        // A regular file stands where the state directory should be, so AtomicFile cannot create
        // the parent and the drain loop's write throws.
        val blocker = File(tempDir, "blocker")
        blocker.writeText("not a directory")
        val file = File(blocker, "state.json")
        val writer = LatestAtomicTextFileWriter(file)

        writer.submit("doomed")
        assertTrue("failed persist must still settle the worker", writer.awaitIdle(5L, TimeUnit.SECONDS))
        assertFalse(file.exists())

        // Once the path is usable a later submission must still reach disk — the earlier failure
        // must not have left the worker permanently scheduled or the pending slot occupied.
        assertTrue(blocker.delete())
        assertTrue(blocker.mkdirs())
        writer.submit("recovered")
        assertTrue(writer.awaitIdle(5L, TimeUnit.SECONDS))
        assertEquals("recovered", readAtomicUtf8(file))
    }

    @Test
    fun `each writer instance targets only its own file`() {
        val presets = File(tempDir, "presets.json")
        val settings = File(tempDir, "settings.json")
        val presetWriter = LatestAtomicTextFileWriter(presets)
        val settingsWriter = LatestAtomicTextFileWriter(settings)

        presetWriter.submit("preset-state")
        settingsWriter.submit("settings-state")
        assertTrue(presetWriter.awaitIdle(5L, TimeUnit.SECONDS))
        assertTrue(settingsWriter.awaitIdle(5L, TimeUnit.SECONDS))

        assertEquals("preset-state", readAtomicUtf8(presets))
        assertEquals("settings-state", readAtomicUtf8(settings))
        assertNotEquals(readAtomicUtf8(presets), readAtomicUtf8(settings))
    }

    @Test
    fun `awaitIdle reports true when nothing was ever submitted`() {
        val writer = LatestAtomicTextFileWriter(File(tempDir, "idle.json"))
        assertTrue(writer.awaitIdle(1L, TimeUnit.SECONDS))
    }
}
