package com.echidna.app.system

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Staging contract for [EngineModuleArchive]: the guided installer hands the returned path to the
 * privileged service, which runs `magisk --install-module <path>` as root. The class must therefore
 * either produce a real file on disk holding exactly the selected bytes, or return null — never a
 * path that does not exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EngineModuleArchiveTest {

    private lateinit var context: Application
    private lateinit var archive: EngineModuleArchive

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        archive = EngineModuleArchive(context)
        stagedFile().delete()
    }

    @After
    fun tearDown() {
        stagedFile().delete()
    }

    private fun stagedFile() = File(context.cacheDir, EngineModuleArchive.STAGED_NAME)

    private fun register(uri: Uri, stream: InputStream) {
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
    }

    @Test
    fun `staging a picked archive writes the exact bytes to a real path`() {
        val uri = Uri.parse("content://com.example.docs/module.zip")
        // A minimal but real zip header, so the assertion is about byte fidelity rather than text.
        val payload = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(4096) { (it % 251).toByte() }
        register(uri, ByteArrayInputStream(payload))

        val path = archive.stageArchive(uri)

        assertEquals(stagedFile().absolutePath, path)
        val staged = File(path!!)
        assertTrue("the returned path must exist on disk", staged.isFile)
        assertArrayEquals("the staged archive must be byte-identical", payload, staged.readBytes())
    }

    @Test
    fun `staging is world-readable so the root-side installer can read it`() {
        val uri = Uri.parse("content://com.example.docs/readable.zip")
        register(uri, ByteArrayInputStream("payload".toByteArray()))

        val path = archive.stageArchive(uri)

        assertTrue(File(path!!).canRead())
    }

    @Test
    fun `re-staging replaces the previous archive rather than appending to it`() {
        val first = Uri.parse("content://com.example.docs/first.zip")
        val second = Uri.parse("content://com.example.docs/second.zip")
        register(first, ByteArrayInputStream(ByteArray(8192) { 1 }))
        register(second, ByteArrayInputStream("short".toByteArray()))

        archive.stageArchive(first)
        val path = archive.stageArchive(second)

        assertEquals("short", File(path!!).readText())
        assertEquals(5L, File(path).length())
    }

    @Test
    fun `an unreadable source yields null instead of a partially staged path`() {
        val uri = Uri.parse("content://com.example.docs/broken.zip")
        register(uri, object : InputStream() {
            override fun read(): Int = throw IOException("source went away")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("source went away")
        })

        assertNull("a failed copy must not hand back a path", archive.stageArchive(uri))
    }

    @Test
    fun `a build without a bundled module never fabricates an archive path`() {
        // Debug builds do not ship the release-only asset; the installer must therefore be told
        // there is nothing to flash rather than be handed a nonexistent path.
        val bundled = context.assets.list("").orEmpty().contains(EngineModuleArchive.ASSET_NAME)
        val path = archive.bundledArchivePath()

        if (bundled) {
            assertTrue("a bundled archive must be staged to a real file", File(path!!).isFile)
        } else {
            assertNull(path)
            assertTrue(
                "nothing may be left in the cache when no archive is bundled",
                !stagedFile().exists() || stagedFile().length() == 0L,
            )
        }
    }
}
