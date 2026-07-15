package com.echidna.control.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyScopeTest {
    @Before
    fun setUp() {
        PublishedPolicyRegistry.resetForTests()
    }

    @After
    fun tearDown() {
        PublishedPolicyRegistry.resetForTests()
    }

    @Test
    fun `socket scope exposes only packages proven by peer uid`() {
        val published = PolicyEnvelopeCodec.parsePublished(envelope(7L).toString())!!

        val scoped = JSONObject(
            PolicyEnvelopeCodec.encodeScopedForPackages(
                published,
                setOf("com.example.recorder"),
            )!!,
        )

        assertTrue(scoped.getJSONObject("profiles").has("default"))
        assertTrue(scoped.getJSONObject("profiles").has("recorder"))
        assertFalse(scoped.getJSONObject("profiles").has("other"))
        assertTrue(scoped.getJSONObject("whitelist").has("com.example.recorder:remote"))
        assertFalse(scoped.getJSONObject("whitelist").has("com.example.other"))
        assertEquals(
            "lsposed",
            scoped.getJSONObject("captureOwners").getString("com.example.recorder:remote"),
        )
        assertNull(
            PolicyEnvelopeCodec.encodeScopedForPackages(
                published,
                setOf("com.example.unknown"),
            ),
        )
    }

    @Test
    fun `binder process scope keeps exact and base overrides only`() {
        val published = PolicyEnvelopeCodec.parsePublished(envelope(8L).toString())!!
        val scoped = JSONObject(
            PolicyEnvelopeCodec.encodeScopedForProcess(
                published,
                "com.example.recorder",
                "com.example.recorder:remote",
            )!!,
        )

        assertTrue(scoped.getJSONObject("whitelist").getBoolean("com.example.recorder"))
        assertFalse(scoped.getJSONObject("whitelist").getBoolean("com.example.recorder:remote"))
        assertEquals(
            "lsposed",
            scoped.getJSONObject("captureOwners").getString("com.example.recorder:remote"),
        )
        assertFalse(scoped.getJSONObject("whitelist").has("com.example.other"))
    }

    @Test
    fun `binder caller cannot impersonate a package outside its uid`() {
        assertEquals(
            "com.example.recorder",
            CallerPolicyAuthorizer.authorize(
                listOf("com.example.recorder"),
                "com.example.recorder:remote",
            ),
        )
        assertNull(
            CallerPolicyAuthorizer.authorize(
                listOf("com.attacker"),
                "com.example.recorder:remote",
            ),
        )
        assertNull(
            CallerPolicyAuthorizer.authorize(
                listOf("com.example.recorder"),
                "com.example.recorder/../../other",
            ),
        )

        val running = listOf(
            CallerPolicyAuthorizer.RunningProcess(
                pid = 42,
                uid = 10_000,
                processName = "com.example.recorder:remote",
                packageNames = setOf("com.example.recorder"),
            ),
        )
        assertEquals(
            "com.example.recorder",
            CallerPolicyAuthorizer.authorizeCapability(
                10_000,
                42,
                listOf("com.example.recorder"),
                running,
                "com.example.recorder:remote",
            ),
        )
        assertNull(
            CallerPolicyAuthorizer.authorizeCapability(
                10_000,
                43,
                listOf("com.example.recorder"),
                running,
                "com.example.recorder:remote",
            ),
        )
    }

    @Test
    fun `capability policy is current scoped and control gated`() {
        assertTrue(PublishedPolicyRegistry.publish(envelope(10L).toString()))
        assertNull(
            PublishedPolicyRegistry.capabilityForProcess(
                "com.example.recorder",
                "com.example.recorder",
                nowEpochMs = 1_000L,
            ),
        )

        val lsposedOwned = envelope(11L)
        lsposedOwned.getJSONObject("captureOwners").put("com.example.recorder", "lsposed")
        assertTrue(PublishedPolicyRegistry.publish(lsposedOwned.toString()))
        val current = PublishedPolicyRegistry.capabilityForProcess(
            "com.example.recorder",
            "com.example.recorder",
            nowEpochMs = 1_000L,
        )
        assertEquals(11L, current!!.generation)
        assertTrue(String(current.preset).contains("recorder"))
        assertNull(
            PublishedPolicyRegistry.capabilityForProcess(
                "com.example.recorder",
                "com.example.recorder:remote",
                1_000L,
            ),
        )

        val bypassed = envelope(12L)
        bypassed.getJSONObject("control").put("bypass", true)
        assertTrue(PublishedPolicyRegistry.publish(bypassed.toString()))
        assertNull(
            PublishedPolicyRegistry.capabilityForProcess(
                "com.example.recorder",
                "com.example.recorder",
                1_000L,
            ),
        )
    }

    @Test
    fun `registry preserves generation watermark and exact idempotence`() {
        val generationSeven = envelope(7L).toString()
        assertTrue(PublishedPolicyRegistry.publish(generationSeven))
        assertTrue(PublishedPolicyRegistry.publish(generationSeven))
        assertFalse(PublishedPolicyRegistry.publish(envelope(6L).toString()))

        val conflict = envelope(7L)
        conflict.getJSONObject("control").put("bypass", true)
        assertFalse(PublishedPolicyRegistry.publish(conflict.toString()))
        assertEquals(7L, PublishedPolicyRegistry.generation())
    }

    private fun envelope(generation: Long): JSONObject {
        val profiles = JSONObject()
            .put("default", preset("default"))
            .put("recorder", preset("recorder"))
            .put("other", preset("other"))
        return JSONObject()
            .put("schemaVersion", POLICY_SCHEMA_VERSION)
            .put("generation", generation)
            .put("profiles", profiles)
            .put("defaultProfileId", "default")
            .put(
                "appBindings",
                JSONObject()
                    .put("com.example.recorder", "recorder")
                    .put("com.example.other", "other"),
            )
            .put(
                "whitelist",
                JSONObject()
                    .put("com.example.recorder", true)
                    .put("com.example.recorder:remote", false)
                    .put("com.example.other", true),
            )
            .put(
                "captureOwners",
                JSONObject()
                    .put("com.example.recorder", "zygisk")
                    .put("com.example.recorder:remote", "lsposed")
                    .put("com.example.other", "zygisk"),
            )
            .put(
                "control",
                JSONObject()
                    .put("masterEnabled", true)
                    .put("bypass", false)
                    .put("panicUntilEpochMs", 0L)
                    .put("sidetoneEnabled", false)
                    .put("sidetoneGainDb", 0.0)
                    .put("engineMode", "native_first"),
            )
    }

    private fun preset(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("engine", JSONObject())
        .put("modules", JSONArray())
}
