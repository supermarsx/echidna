package com.echidna.app.system

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

/**
 * Reads signing-certificate digests. Behind an interface so the origin-binding rules in
 * [ReleaseArtifactVerifier] can be tested with known digests instead of real signed APKs, which a
 * JVM unit test cannot produce.
 */
interface ReleaseSigningIdentity {
    /** SHA-256 of the certificate that signed the RUNNING app, or null when it cannot be read. */
    fun ownCertificateSha256(): String?

    /** SHA-256 of the certificate that signed the APK at [path], or null when it cannot be read. */
    fun archiveCertificateSha256(path: String): String?
}

/**
 * PackageManager-backed implementation.
 *
 * On API 28+ the modern `GET_SIGNING_CERTIFICATES` / [android.content.pm.SigningInfo] path is used
 * and a multi-signer artifact is rejected: with several signers there is no single certificate to
 * bind to, and accepting "one of them matches" would let an attacker co-sign their way in. Below 28
 * (minSdk is 26) only the deprecated `GET_SIGNATURES` array exists, and the same one-signer rule is
 * applied to it.
 *
 * Only the CURRENT signer is compared. Rotation lineage is deliberately not accepted here: an
 * artifact signed by a superseded key is not the same origin as the running app for the purposes of
 * a root-adjacent install, and the module pin has no lineage concept at all.
 */
class PackageManagerSigningIdentity(private val context: Context) : ReleaseSigningIdentity {

    override fun ownCertificateSha256(): String? = runCatching {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        certificateDigest(info)
    }.getOrElse { error ->
        Log.w(TAG, "Failed to read this app's signing certificate", error)
        null
    }

    override fun archiveCertificateSha256(path: String): String? = runCatching {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            PackageManager.GET_SIGNATURES
        }
        // getPackageArchiveInfo parses and verifies the APK's signing block; a tampered or unsigned
        // file yields null here rather than a certificate we would then compare.
        val info = manager.getPackageArchiveInfo(path, flags) ?: return@runCatching null
        certificateDigest(info)
    }.getOrElse { error ->
        Log.w(TAG, "Failed to read the signing certificate of $path", error)
        null
    }

    private fun certificateDigest(info: PackageInfo): String? {
        val encoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) return null
            val signers = signingInfo.apkContentsSigners ?: return null
            if (signers.size != 1) return null
            signers[0].toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val signatures = info.signatures ?: return null
            if (signatures.size != 1) return null
            signatures[0].toByteArray()
        }
        if (encoded.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        val out = StringBuilder(digest.size * 2)
        digest.forEach { out.append(String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)) }
        return out.toString()
    }

    private companion object {
        const val TAG = "ReleaseSigningIdentity"
    }
}
