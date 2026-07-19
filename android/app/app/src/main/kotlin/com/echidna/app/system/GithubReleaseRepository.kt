package com.echidna.app.system

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Where the optional "fetch the latest release" flow gets its artifacts. Behind an interface so the
 * install state machine can be driven by a fake in tests, exactly like [EngineInstallController].
 *
 * Both members are suspending and do their own IO dispatching; nothing here touches the main
 * thread. Failures are surfaced as [ReleaseArtifactException] whose message is shown to the user.
 */
interface ReleaseArtifactSource {
    /** Resolves the CURRENT latest release from the API. Never returns a hardcoded tag. */
    suspend fun resolveLatest(): ResolvedRelease

    /**
     * Downloads [asset] from [release], verifies it end to end, and returns a real filesystem path
     * the privileged installer can read. Any failed check deletes the file and throws.
     */
    suspend fun stageForInstall(release: ResolvedRelease, asset: ReleaseAsset): String
}

/**
 * Fetches Echidna release artifacts from GitHub Releases.
 *
 * The latest release is resolved dynamically from `releases/latest` and every asset name comes out
 * of that response — no tag, filename, or download URL is composed or hardcoded here, so the app
 * keeps working across renames and never guesses at an asset that does not exist.
 *
 * Nothing is downloaded automatically: [resolveLatest] and [stageForInstall] are only ever called
 * from an explicit user action, and the resolved tag and asset name are shown before the second
 * step is offered. Nothing is installed automatically either — staging hands back a path, and the
 * existing guided install flow still requires a separate confirmation.
 */
class GithubReleaseRepository(
    private val context: Context,
    private val http: ReleaseHttpClient = HttpsReleaseHttpClient(),
    private val identity: ReleaseSigningIdentity = PackageManagerSigningIdentity(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) : ReleaseArtifactSource {

    override suspend fun resolveLatest(): ResolvedRelease = withContext(io) {
        val body = http.fetchText(latestReleaseUrl, MAX_JSON_BYTES)
        parseRelease(body)
    }

    override suspend fun stageForInstall(
        release: ResolvedRelease,
        asset: ReleaseAsset,
    ): String = withContext(io) {
        // Establish the trust anchor FIRST. If this app's own certificate is unreadable or is the
        // public debug certificate there is nothing to bind a download to, and we refuse before
        // spending a byte of the user's data.
        val ownCertificate = ReleaseArtifactVerifier.requireTrustAnchor(identity.ownCertificateSha256())

        val checksumAsset = release.checksumAsset
            ?: throw ReleaseArtifactException(
                "Checksum check failed: release ${release.tag} publishes no " +
                    "${ResolvedRelease.CHECKSUM_ASSET_NAME}, so downloads from it cannot be verified."
            )
        val checksums = ReleaseArtifactVerifier.parseChecksums(
            http.fetchText(requireHttpsUrl(checksumAsset.downloadUrl).toString(), MAX_CHECKSUM_BYTES)
        )
        val expectedDigest = ReleaseArtifactVerifier.requirePublishedDigest(asset.name, checksums)

        val destination = destinationFor(asset.name)
        try {
            http.download(requireHttpsUrl(asset.downloadUrl).toString(), destination, MAX_ARTIFACT_BYTES)
            ReleaseArtifactVerifier.requireContentDigest(destination, asset.name, expectedDigest)
            verifyOrigin(asset, destination, ownCertificate)
            // World-readable for the same reason EngineModuleArchive stages that way: the install
            // command runs as root out of this app's cache directory.
            runCatching { destination.setReadable(true, false) }
            destination.absolutePath
        } catch (error: Throwable) {
            // Fail closed: a partially downloaded or rejected artifact never survives on disk, and
            // the caller never falls back to the bundled asset behind the user's back.
            runCatching { destination.delete() }
            throw error
        }
    }

    /**
     * Origin binding, per artifact kind. An artifact we have no way to bind is refused outright
     * rather than accepted on its checksum alone — the checksum list travels over the same channel
     * as the artifact, so on its own it proves nothing about who produced it.
     */
    private fun verifyOrigin(asset: ReleaseAsset, file: File, ownCertificate: String) {
        when {
            asset.name.endsWith(".apk", ignoreCase = true) ->
                ReleaseArtifactVerifier.requireApkOrigin(
                    asset.name,
                    identity.archiveCertificateSha256(file.absolutePath),
                    ownCertificate,
                )

            asset.name.startsWith(ResolvedRelease.MODULE_ASSET_PREFIX) ->
                ReleaseArtifactVerifier.requireModuleOrigin(file, asset.name, ownCertificate)

            else -> throw ReleaseArtifactException(
                "Origin check failed: ${asset.name} carries no signing certificate and no " +
                    "${ReleaseArtifactVerifier.MODULE_PIN_ENTRY} pin, so it cannot be bound to " +
                    "this app. The file was deleted."
            )
        }
    }

    private fun destinationFor(assetName: String): File {
        val directory = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        // Downloads live beside — never on top of — the bundled/picked staging path used by
        // EngineModuleArchive, so the offline install path is completely unaffected by this
        // feature. The name is sanitised because it comes from the API response.
        val safeName = assetName.replace(UNSAFE_NAME_CHARS, "_").takeLast(120)
        return File(directory, safeName.ifEmpty { "artifact.bin" })
    }

    private fun parseRelease(body: String): ResolvedRelease {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw ReleaseArtifactException("Release lookup failed: the GitHub API response was not valid JSON.")
        }
        val tag = json.optString("tag_name").trim()
        if (tag.isEmpty()) {
            throw ReleaseArtifactException("Release lookup failed: the GitHub API response carries no tag name.")
        }
        val assetsJson = json.optJSONArray("assets")
        val assets = buildList {
            for (index in 0 until (assetsJson?.length() ?: 0)) {
                val entry = assetsJson?.optJSONObject(index) ?: continue
                val name = entry.optString("name").trim()
                val url = entry.optString("browser_download_url").trim()
                if (name.isEmpty() || url.isEmpty()) continue
                add(ReleaseAsset(name = name, downloadUrl = url, sizeBytes = entry.optLong("size", 0L)))
            }
        }
        if (assets.isEmpty()) {
            throw ReleaseArtifactException("Release lookup failed: release $tag publishes no downloadable assets.")
        }
        return ResolvedRelease(tag = tag, assets = assets)
    }

    companion object {
        /** Resolves whatever is latest right now; the tag is never baked into the app. */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/supermarsx/echidna/releases/latest"

        private const val DOWNLOAD_DIR = "release-downloads"
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val MAX_CHECKSUM_BYTES = 64 * 1024
        private const val MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
