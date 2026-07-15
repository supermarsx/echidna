package com.echidna.control.service

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryExporterTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("telemetry-exporter").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `diagnostics export remains opt-in gated`() {
        val exporter = TelemetryExporter(tempDir)

        val exported = exporter.exportDiagnostics(
            includeTrends = true,
            statusJson = "{}",
            whitelistBindingsJson = "{}",
            controlStateJson = "{}"
        )

        assertEquals("{}", exported)
    }

    @Test
    fun `diagnostics export redacts whitelist and device identifiers`() {
        val exporter = TelemetryExporter(tempDir)
        exporter.setOptIn(true)

        val exported = exporter.exportDiagnostics(
            includeTrends = true,
            statusJson = statusJson(),
            whitelistBindingsJson = whitelistJson(),
            controlStateJson = """{"masterEnabled":true,"bypass":false}"""
        )
        val root = JSONObject(exported)
        val counts = root.getJSONObject("whitelist").getJSONObject("counts")

        assertEquals("echidna.diagnostics.v1", root.getString("schema"))
        assertEquals(1, counts.getInt("enabledWhitelist"))
        assertEquals(1, counts.getInt("disabledWhitelist"))
        assertEquals(1, counts.getInt("appBindings"))
        assertFalse(exported.contains("com.example.voice"))
        assertFalse(exported.contains("preset-main"))
        assertFalse(exported.contains("Samsung (exynos990)"))
        assertFalse(exported.contains("/data/user/0/com.example.voice"))
        assertTrue(exported.contains("sha256:"))
    }

    @Test
    fun `diagnostics export reports empty whitelist as an action`() {
        val exporter = TelemetryExporter(tempDir)
        exporter.setOptIn(true)

        val exported = exporter.exportDiagnostics(
            includeTrends = false,
            statusJson = """{"magiskModuleInstalled":true,"zygiskEnabled":true}""",
            whitelistBindingsJson = """{"whitelist":{},"appBindings":{}}""",
            controlStateJson = """{"masterEnabled":true,"bypass":false}"""
        )
        val actions = JSONObject(exported).getJSONArray("actions")

        assertEquals("configure_whitelist", actions.getJSONObject(0).getString("code"))
    }

    @Test
    fun `telemetry diagnostics omit raw samples and hook timestamps`() {
        val json = sampleSnapshot().diagnosticsJson(includeTrends = true).toString()

        assertTrue(json.contains("sampleSummary"))
        assertTrue(json.contains("AudioRecord::read"))
        assertFalse(json.contains("timestampNs"))
        assertFalse(json.contains("lastAttemptNs"))
        assertFalse(json.contains("lastSuccessNs"))
    }

    private fun statusJson(): String = JSONObject()
        .put("magiskModuleInstalled", true)
        .put("zygiskEnabled", true)
        .put("selinuxState", "ENFORCING")
        .put("selinuxStatus", "Enforcing (policy and capture route unverified)")
        .put("policyToolAvailable", true)
        .put("policyAppliedVerified", false)
        .put("nativeRouteVerified", false)
        .put("javaFallbackRecommended", true)
        .put("lastError", "/data/user/0/com.example.voice/cache failed")
        .put(
            "audioStack",
            JSONObject()
                .put("hal", "Samsung (exynos990)")
                .put("manufacturer", "Samsung")
                .put("boardPlatform", "exynos990")
                .put("vendorFamily", "Samsung Exynos")
                .put("aaudioSupported", true)
                .put("sampleRate", 48000)
                .put("framesPerBuffer", 192)
        )
        .toString()

    private fun whitelistJson(): String = """
        {
          "whitelist": {
            "com.example.voice": true,
            "com.example.disabled": false
          },
          "appBindings": {
            "com.example.voice": "preset-main"
          }
        }
    """.trimIndent()

    private fun sampleSnapshot(): TelemetrySnapshot = TelemetrySnapshot(
        totalCallbacks = 2L,
        averageLatencyMs = 3.5f,
        averageCpuPercent = 12.5f,
        inputRms = -40f,
        outputRms = -38f,
        inputPeak = -6f,
        outputPeak = -5f,
        detectedPitchHz = 140f,
        targetPitchHz = 180f,
        formantShiftCents = 25f,
        formantWidth = 0.8f,
        xruns = 1,
        warningFlags = 0,
        samples = listOf(
            TelemetrySample(timestampNs = 100L, durationUs = 10, cpuUs = 1, flags = 1, xruns = 0),
            TelemetrySample(timestampNs = 200L, durationUs = 20, cpuUs = 3, flags = 1, xruns = 1)
        ),
        hooks = listOf(
            HookTelemetry(
                name = "AudioRecord",
                library = "libaudioclient.so",
                symbol = "AudioRecord::read",
                reason = "installed",
                attempts = 2,
                successes = 1,
                failures = 1,
                lastAttemptNs = 300L,
                lastSuccessNs = 250L
            )
        )
    )
}
