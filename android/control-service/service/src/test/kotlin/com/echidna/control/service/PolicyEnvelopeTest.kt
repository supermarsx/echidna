package com.echidna.control.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEnvelopeTest {
    @Test
    fun `valid request receives a positive published generation`() {
        val parsed = PolicyEnvelopeCodec.parseRequest(validRequest().toString())

        assertNotNull(parsed)
        val published = PolicyEnvelopeCodec.encode(parsed!!, 7L)
        val reparsed = PolicyEnvelopeCodec.parsePublished(published!!)

        assertEquals(7L, reparsed!!.generation)
        assertEquals("p1", reparsed.envelope.defaultProfileId)
        assertEquals("zygisk", reparsed.envelope.captureOwners["com.example.recorder"])
    }

    @Test
    fun `unknown root and control fields are rejected`() {
        val unknownRoot = validRequest().put("future", true)
        assertNull(PolicyEnvelopeCodec.parseRequest(unknownRoot.toString()))

        val unknownControl = validRequest()
        unknownControl.getJSONObject("control").put("future", true)
        assertNull(PolicyEnvelopeCodec.parseRequest(unknownControl.toString()))
    }

    @Test
    fun `duplicate object keys are rejected before org json can collapse them`() {
        val original = validRequest().toString()
        val duplicate = original.replaceFirst(
            "\"defaultProfileId\":\"p1\"",
            "\"defaultProfileId\":\"p1\",\"defaultProfileId\":\"p1\"",
        )

        assertTrue(duplicate != original)
        assertNull(PolicyEnvelopeCodec.parseRequest(duplicate))
    }

    @Test
    fun `published generation must be a positive exact integer`() {
        val request = validRequest()
        assertNull(PolicyEnvelopeCodec.parsePublished(JSONObject(request.toString()).put("generation", 0).toString()))
        assertNull(PolicyEnvelopeCodec.parsePublished(JSONObject(request.toString()).put("generation", 1.5).toString()))
        assertNotNull(PolicyEnvelopeCodec.parsePublished(JSONObject(request.toString()).put("generation", 1).toString()))
    }

    @Test
    fun `bindings use base packages while whitelist and owners may name processes`() {
        val request = validRequest()
        request.put("appBindings", JSONObject().put("com.example.recorder:worker", "p1"))
        assertNull(PolicyEnvelopeCodec.parseRequest(request.toString()))

        request.put("appBindings", JSONObject().put("com.example.recorder", "p1"))
        request.put("whitelist", JSONObject().put("com.example.recorder:worker", true))
        request.put("captureOwners", JSONObject().put("com.example.recorder:worker", "lsposed"))
        assertNotNull(PolicyEnvelopeCodec.parseRequest(request.toString()))
    }

    @Test
    fun `profile and entry limits are enforced`() {
        val oversized = validRequest()
        oversized.getJSONObject("profiles").getJSONObject("p1")
            .put("padding", "x".repeat(MAX_POLICY_PRESET_BYTES))
        assertNull(PolicyEnvelopeCodec.parseRequest(oversized.toString()))

        val tooMany = validRequest()
        val whitelist = JSONObject()
        repeat(257) { whitelist.put("com.example.p$it", true) }
        tooMany.put("whitelist", whitelist)
        assertNull(PolicyEnvelopeCodec.parseRequest(tooMany.toString()))
    }

    private fun validRequest(): JSONObject = JSONObject()
        .put("schemaVersion", POLICY_SCHEMA_VERSION)
        .put(
            "profiles",
            JSONObject().put(
                "p1",
                JSONObject()
                    .put("name", "Preset")
                    .put("engine", JSONObject().put("latencyMode", "LL"))
                    .put("modules", JSONArray()),
            ),
        )
        .put("defaultProfileId", "p1")
        .put("appBindings", JSONObject().put("com.example.recorder", "p1"))
        .put("whitelist", JSONObject().put("com.example.recorder", true))
        .put("captureOwners", JSONObject().put("com.example.recorder", "zygisk"))
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
