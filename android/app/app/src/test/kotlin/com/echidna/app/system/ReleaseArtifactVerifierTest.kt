package com.echidna.app.system

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The verification rules the download path is built on. These are the checks that stand between a
 * network-supplied file and a root install, so each one is asserted to FAIL CLOSED with a message
 * that names which check rejected the artifact.
 */
class ReleaseArtifactVerifierTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `checksum list parses sha256sum output including binary markers and paths`() {
        val checksums = ReleaseArtifactVerifier.parseChecksums(
            """
            # SHA256SUMS.txt
            ${DIGEST_A}  echidna-magisk-v1.2.3.zip
            ${DIGEST_B} *out/echidna-companion-v1.2.3.apk
            not-a-digest  garbage.txt
            """.trimIndent()
        )

        assertEquals(DIGEST_A, checksums["echidna-magisk-v1.2.3.zip"])
        assertEquals(DIGEST_B, checksums["echidna-companion-v1.2.3.apk"])
        assertEquals(2, checksums.size)
    }

    @Test
    fun `a content hash mismatch is rejected and names the checksum check`() {
        val file = folder.newFile("echidna-magisk-v1.zip").apply { writeText("payload") }

        val error = assertFails {
            ReleaseArtifactVerifier.requireContentDigest(file, "echidna-magisk-v1.zip", DIGEST_A)
        }

        assertTrue(error.message!!.contains("Checksum check failed"))
        assertTrue(error.message!!.contains(DIGEST_A))
    }

    @Test
    fun `a matching content hash passes`() {
        val file = folder.newFile("payload.zip").apply { writeText("payload") }

        ReleaseArtifactVerifier.requireContentDigest(file, "payload.zip", ReleaseArtifactVerifier.sha256(file))
    }

    @Test
    fun `an asset missing from the checksum list is rejected`() {
        val error = assertFails {
            ReleaseArtifactVerifier.requirePublishedDigest("echidna-magisk-v1.zip", emptyMap())
        }

        assertTrue(error.message!!.contains("no entry for echidna-magisk-v1.zip"))
    }

    @Test
    fun `a module zip pinned to a different release certificate is rejected`() {
        val zip = moduleZip("module.zip", DIGEST_B)

        val error = assertFails {
            ReleaseArtifactVerifier.requireModuleOrigin(zip, "module.zip", DIGEST_A)
        }

        assertTrue(error.message!!.contains("Origin check failed"))
        assertTrue(error.message!!.contains("pinned to a different release certificate"))
    }

    @Test
    fun `a module zip pinned to the known debug certificate is rejected`() {
        val zip = moduleZip("module.zip", ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE)

        val error = assertFails {
            ReleaseArtifactVerifier.requireModuleOrigin(
                zip,
                "module.zip",
                ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE,
            )
        }

        assertTrue(error.message!!.contains("debug"))
    }

    @Test
    fun `a module zip without the release cert pin is rejected`() {
        val zip = folder.newFile("unpinned.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("module.prop"))
            out.write("id=echidna\n".toByteArray())
            out.closeEntry()
        }

        val error = assertFails { ReleaseArtifactVerifier.requireModuleOrigin(zip, "unpinned.zip", DIGEST_A) }

        assertTrue(error.message!!.contains(ReleaseArtifactVerifier.MODULE_PIN_ENTRY))
    }

    @Test
    fun `a file that is not a zip at all is rejected rather than treated as unpinned`() {
        val notAZip = folder.newFile("broken.zip").apply { writeText("this is not a zip") }

        val error = assertFails { ReleaseArtifactVerifier.requireModuleOrigin(notAZip, "broken.zip", DIGEST_A) }

        assertTrue(error.message!!.contains("could not be read as a zip archive"))
    }

    @Test
    fun `a module zip pinned to this app's certificate is accepted`() {
        val zip = moduleZip("module.zip", DIGEST_A)

        ReleaseArtifactVerifier.requireModuleOrigin(zip, "module.zip", DIGEST_A)
    }

    @Test
    fun `an apk signed by a different certificate is rejected`() {
        val error = assertFails {
            ReleaseArtifactVerifier.requireApkOrigin("companion.apk", DIGEST_B, DIGEST_A)
        }

        assertTrue(error.message!!.contains("signed by a different certificate"))
    }

    @Test
    fun `a debug-signed apk is rejected even when it is the only certificate we know`() {
        val error = assertFails {
            ReleaseArtifactVerifier.requireApkOrigin(
                "companion.apk",
                ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE,
                ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE,
            )
        }

        assertTrue(error.message!!.contains("debug certificate"))
    }

    @Test
    fun `an apk with no readable certificate is rejected`() {
        val error = assertFails { ReleaseArtifactVerifier.requireApkOrigin("companion.apk", null, DIGEST_A) }

        assertTrue(error.message!!.contains("no signing certificate"))
    }

    @Test
    fun `an apk signed by this app's certificate is accepted`() {
        ReleaseArtifactVerifier.requireApkOrigin("companion.apk", DIGEST_A, DIGEST_A)
    }

    @Test
    fun `a debug-signed app cannot act as the trust anchor`() {
        val error = assertFails {
            ReleaseArtifactVerifier.requireTrustAnchor(ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE)
        }

        assertTrue(error.message!!.contains("debug"))
    }

    @Test
    fun `an unreadable or placeholder own certificate cannot act as the trust anchor`() {
        assertTrue(assertFails { ReleaseArtifactVerifier.requireTrustAnchor(null) }.message!!.contains("own signing"))
        assertTrue(assertFails { ReleaseArtifactVerifier.requireTrustAnchor("0".repeat(64)) }.message!!.contains("all-zero"))
        assertTrue(assertFails { ReleaseArtifactVerifier.requireTrustAnchor("nope") }.message!!.contains("valid SHA-256"))
    }

    private fun moduleZip(name: String, pin: String): File {
        val zip = folder.newFile(name)
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("module.prop"))
            out.write("id=echidna\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry(ReleaseArtifactVerifier.MODULE_PIN_ENTRY))
            out.write("$pin\n".toByteArray())
            out.closeEntry()
        }
        return zip
    }

    private fun assertFails(block: () -> Unit): ReleaseArtifactException = try {
        block()
        throw AssertionError("expected the verifier to reject this artifact, but it was accepted")
    } catch (expected: ReleaseArtifactException) {
        expected
    }

    private companion object {
        val DIGEST_A = "11".repeat(32)
        val DIGEST_B = "22".repeat(32)
    }
}
