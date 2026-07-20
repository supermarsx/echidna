package com.echidna.control.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Derivation contract for [TelemetrySnapshot]: the warning-flag decoding, the anonymised payload's
 * redaction boundary, and the sample-summary statistics. TelemetryExporterTest covers the exporter
 * that wraps these; this pins the model's own behaviour.
 */
class TelemetryModelsTest {

    private fun snapshot(
        warningFlags: Int = 0,
        xruns: Int = 0,
        samples: List<TelemetrySample> = emptyList(),
        hooks: List<HookTelemetry> = emptyList(),
    ) = TelemetrySnapshot(
        totalCallbacks = 1_000L,
        averageLatencyMs = 4.25f,
        averageCpuPercent = 18.5f,
        inputRms = -41.5f,
        outputRms = -37.25f,
        inputPeak = -6.5f,
        outputPeak = -3.25f,
        detectedPitchHz = 143.75f,
        targetPitchHz = 196.5f,
        formantShiftCents = 31.5f,
        formantWidth = 0.75f,
        xruns = xruns,
        warningFlags = warningFlags,
        samples = samples,
        hooks = hooks,
    )

    private fun hook(
        name: String = "AudioRecord",
        library: String = "libaudioclient.so",
        symbol: String = "AudioRecord::read",
        reason: String = "installed",
        attempts: Int = 4,
        successes: Int = 3,
    ) = HookTelemetry(
        name = name,
        library = library,
        symbol = symbol,
        reason = reason,
        attempts = attempts,
        successes = successes,
        failures = attempts - successes,
        lastAttemptNs = 900L,
        lastSuccessNs = 800L,
    )

    @Test
    fun `no warning flags means no warnings`() {
        assertEquals(emptyList<String>(), snapshot(warningFlags = 0).warnings)
    }

    @Test
    fun `each warning bit decodes to its own message`() {
        assertEquals(listOf("Latency exceeded guard threshold"), snapshot(warningFlags = 1).warnings)
        assertEquals(listOf("CPU usage exceeded 75%"), snapshot(warningFlags = 2).warnings)
        assertEquals(listOf("XRuns detected"), snapshot(warningFlags = 4).warnings)
    }

    @Test
    fun `combined warning bits decode in bit order`() {
        assertEquals(
            listOf(
                "Latency exceeded guard threshold",
                "CPU usage exceeded 75%",
                "XRuns detected",
            ),
            snapshot(warningFlags = 7).warnings,
        )
        assertEquals(
            listOf("Latency exceeded guard threshold", "XRuns detected"),
            snapshot(warningFlags = 5).warnings,
        )
    }

    @Test
    fun `unknown warning bits are ignored rather than invented`() {
        assertEquals(emptyList<String>(), snapshot(warningFlags = 1 shl 8).warnings)
        assertEquals(
            listOf("CPU usage exceeded 75%"),
            snapshot(warningFlags = (1 shl 8) or 2).warnings,
        )
    }

    @Test
    fun `toJson omits the sample array when trends are not requested`() {
        val samples = listOf(TelemetrySample(1L, 10, 2, 0, 0))
        val withSamples = JSONObject(snapshot(samples = samples).toJson(includeSamples = true))
        val without = JSONObject(snapshot(samples = samples).toJson(includeSamples = false))

        assertEquals(1, withSamples.getJSONArray("samples").length())
        assertFalse(without.has("samples"))
        // Everything else survives either way.
        assertEquals(1_000L, without.getLong("totalCallbacks"))
        assertEquals(0, without.getJSONArray("hooks").length())
    }

    @Test
    fun `empty hook metadata fields are dropped from the payload`() {
        val bare = hook(library = "", symbol = "", reason = "")
        val json = JSONObject(snapshot(hooks = listOf(bare)).toJson())
        val encoded = json.getJSONArray("hooks").getJSONObject(0)

        assertEquals("AudioRecord", encoded.getString("name"))
        assertFalse(encoded.has("library"))
        assertFalse(encoded.has("symbol"))
        assertFalse(encoded.has("reason"))
        // Counters are always present even when zero-valued metadata is dropped.
        assertEquals(4, encoded.getInt("attempts"))
        assertEquals(1, encoded.getInt("failures"))
    }

    @Test
    fun `anonymized payload drops signal levels pitch and per-sample timings`() {
        val json = snapshot(
            warningFlags = 3,
            xruns = 2,
            samples = listOf(TelemetrySample(12_345L, 10, 2, 1, 0)),
            hooks = listOf(hook()),
        ).anonymizedJson()

        assertFalse("input levels are voice-derived", json.contains("inputRms"))
        assertFalse(json.contains("outputRms"))
        assertFalse(json.contains("inputPeak"))
        assertFalse("detected pitch identifies a speaker", json.contains("detectedPitchHz"))
        assertFalse(json.contains("formantShiftCents"))
        assertFalse("raw sample timings are not shared", json.contains("samples"))
        assertFalse(json.contains("12345"))
        assertFalse("hook symbols and libraries stay local", json.contains("libaudioclient.so"))
        assertFalse(json.contains("AudioRecord::read"))

        val root = JSONObject(json)
        assertEquals(1_000L, root.getLong("totalCallbacks"))
        assertEquals(2, root.getInt("xruns"))
        assertEquals(2, root.getJSONArray("warnings").length())
    }

    @Test
    fun `anonymized hook success rate is a ratio and never divides by zero`() {
        val json = JSONObject(
            snapshot(
                hooks = listOf(
                    hook(name = "Tried", attempts = 4, successes = 3),
                    hook(name = "Untried", attempts = 0, successes = 0),
                )
            ).anonymizedJson()
        )
        val hooks = json.getJSONArray("hooks")

        assertEquals(0.75, hooks.getJSONObject(0).getDouble("successRate"), 1e-9)
        assertEquals(
            "an unattempted hook must report 0.0, not NaN",
            0.0,
            hooks.getJSONObject(1).getDouble("successRate"),
            0.0,
        )
    }

    @Test
    fun `diagnostics payload adds a per-hook success rate alongside the raw counters`() {
        val root = snapshot(hooks = listOf(hook(attempts = 5, successes = 1)))
            .diagnosticsJson(includeTrends = false)
        val encoded = root.getJSONArray("hooks").getJSONObject(0)

        assertEquals(5, encoded.getInt("attempts"))
        assertEquals(1, encoded.getInt("successes"))
        assertEquals(4, encoded.getInt("failures"))
        assertEquals(0.2, encoded.getDouble("successRate"), 1e-9)
        assertFalse("trends were not requested", root.has("sampleSummary"))
    }

    @Test
    fun `sample summary reports min max average and total per field`() {
        val samples = listOf(
            TelemetrySample(1L, durationUs = 10, cpuUs = 2, flags = 1, xruns = 0),
            TelemetrySample(2L, durationUs = 30, cpuUs = 4, flags = 1, xruns = 3),
            TelemetrySample(3L, durationUs = 20, cpuUs = 6, flags = 2, xruns = 0),
        )
        val summary = snapshot(samples = samples)
            .diagnosticsJson(includeTrends = true)
            .getJSONObject("sampleSummary")

        assertEquals(3, summary.getInt("sampleCount"))

        val duration = summary.getJSONObject("durationUs")
        assertEquals(10, duration.getInt("min"))
        assertEquals(30, duration.getInt("max"))
        assertEquals(20.0, duration.getDouble("avg"), 1e-9)
        assertEquals(60L, duration.getLong("total"))

        val cpu = summary.getJSONObject("cpuUs")
        assertEquals(2, cpu.getInt("min"))
        assertEquals(6, cpu.getInt("max"))
        assertEquals(4.0, cpu.getDouble("avg"), 1e-9)

        val xruns = summary.getJSONObject("xruns")
        assertEquals(0, xruns.getInt("min"))
        assertEquals(3, xruns.getInt("max"))
        assertEquals(3L, xruns.getLong("total"))

        // Flag values are histogrammed, not averaged.
        val flagCounts = summary.getJSONObject("flagCounts")
        assertEquals(2, flagCounts.getInt("1"))
        assertEquals(1, flagCounts.getInt("2"))
    }

    @Test
    fun `an empty sample window summarises to zeroes rather than throwing`() {
        val summary = snapshot(samples = emptyList())
            .diagnosticsJson(includeTrends = true)
            .getJSONObject("sampleSummary")

        assertEquals(0, summary.getInt("sampleCount"))
        listOf("durationUs", "cpuUs", "xruns").forEach { field ->
            val stats = summary.getJSONObject(field)
            assertEquals("$field min", 0, stats.getInt("min"))
            assertEquals("$field max", 0, stats.getInt("max"))
            assertEquals("$field avg", 0.0, stats.getDouble("avg"), 0.0)
            assertEquals("$field total", 0L, stats.getLong("total"))
        }
        assertEquals(0, summary.getJSONObject("flagCounts").length())
    }

    @Test
    fun `sample summary totals do not overflow an int accumulator`() {
        // durationUs is an Int per sample but the running total is a Long; a large window of
        // near-max samples must not wrap negative.
        val samples = List(4) { TelemetrySample(it.toLong(), Int.MAX_VALUE, 0, 0, 0) }
        val total = snapshot(samples = samples)
            .diagnosticsJson(includeTrends = true)
            .getJSONObject("sampleSummary")
            .getJSONObject("durationUs")
            .getLong("total")

        assertEquals(4L * Int.MAX_VALUE, total)
        assertTrue(total > 0L)
    }
}
