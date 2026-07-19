package com.echidna.app.system

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Honest failure of the optional release-download path. The message is shown to the user verbatim,
 * so every throw site names WHICH check failed rather than collapsing to a generic "download
 * failed" — a silent or vague failure on a root-adjacent install path is worse than no download
 * feature at all.
 */
class ReleaseArtifactException(message: String) : Exception(message)

/** One downloadable asset of a GitHub release, exactly as the API reported it. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * A release resolved from the GitHub API. The tag is whatever the API returned for
 * `releases/latest` — nothing in this app hardcodes a tag, so a new release is picked up without
 * shipping a new build.
 */
data class ResolvedRelease(
    val tag: String,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }

    /**
     * The flashable module archive. Matched by shape (`echidna-magisk-<tag>.zip`) rather than by a
     * composed name, because the tag in the asset name is not guaranteed to be spelled the same way
     * as the release tag (a `v` prefix may or may not be present).
     */
    val moduleAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.startsWith(MODULE_ASSET_PREFIX) && it.name.endsWith(".zip") }

    /** The companion APK, offered as an optional self-update download. */
    val companionAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.startsWith(COMPANION_ASSET_PREFIX) && it.name.endsWith(".apk") }

    val checksumAsset: ReleaseAsset?
        get() = asset(CHECKSUM_ASSET_NAME)

    companion object {
        const val MODULE_ASSET_PREFIX = "echidna-magisk-"
        const val COMPANION_ASSET_PREFIX = "echidna-companion-"
        const val CHECKSUM_ASSET_NAME = "SHA256SUMS.txt"
    }
}

/**
 * The pure verification rules for a downloaded release artifact. Kept free of Android and network
 * types so the whole trust chain is unit-testable without a device or a socket.
 *
 * Two independent properties are checked, and BOTH must hold before anything is handed to the
 * privileged installer:
 *
 *  1. **Integrity** — the file's SHA-256 equals the digest published in `SHA256SUMS.txt` of the
 *     same release. This catches truncation, corruption, and a swapped asset body.
 *  2. **Origin** — the artifact is bound to the certificate that signed the app that is asking for
 *     it. For an APK that is its actual signing certificate; for the Magisk zip (which is NOT
 *     APK-signed, see docs/signing.md) it is the `common/release-cert-sha256` pin that
 *     `tools/build_magisk_module.sh` embeds at build time. Integrity alone would only prove the
 *     file matches a checksum list that arrived over the same channel.
 *
 * The Android debug certificate is rejected outright on both paths. A debug-signed artifact is
 * trivially forgeable by anyone with the public debug keystore, so it must never reach a root
 * install flow over the network, not even when the digests happen to agree.
 */
object ReleaseArtifactVerifier {

    /**
     * Certificate on the public Android debug keystore. Mirrors the same constant in
     * `tools/verify_magisk_module.py`, `tools/check_release_signing.py` and
     * `com.echidna.magisk.SignerPolicy`; keep the four in sync.
     */
    const val KNOWN_DEBUG_CERTIFICATE = "b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b"

    /** Zip entry that `tools/build_magisk_module.sh:155` writes into the flashable module. */
    const val MODULE_PIN_ENTRY = "common/release-cert-sha256"

    /** Largest `common/release-cert-sha256` we will read — the real entry is 65 bytes. */
    private const val MAX_PIN_BYTES = 512

    /** Streams [file] and returns its lowercase hex SHA-256. Callers run this off the main thread. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Normalizes a certificate/checksum digest to 64 lowercase hex characters, rejecting the
     * all-zero placeholder that the build tooling uses to mean "no pin provisioned". Same rule as
     * `SignerPolicy.requireNormalizedDigest`, so a placeholder can never satisfy a comparison.
     */
    fun normalizeDigest(value: String?, label: String): String {
        val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (!normalized.matches(HEX_64)) {
            throw ReleaseArtifactException(
                "$label is not a valid SHA-256 digest (expected 64 hex characters). Refusing to " +
                    "use this artifact."
            )
        }
        if (normalized.all { it == '0' }) {
            throw ReleaseArtifactException(
                "$label is the all-zero placeholder digest, which means no certificate pin was " +
                    "provisioned. Refusing to use this artifact."
            )
        }
        return normalized
    }

    /**
     * Parses a `sha256sum`-style listing into filename -> digest. Lines are `<digest>  <name>`;
     * the binary-mode `*` marker and directory prefixes are tolerated because the release job's
     * working directory is not part of the contract. Unparseable lines are ignored rather than
     * failing the whole file, but a missing entry for the artifact we care about is still a hard
     * failure in [requirePublishedDigest].
     */
    fun parseChecksums(text: String): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val separator = line.indexOfFirst { it == ' ' || it == '\t' }
            if (separator <= 0) return@forEach
            val digest = line.substring(0, separator).lowercase(Locale.ROOT)
            if (!digest.matches(HEX_64)) return@forEach
            val name = line.substring(separator).trim().removePrefix("*").substringAfterLast('/')
            if (name.isNotEmpty()) entries[name] = digest
        }
        return entries
    }

    /** Returns the published digest for [assetName], failing closed when the list has no entry. */
    fun requirePublishedDigest(assetName: String, checksums: Map<String, String>): String {
        val published = checksums[assetName]
            ?: throw ReleaseArtifactException(
                "Checksum check failed: ${ResolvedRelease.CHECKSUM_ASSET_NAME} for this release " +
                    "has no entry for $assetName, so the download cannot be verified."
            )
        return normalizeDigest(published, "The published checksum for $assetName")
    }

    /** Hard-fails when [file]'s SHA-256 differs from the digest published for [assetName]. */
    fun requireContentDigest(file: File, assetName: String, expectedDigest: String) {
        val actual = sha256(file)
        if (!digestsEqual(actual, expectedDigest)) {
            throw ReleaseArtifactException(
                "Checksum check failed: $assetName does not match the SHA-256 published in " +
                    "${ResolvedRelease.CHECKSUM_ASSET_NAME} for this release. Expected " +
                    "$expectedDigest but the downloaded file hashes to $actual. The file was deleted."
            )
        }
    }

    /**
     * Validates the running app's own signing certificate digest before it is used as the trust
     * anchor. A debug-signed build has no meaningful origin to bind to — the debug key is public —
     * so the download path is refused entirely rather than pinning to a forgeable certificate.
     */
    fun requireTrustAnchor(ownCertificate: String?): String {
        if (ownCertificate == null) {
            throw ReleaseArtifactException(
                "Origin check failed: this app's own signing certificate could not be read, so a " +
                    "downloaded artifact cannot be bound to it."
            )
        }
        val normalized = normalizeDigest(ownCertificate, "This app's signing certificate")
        if (digestsEqual(normalized, KNOWN_DEBUG_CERTIFICATE)) {
            throw ReleaseArtifactException(
                "Origin check failed: this build is signed with the public Android debug " +
                    "certificate, which anyone can forge. Downloading release artifacts is " +
                    "disabled for debug builds — install a release build, or use the bundled " +
                    "package / a locally verified .zip."
            )
        }
        return normalized
    }

    /**
     * Origin check for a downloaded APK: its signing certificate must be the certificate that
     * signed the app performing the download. [archiveCertificate] is the digest read from the
     * downloaded file (PackageManager on device); it is supplied by the caller so this rule stays
     * testable without a real APK.
     */
    fun requireApkOrigin(assetName: String, archiveCertificate: String?, ownCertificate: String) {
        if (archiveCertificate == null) {
            throw ReleaseArtifactException(
                "Origin check failed: no signing certificate could be read from $assetName. An " +
                    "unsigned or unparseable APK is never installed."
            )
        }
        val normalized = normalizeDigest(archiveCertificate, "The signing certificate of $assetName")
        if (digestsEqual(normalized, KNOWN_DEBUG_CERTIFICATE)) {
            throw ReleaseArtifactException(
                "Origin check failed: $assetName is signed with the public Android debug " +
                    "certificate. A debug-signed artifact is never installed from the network. " +
                    "The file was deleted."
            )
        }
        if (!digestsEqual(normalized, ownCertificate)) {
            throw ReleaseArtifactException(
                "Origin check failed: $assetName is signed by a different certificate than this " +
                    "app. Expected $ownCertificate but the download is signed by $normalized. " +
                    "The file was deleted."
            )
        }
    }

    /**
     * Origin check for the flashable module. The Magisk zip is not APK-signed (docs/signing.md), so
     * the binding is the `common/release-cert-sha256` entry the module build embeds: it must name
     * the same certificate that signed this app. That is exactly the pin the on-device trust helper
     * enforces at runtime, so a zip whose pin disagrees would refuse to trust this app anyway.
     */
    fun requireModuleOrigin(file: File, assetName: String, ownCertificate: String) {
        val pin = readModulePin(file, assetName)
        val normalized = normalizeDigest(pin, "The $MODULE_PIN_ENTRY pin inside $assetName")
        if (digestsEqual(normalized, KNOWN_DEBUG_CERTIFICATE)) {
            throw ReleaseArtifactException(
                "Origin check failed: $assetName is pinned to the public Android debug " +
                    "certificate. A debug-pinned module is never installed from the network. " +
                    "The file was deleted."
            )
        }
        if (!digestsEqual(normalized, ownCertificate)) {
            throw ReleaseArtifactException(
                "Origin check failed: $assetName is pinned to a different release certificate " +
                    "than the one that signed this app. Expected $ownCertificate but the module " +
                    "carries $normalized. The file was deleted."
            )
        }
    }

    /** Reads [MODULE_PIN_ENTRY] out of the flashable zip, failing closed when it is absent. */
    private fun readModulePin(file: File, assetName: String): String {
        val raw = runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MODULE_PIN_ENTRY) ?: return@use null
                // Bounded read: the entry is a single 64-hex line, so a huge or nested entry is a
                // sign of a tampered archive rather than something to stream into memory.
                zip.getInputStream(entry).use { input ->
                    String(input.readAtMost(MAX_PIN_BYTES), StandardCharsets.US_ASCII)
                }
            }
        }.getOrElse {
            throw ReleaseArtifactException(
                "Origin check failed: $assetName could not be read as a zip archive, so its " +
                    "$MODULE_PIN_ENTRY certificate pin could not be checked. The file was deleted."
            )
        }
        return raw ?: throw ReleaseArtifactException(
            "Origin check failed: $assetName does not contain $MODULE_PIN_ENTRY, so it cannot be " +
                "bound to this app's signing certificate. The file was deleted."
        )
    }

    /** Constant-time digest comparison, matching `SignerPolicy.constantTimeHexEquals`. */
    private fun digestsEqual(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.US_ASCII),
        right.toByteArray(StandardCharsets.US_ASCII),
    )

    private val HEX_64 = Regex("[0-9a-f]{64}")

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        forEach { out.append(String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)) }
        return out.toString()
    }
}

/** Reads at most [limit] bytes; anything beyond is dropped rather than buffered. */
internal fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = read(buffer, filled, limit - filled)
        if (read <= 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}
