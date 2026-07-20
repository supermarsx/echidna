package com.echidna.control.service

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Durability contract for the privileged profile store's file primitives. The service persists
 * authorisation-relevant state here, so a failed or interrupted write must leave the previous
 * complete state intact and must surface as an [IOException] rather than silently succeeding.
 */
class AtomicProfileStoreFileTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("echidna-profile-store").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `round trip preserves multi-byte utf-8 payloads`() {
        val file = File(tempDir, "profiles.json")
        val payload = """{"owner":"Ünïcödé ✓ 日本語"}"""
        writeProfileStoreAtomic(file, payload)

        assertEquals(payload, readProfileStoreAtomic(file))
        assertEquals(payload, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `a failure before commit preserves the previous complete state`() {
        val file = File(tempDir, "rollback.json")
        writeProfileStoreAtomic(file, "complete-v1")

        assertThrows(IOException::class.java) {
            writeProfileStoreAtomic(file, "doomed-v2") { throw IOException("power loss") }
        }

        assertEquals("complete-v1", readProfileStoreAtomic(file))
    }

    @Test
    fun `a non-IO failure is wrapped as an IOException so callers cannot miss it`() {
        val file = File(tempDir, "wrapped.json")
        writeProfileStoreAtomic(file, "complete-v1")

        val thrown = assertThrows(IOException::class.java) {
            writeProfileStoreAtomic(file, "doomed-v2") { throw IllegalStateException("bad state") }
        }

        assertEquals("Unable to atomically persist profile state", thrown.message)
        assertTrue(thrown.cause is IllegalStateException)
        assertEquals("complete-v1", readProfileStoreAtomic(file))
    }

    @Test
    fun `the store is writable again after a rolled-back attempt`() {
        val file = File(tempDir, "recovery.json")
        writeProfileStoreAtomic(file, "complete-v1")
        runCatching { writeProfileStoreAtomic(file, "doomed") { throw IOException("nope") } }

        writeProfileStoreAtomic(file, "complete-v2")

        assertEquals("complete-v2", readProfileStoreAtomic(file))
    }

    @Test
    fun `an abandoned write leaves the previous complete state readable`() {
        val file = File(tempDir, "interrupted.json")
        writeProfileStoreAtomic(file, "complete-v1")

        // Simulate the service being killed between startWrite() and finishWrite(): nothing
        // commits and nothing rolls back explicitly.
        val abandoned = AtomicFile(file)
        val stream = abandoned.startWrite()
        stream.write("half-writ".toByteArray(Charsets.UTF_8))
        stream.flush()
        stream.close()

        assertEquals("complete-v1", readProfileStoreAtomic(file))
    }

    @Test
    fun `shrinking payloads do not leave trailing bytes behind`() {
        val file = File(tempDir, "shrink.json")
        writeProfileStoreAtomic(file, """{"profiles":["a","b","c","d","e","f"]}""")
        writeProfileStoreAtomic(file, """{"profiles":[]}""")

        assertEquals("""{"profiles":[]}""", readProfileStoreAtomic(file))
    }

    @Test
    fun `a missing parent directory is created on first write`() {
        val file = File(File(tempDir, "not-created-yet"), "profiles.json")

        writeProfileStoreAtomic(file, "first-boot-state")

        assertEquals("first-boot-state", readProfileStoreAtomic(file))
    }

    @Test
    fun `an unusable destination fails loudly rather than silently dropping state`() {
        // The would-be parent directory is a regular file, so the directory cannot be created.
        val blocker = File(tempDir, "blocker")
        blocker.writeText("not a directory")
        val file = File(blocker, "profiles.json")

        assertThrows(IOException::class.java) {
            writeProfileStoreAtomic(file, "state")
        }
    }
}
