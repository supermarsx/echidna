package com.echidna.control.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedAppIdentityTest {
    @Test
    fun `foreign uid is authorized from published install identity for exact and base routes`() {
        val identity = identity(PACKAGE, UID)
        val resolver = PublishedAppIdentityResolver { requested ->
            identity.takeIf { requested == PACKAGE }
        }
        val policy = policy(identity)

        assertNotNull(
            PublishedProcessIdentityAuthorizer.authorize(policy, UID, PACKAGE, resolver),
        )
        assertNotNull(
            PublishedProcessIdentityAuthorizer.authorize(
                policy,
                UID,
                "$PACKAGE:worker",
                resolver,
            ),
        )
        assertNotNull(
            PublishedProcessIdentityAuthorizer.authorize(
                policy,
                UID,
                "$PACKAGE:unpublished",
                resolver,
            ),
        )
        val exactOnly = policy.copy(
            envelope = policy.envelope.copy(
                whitelist = linkedMapOf("$PACKAGE:worker" to false),
            ),
        )
        assertNull(
            PublishedProcessIdentityAuthorizer.authorize(
                exactOnly,
                UID,
                "$PACKAGE:unpublished",
                resolver,
            ),
        )
        assertNull(
            PublishedProcessIdentityAuthorizer.authorize(
                policy,
                UID,
                "com.example.attacker",
                resolver,
            ),
        )
    }

    @Test
    fun `full android uid prevents cross-user reuse of the same app id`() {
        val workProfileUid = 10 * 100_000 + UID
        val workIdentity = identity(PACKAGE, workProfileUid)
        val policy = policy(workIdentity)
        val resolver = PublishedAppIdentityResolver { workIdentity }

        assertEquals(10, workIdentity.userId)
        assertNotNull(
            PublishedProcessIdentityAuthorizer.authorize(
                policy,
                workProfileUid,
                PACKAGE,
                resolver,
            ),
        )
        assertNull(
            PublishedProcessIdentityAuthorizer.authorize(policy, UID, PACKAGE, resolver),
        )
    }

    @Test
    fun `uid signing reinstall removal and visibility drift all fail closed`() {
        val published = identity(PACKAGE, UID)
        val policy = policy(published)
        var current: PublishedAppIdentity? = published
        val resolver = PublishedAppIdentityResolver { current }

        assertNotNull(
            PublishedProcessIdentityAuthorizer.authorize(policy, UID, PACKAGE, resolver),
        )

        current = identity(PACKAGE, UID + 1)
        assertNull(PublishedProcessIdentityAuthorizer.authorize(policy, UID, PACKAGE, resolver))

        current = published.copy(signingSha256 = listOf("22".repeat(32)))
        assertNull(PublishedProcessIdentityAuthorizer.authorize(policy, UID, PACKAGE, resolver))

        current = null
        assertNull(PublishedProcessIdentityAuthorizer.authorize(policy, UID, PACKAGE, resolver))
    }

    @Test
    fun `shared uid packages are separate claims inside one android trust domain`() {
        val sibling = "com.example.sibling"
        val recorderIdentity = identity(PACKAGE, UID)
        val siblingIdentity = identity(sibling, UID)
        val base = policy(recorderIdentity)
        val sharedUidPolicy = base.copy(
            envelope = base.envelope.copy(
                whitelist = LinkedHashMap(base.envelope.whitelist).apply {
                    put(sibling, true)
                },
                appIdentities = LinkedHashMap(base.envelope.appIdentities).apply {
                    put(sibling, siblingIdentity)
                },
            ),
        )
        val resolver = PublishedAppIdentityResolver { packageName ->
            when (packageName) {
                PACKAGE -> recorderIdentity
                sibling -> siblingIdentity
                else -> null
            }
        }

        assertNotNull(
            PublishedProcessIdentityAuthorizer.authorize(
                sharedUidPolicy,
                UID,
                sibling,
                resolver,
            ),
        )
        assertNull(
            PublishedProcessIdentityAuthorizer.authorize(
                policy(recorderIdentity),
                UID,
                sibling,
                resolver,
            ),
        )
    }

    @Test
    fun `old unbound stores cannot activate and transport views omit private identity metadata`() {
        val published = policy(identity(PACKAGE, UID))
        val encoded = PolicyEnvelopeCodec.encode(published.envelope, published.generation)!!
        assertNotNull(PolicyEnvelopeCodec.parsePublished(encoded))

        val oldUnbound = JSONObject(encoded).apply { remove("appIdentities") }.toString()
        assertNull(PolicyEnvelopeCodec.parsePublished(oldUnbound))
        assertNotNull(PolicyEnvelopeCodec.parseUnboundPublished(oldUnbound))

        val transport = PolicyEnvelopeCodec.encodeScopedForProcess(
            published,
            PACKAGE,
            "$PACKAGE:worker",
        )!!
        assertFalse(JSONObject(transport).has("appIdentities"))
    }

    @Test
    fun `published identity json rejects mismatched keys and noncanonical signers`() {
        val valid = identity(PACKAGE, UID)
        assertTrue(valid.isValid())
        assertNull(PublishedAppIdentity.parse("com.example.other", valid.toJson()))
        assertFalse(valid.copy(signingSha256 = listOf("AA".repeat(32))).isValid())
        assertFalse(
            valid.copy(signingSha256 = listOf(SIGNING_DIGEST, SIGNING_DIGEST)).isValid(),
        )
    }

    private fun policy(identity: PublishedAppIdentity): VersionedPolicyEnvelope =
        VersionedPolicyEnvelope(
            generation = GENERATION,
            envelope = PolicyEnvelope(
                profiles = linkedMapOf(
                    "default" to JSONObject("{\"engine\":{},\"modules\":[]}"),
                ),
                defaultProfileId = "default",
                appBindings = linkedMapOf(),
                whitelist = linkedMapOf(
                    PACKAGE to true,
                    "$PACKAGE:worker" to false,
                ),
                captureOwners = linkedMapOf(PACKAGE to "zygisk"),
                control = PolicyControl(
                    masterEnabled = true,
                    bypass = false,
                    panicUntilEpochMs = 0L,
                    sidetoneEnabled = false,
                    sidetoneGainDb = 0.0,
                    engineMode = "native_first",
                ),
                appIdentities = linkedMapOf(PACKAGE to identity),
            ),
        )

    private fun identity(packageName: String, uid: Int): PublishedAppIdentity =
        PublishedAppIdentity(
            packageName = packageName,
            uid = uid,
            userId = androidUserId(uid),
            signingSha256 = listOf(SIGNING_DIGEST),
        )

    companion object {
        private const val PACKAGE = "com.example.recorder"
        private const val UID = 10_123
        private const val GENERATION = 7L
        private val SIGNING_DIGEST = "11".repeat(32)
    }
}
