package com.echidna.app.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end cover for the download path with a faked transport — no test here touches the network.
 * The point of these cases is the fail-closed behaviour: every rejection must leave nothing staged
 * on disk, and the accepted case must have passed BOTH the checksum and the origin binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GithubReleaseRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the latest tag and asset names come from the API response, never from a constant`() {
        val http = FakeHttp().withRelease(tag = "v9.9.9-unreleased")

        val release = runBlocking { repository(http).resolveLatest() }

        assertEquals("v9.9.9-unreleased", release.tag)
        assertEquals("echidna-magisk-v9.9.9-unreleased.zip", release.moduleAsset?.name)
        assertEquals("echidna-companion-v9.9.9-unreleased.apk", release.companionAsset?.name)
        assertTrue(release.checksumAsset != null)
    }

    @Test
    fun `a correctly pinned module zip with a matching checksum is staged`() {
        val http = FakeHttp().withRelease()
        val module = moduleZipBytes(OWN_CERT)
        http.publish(MODULE_ASSET, module)
        val repository = repository(http)

        val release = runBlocking { repository.resolveLatest() }
        val path = runBlocking { repository.stageForInstall(release, release.moduleAsset!!) }

        val staged = File(path)
        assertTrue(staged.isFile)
        assertEquals(MODULE_ASSET, staged.name)
        assertTrue(staged.readBytes().contentEquals(module))
    }

    @Test
    fun `a checksum mismatch is rejected and the downloaded file is deleted`() {
        val http = FakeHttp().withRelease()
        // Published digest describes the honest artifact; the served body is something else.
        http.publish(MODULE_ASSET, moduleZipBytes(OWN_CERT))
        http.serve(MODULE_ASSET, "tampered payload".toByteArray())
        val repository = repository(http)

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("Checksum check failed"))
        assertFalse(downloadedFile(MODULE_ASSET).exists())
    }

    @Test
    fun `a module zip pinned to a foreign release certificate is rejected and deleted`() {
        val http = FakeHttp().withRelease()
        http.publish(MODULE_ASSET, moduleZipBytes(OTHER_CERT))
        val repository = repository(http)

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("Origin check failed"))
        assertFalse(downloadedFile(MODULE_ASSET).exists())
    }

    @Test
    fun `a debug-pinned module zip is rejected`() {
        val http = FakeHttp().withRelease()
        http.publish(MODULE_ASSET, moduleZipBytes(ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE))
        val repository = repository(http)

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("debug"))
        assertFalse(downloadedFile(MODULE_ASSET).exists())
    }

    @Test
    fun `an apk signed by a foreign certificate is rejected and deleted`() {
        val http = FakeHttp().withRelease()
        http.publish(APK_ASSET, "apk-body".toByteArray())
        val repository = repository(http, identity = FakeIdentity(own = OWN_CERT, archive = OTHER_CERT))

        val error = rejects(repository, APK_ASSET)

        assertTrue(error.message!!.contains("signed by a different certificate"))
        assertFalse(downloadedFile(APK_ASSET).exists())
    }

    @Test
    fun `a debug-signed apk is rejected`() {
        val http = FakeHttp().withRelease()
        http.publish(APK_ASSET, "apk-body".toByteArray())
        val repository = repository(
            http,
            identity = FakeIdentity(own = OWN_CERT, archive = ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE),
        )

        val error = rejects(repository, APK_ASSET)

        assertTrue(error.message!!.contains("debug certificate"))
        assertFalse(downloadedFile(APK_ASSET).exists())
    }

    @Test
    fun `an apk signed by this app's own certificate is staged`() {
        val http = FakeHttp().withRelease()
        http.publish(APK_ASSET, "apk-body".toByteArray())
        val repository = repository(http, identity = FakeIdentity(own = OWN_CERT, archive = OWN_CERT))

        val release = runBlocking { repository.resolveLatest() }
        val path = runBlocking { repository.stageForInstall(release, release.asset(APK_ASSET)!!) }

        assertTrue(File(path).isFile)
    }

    @Test
    fun `a non-HTTPS asset URL is refused before anything is downloaded`() {
        val http = FakeHttp().withRelease(moduleUrl = "http://github.com/supermarsx/echidna/$MODULE_ASSET")
        http.publish(MODULE_ASSET, moduleZipBytes(OWN_CERT))
        val repository = repository(http)

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("not HTTPS"))
        assertTrue(http.downloaded.isEmpty())
    }

    @Test
    fun `an asset URL pointing off GitHub is refused before anything is downloaded`() {
        val http = FakeHttp().withRelease(moduleUrl = "https://evil.example.com/$MODULE_ASSET")
        http.publish(MODULE_ASSET, moduleZipBytes(OWN_CERT))
        val repository = repository(http)

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("not a GitHub release host"))
        assertTrue(http.downloaded.isEmpty())
    }

    @Test
    fun `an artifact with no certificate and no pin is refused even with a valid checksum`() {
        val http = FakeHttp().withRelease()
        http.publish(NATIVE_ASSET, "native-libs".toByteArray())
        val repository = repository(http)

        val error = rejects(repository, NATIVE_ASSET)

        assertTrue(error.message!!.contains("cannot be bound to this app"))
        assertFalse(downloadedFile(NATIVE_ASSET).exists())
    }

    @Test
    fun `a release without a checksum list cannot be downloaded from at all`() {
        val http = FakeHttp().withRelease(includeChecksums = false)
        http.serve(MODULE_ASSET, moduleZipBytes(OWN_CERT))
        val repository = repository(http)

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("SHA256SUMS.txt"))
        assertTrue(http.downloaded.isEmpty())
    }

    @Test
    fun `a debug-signed app refuses to download anything at all`() {
        val http = FakeHttp().withRelease()
        http.publish(MODULE_ASSET, moduleZipBytes(OWN_CERT))
        val repository = repository(
            http,
            identity = FakeIdentity(own = ReleaseArtifactVerifier.KNOWN_DEBUG_CERTIFICATE),
        )

        val error = rejects(repository, MODULE_ASSET)

        assertTrue(error.message!!.contains("debug"))
        assertTrue(http.downloaded.isEmpty())
    }

    private fun rejects(repository: GithubReleaseRepository, assetName: String): ReleaseArtifactException {
        val release = runBlocking { repository.resolveLatest() }
        val asset = release.asset(assetName) ?: throw AssertionError("test release has no $assetName")
        return try {
            runBlocking { repository.stageForInstall(release, asset) }
            throw AssertionError("expected $assetName to be rejected, but it was staged")
        } catch (expected: ReleaseArtifactException) {
            expected
        }
    }

    private fun repository(
        http: FakeHttp,
        identity: ReleaseSigningIdentity = FakeIdentity(own = OWN_CERT),
    ) = GithubReleaseRepository(
        context = context,
        http = http,
        identity = identity,
        io = Dispatchers.Unconfined,
        latestReleaseUrl = FakeHttp.API_URL,
    )

    private fun downloadedFile(assetName: String) = File(File(context.cacheDir, "release-downloads"), assetName)

    /**
     * Transport stub. [publish] serves a body AND lists its real digest in SHA256SUMS.txt (the
     * honest case); [serve] replaces only the body, which is how the tampering cases are built.
     */
    private class FakeHttp : ReleaseHttpClient {
        private val bodies = LinkedHashMap<String, ByteArray>()
        private val checksums = LinkedHashMap<String, String>()
        private var releaseJson: String = ""
        private var checksumsPublished = true
        val downloaded = mutableListOf<String>()

        fun withRelease(
            tag: String = TAG,
            moduleUrl: String = assetUrl(MODULE_ASSET),
            includeChecksums: Boolean = true,
        ) = apply {
            checksumsPublished = includeChecksums
            val assets = buildList {
                add(""""name":"echidna-magisk-$tag.zip","browser_download_url":"$moduleUrl","size":1024""")
                add(""""name":"echidna-companion-$tag.apk","browser_download_url":"${assetUrl(APK_ASSET)}","size":2048""")
                add(""""name":"echidna-native-libs-$tag.zip","browser_download_url":"${assetUrl(NATIVE_ASSET)}","size":512""")
                if (includeChecksums) {
                    add(""""name":"SHA256SUMS.txt","browser_download_url":"${assetUrl(CHECKSUMS)}","size":128""")
                }
            }.joinToString(",") { "{$it}" }
            releaseJson = """{"tag_name":"$tag","assets":[$assets]}"""
        }

        fun publish(assetName: String, body: ByteArray) {
            serve(assetName, body)
            checksums[assetName] = sha256Hex(body)
        }

        fun serve(assetName: String, body: ByteArray) {
            bodies[assetUrl(assetName)] = body
        }

        override fun fetchText(url: String, maxBytes: Int): String = when (url) {
            API_URL -> releaseJson
            assetUrl(CHECKSUMS) -> {
                if (!checksumsPublished) throw ReleaseArtifactException("no checksum list published")
                checksums.entries.joinToString("\n") { "${it.value}  ${it.key}" }
            }
            else -> throw ReleaseArtifactException("unexpected text fetch: $url")
        }

        override fun download(url: String, destination: File, maxBytes: Long): Long {
            downloaded += url
            val body = bodies[url] ?: throw ReleaseArtifactException("unexpected download: $url")
            destination.writeBytes(body)
            return body.size.toLong()
        }

        companion object {
            const val API_URL = "https://api.github.com/repos/supermarsx/echidna/releases/latest"
            fun assetUrl(name: String) = "https://github.com/supermarsx/echidna/releases/download/$TAG/$name"
        }
    }

    private class FakeIdentity(
        private val own: String?,
        private val archive: String? = null,
    ) : ReleaseSigningIdentity {
        override fun ownCertificateSha256(): String? = own
        override fun archiveCertificateSha256(path: String): String? = archive
    }

    private companion object {
        const val TAG = "v1.4.0"
        const val MODULE_ASSET = "echidna-magisk-$TAG.zip"
        const val APK_ASSET = "echidna-companion-$TAG.apk"
        const val NATIVE_ASSET = "echidna-native-libs-$TAG.zip"
        const val CHECKSUMS = "SHA256SUMS.txt"
        val OWN_CERT = "ab".repeat(32)
        val OTHER_CERT = "cd".repeat(32)

        fun sha256Hex(body: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(body)
            val out = StringBuilder(digest.size * 2)
            digest.forEach { out.append(String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)) }
            return out.toString()
        }

        fun moduleZipBytes(pin: String): ByteArray {
            val buffer = ByteArrayOutputStream()
            ZipOutputStream(buffer).use { out ->
                out.putNextEntry(ZipEntry("module.prop"))
                out.write("id=echidna\n".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry(ReleaseArtifactVerifier.MODULE_PIN_ENTRY))
                out.write("$pin\n".toByteArray())
                out.closeEntry()
            }
            return buffer.toByteArray()
        }
    }
}
