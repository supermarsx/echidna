package com.echidna.control.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

private val SIGNING_DIGEST_PATTERN = Regex("[0-9a-f]{64}")
private val IDENTITY_KEYS = setOf("packageName", "uid", "userId", "signingSha256")

/**
 * Package identity captured when a policy entry is published.
 *
 * Android processes that share a UID also share an application sandbox and are one trust domain.
 * PID is deliberately absent: it identifies one connection incarnation, not an install identity.
 */
internal data class PublishedAppIdentity(
    val packageName: String,
    val uid: Int,
    val userId: Int,
    val signingSha256: List<String>,
) {
    fun isValid(): Boolean =
        isPolicyPackageName(packageName) &&
            uid >= 0 &&
            userId >= 0 &&
            userId == androidUserId(uid) &&
            signingSha256.isNotEmpty() &&
            signingSha256.size <= MAX_PUBLISHED_SIGNERS &&
            signingSha256 == signingSha256.distinct().sorted() &&
            signingSha256.all(SIGNING_DIGEST_PATTERN::matches)

    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("uid", uid)
        .put("userId", userId)
        .put("signingSha256", JSONArray(signingSha256))

    companion object {
        fun parse(mapKey: String, root: JSONObject): PublishedAppIdentity? {
            if (!hasExactlyKeys(root, IDENTITY_KEYS)) return null
            val packageName = root.opt("packageName") as? String ?: return null
            if (packageName != mapKey) return null
            val uid = exactInt(root.opt("uid")) ?: return null
            val userId = exactInt(root.opt("userId")) ?: return null
            val signatures = root.optJSONArray("signingSha256") ?: return null
            if (signatures.length() !in 1..MAX_PUBLISHED_SIGNERS) return null
            val signingSha256 = ArrayList<String>(signatures.length())
            for (index in 0 until signatures.length()) {
                signingSha256 += signatures.opt(index) as? String ?: return null
            }
            return PublishedAppIdentity(packageName, uid, userId, signingSha256)
                .takeIf(PublishedAppIdentity::isValid)
        }
    }
}

internal fun interface PublishedAppIdentityResolver {
    fun resolve(packageName: String): PublishedAppIdentity?
}

internal object MissingPublishedAppIdentityResolver : PublishedAppIdentityResolver {
    override fun resolve(packageName: String): PublishedAppIdentity? = null
}

/** Resolves only packages currently visible to this exact Android user/app instance. */
internal class AndroidPublishedAppIdentityResolver(context: Context) :
    PublishedAppIdentityResolver {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun resolve(packageName: String): PublishedAppIdentity? {
        if (!isPolicyPackageName(packageName)) return null
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                    ),
                )
            } else {
                val flags = if (Build.VERSION.SDK_INT >= 28) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
                packageManager.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        if (packageInfo.packageName != packageName) return null
        val uid = packageInfo.applicationInfo?.uid ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }.orEmpty()
        val digests = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.distinct().sorted()
        return PublishedAppIdentity(
            packageName = packageName,
            uid = uid,
            userId = androidUserId(uid),
            signingSha256 = digests,
        ).takeIf(PublishedAppIdentity::isValid)
    }
}

internal data class PublishedProcessIdentityBinding(
    val generation: Long,
    val processName: String,
    val identity: PublishedAppIdentity,
)

internal object PublishedProcessIdentityAuthorizer {
    fun authorize(
        policy: VersionedPolicyEnvelope,
        peerUid: Int,
        processName: String?,
        resolver: PublishedAppIdentityResolver,
    ): PublishedProcessIdentityBinding? {
        val process = processName?.takeIf(::isPolicyProcessName) ?: return null
        val packageName = policyProcessBase(process)
        val published = policy.envelope.appIdentities[packageName] ?: return null
        if (
            policy.envelope.whitelist[process] == null &&
            policy.envelope.whitelist[packageName] == null
        ) {
            return null
        }
        if (
            published.uid != peerUid ||
            published.userId != androidUserId(peerUid)
        ) {
            return null
        }
        val current = resolver.resolve(packageName) ?: return null
        if (current != published) return null
        return PublishedProcessIdentityBinding(policy.generation, process, published)
    }

    fun matches(
        policy: VersionedPolicyEnvelope,
        binding: PublishedProcessIdentityBinding?,
    ): Boolean {
        binding ?: return false
        val process = binding.processName
        if (!isPolicyProcessName(process)) return false
        val packageName = policyProcessBase(process)
        if (policy.envelope.appIdentities[packageName] != binding.identity) return false
        return policy.envelope.whitelist.containsKey(process) ||
            policy.envelope.whitelist.containsKey(packageName)
    }
}

internal fun revalidatePublishedAppIdentities(
    envelope: PolicyEnvelope,
    resolver: PublishedAppIdentityResolver,
): LinkedHashMap<String, PublishedAppIdentity> {
    val identities = linkedMapOf<String, PublishedAppIdentity>()
    envelope.appIdentities.toSortedMap().forEach { (packageName, persisted) ->
        if (
            envelope.whitelist.keys.any { policyProcessBase(it) == packageName } &&
            resolver.resolve(packageName) == persisted
        ) {
            identities[packageName] = persisted
        }
    }
    return identities
}

private fun exactInt(value: Any?): Int? {
    val number = value as? Number ?: return null
    val asDouble = number.toDouble()
    val asLong = number.toLong()
    return asLong.toInt().takeIf {
        asDouble.isFinite() && asDouble == asLong.toDouble() && asLong == it.toLong()
    }
}

private fun hasExactlyKeys(root: JSONObject, allowed: Set<String>): Boolean {
    if (root.length() != allowed.size) return false
    val keys = root.keys()
    while (keys.hasNext()) {
        if (keys.next() !in allowed) return false
    }
    return true
}

private const val MAX_PUBLISHED_SIGNERS = 16
private const val ANDROID_UIDS_PER_USER = 100_000

internal fun androidUserId(uid: Int): Int = if (uid < 0) -1 else uid / ANDROID_UIDS_PER_USER
