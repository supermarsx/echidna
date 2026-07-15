package com.echidna.app.data

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
