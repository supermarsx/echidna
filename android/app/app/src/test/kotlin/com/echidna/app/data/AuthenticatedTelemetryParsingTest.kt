package com.echidna.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AuthenticatedTelemetryParsingTest {
    @Test
    fun `diagnostics process only a current fresh authenticated mutation`() {
        val snapshot = TelemetryParser.parse(
            snapshotJson(
                currentGeneration = 11L,
                routeGeneration = 11L,
                state = "processing",
                recentMutation = true,
                mutations = 3L,
            ),
        )!!

        assertTrue(snapshot.hasVerifiedRuntimeTelemetry)
        assertTrue(snapshot.isVerifiedProcessing)
    }

    @Test
    fun `callbacks and service processing claim do not replace mutation proof`() {
        val installed = TelemetryParser.parse(
            snapshotJson(
                currentGeneration = 11L,
                routeGeneration = 11L,
                state = "installed",
                recentMutation = false,
                mutations = 0L,
            ),
        )!!
        val expiredMutation = TelemetryParser.parse(
            snapshotJson(
                currentGeneration = 11L,
                routeGeneration = 11L,
                state = "processing",
                recentMutation = false,
                mutations = 3L,
            ),
        )!!

        assertFalse(installed.isVerifiedProcessing)
        assertFalse(expiredMutation.isVerifiedProcessing)
    }

    @Test
    fun `effect hmac mutation proof is trusted end to end`() {
        val snapshot = TelemetryParser.parse(
            snapshotJson(
                currentGeneration = 11L,
                routeGeneration = 11L,
                state = "processing",
                recentMutation = true,
                mutations = 1L,
            ).replace("authenticated_socket_v2", "effect_hmac_v1")
                .replace("\"route\":\"aaudio\"", "\"route\":\"preprocessor\""),
        )!!

        assertTrue(snapshot.hasVerifiedRuntimeTelemetry)
        assertTrue(snapshot.isVerifiedProcessing)
    }

    @Test
    fun `old generation and legacy shared memory snapshots never assert processing`() {
        val oldGeneration = TelemetryParser.parse(
            snapshotJson(
                currentGeneration = 12L,
                routeGeneration = 11L,
                state = "processing",
                recentMutation = true,
                mutations = 3L,
            ),
        )!!
        val legacy = TelemetryParser.parse(
            """{"totalCallbacks":999,"hooks":[{"name":"AAudio","successes":1}]}""",
        )!!

        assertFalse(oldGeneration.isVerifiedProcessing)
        assertFalse(legacy.hasVerifiedRuntimeTelemetry)
        assertFalse(legacy.isVerifiedProcessing)
    }

    @Test
    fun `caller attested preprocessor counters stay visible without verified processing`() {
        val snapshot = TelemetryParser.parse(
            """
                {
                  "schemaVersion":2,
                  "type":"telemetrySnapshot",
                  "verification":"caller_attested_binder_v1",
                  "currentPolicyGeneration":11,
                  "processing":false,
                  "totalCallbacks":4,
                  "routes":[{
                    "process":"com.example.voice",
                    "route":"preprocessor",
                    "generation":11,
                    "state":"processing",
                    "sequence":2,
                    "ageMs":10,
                    "recentMutation":true,
                    "blocks":4,
                    "frames":768,
                    "failures":0,
                    "mutations":1,
                    "verification":"caller_attested_binder_v1"
                  }]
                }
            """.trimIndent(),
        )!!

        assertEquals(1, snapshot.routes.size)
        assertEquals(1L, snapshot.routes.single().mutations)
        assertFalse(snapshot.hasVerifiedRuntimeTelemetry)
        assertFalse(snapshot.isVerifiedProcessing)
    }

    @Test
    fun `mixed snapshots retain trusted socket proof and caller diagnostics separately`() {
        val snapshot = TelemetryParser.parse(
            """
                {
                  "schemaVersion":2,
                  "type":"telemetrySnapshot",
                  "verification":"mixed_route_verification_v1",
                  "currentPolicyGeneration":11,
                  "processing":true,
                  "totalCallbacks":8,
                  "routes":[
                    {
                      "process":"com.example.voice",
                      "route":"preprocessor",
                      "generation":11,
                      "state":"processing",
                      "sequence":2,
                      "ageMs":10,
                      "recentMutation":true,
                      "blocks":4,
                      "frames":768,
                      "failures":0,
                      "mutations":1,
                      "verification":"caller_attested_binder_v1"
                    },
                    {
                      "process":"com.example.voice",
                      "route":"aaudio",
                      "generation":11,
                      "state":"processing",
                      "sequence":8,
                      "ageMs":10,
                      "recentMutation":true,
                      "blocks":4,
                      "frames":768,
                      "failures":0,
                      "mutations":2,
                      "verification":"authenticated_socket_v2"
                    }
                  ]
                }
            """.trimIndent(),
        )!!

        assertEquals(2, snapshot.routes.size)
        assertTrue(snapshot.hasVerifiedRuntimeTelemetry)
        assertTrue(snapshot.isVerifiedProcessing)
    }

    @Test
    fun `explicit unknown route verification never inherits trusted root verification`() {
        val snapshot = TelemetryParser.parse(
            snapshotJson(
                currentGeneration = 11L,
                routeGeneration = 11L,
                state = "processing",
                recentMutation = true,
                mutations = 3L,
            ).replace(
                "\"mutations\":3",
                "\"mutations\":3,\"verification\":\"forged\"",
            ),
        )!!

        assertEquals(1, snapshot.routes.size)
        assertEquals("unverified", snapshot.routes.single().verification)
        assertFalse(snapshot.hasVerifiedRuntimeTelemetry)
        assertFalse(snapshot.isVerifiedProcessing)
    }

    private fun snapshotJson(
        currentGeneration: Long,
        routeGeneration: Long,
        state: String,
        recentMutation: Boolean,
        mutations: Long,
    ): String = """
        {
          "schemaVersion":2,
          "type":"telemetrySnapshot",
          "verification":"authenticated_socket_v2",
          "currentPolicyGeneration":$currentGeneration,
          "processing":true,
          "totalCallbacks":999,
          "routes":[{
            "process":"com.example.voice",
            "route":"aaudio",
            "generation":$routeGeneration,
            "state":"$state",
            "sequence":9,
            "ageMs":100,
            "recentMutation":$recentMutation,
            "blocks":9,
            "frames":1728,
            "failures":0,
            "mutations":$mutations
          }]
        }
    """.trimIndent()
}
