package com.echidna.control.service

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.ArrayList

private const val TELEMETRY_MAGIC = 0xEDC1DA10u
private const val TELEMETRY_VERSION = 2
private const val ANDROID_TELEMETRY_PATH = "/data/local/tmp/echidna/echidna_telemetry.bin"
private const val HOST_TELEMETRY_PATH = "/dev/shm/echidna_telemetry"
private const val TELEMETRY_HEADER_SIZE = 104
private const val TELEMETRY_SAMPLE_SIZE = 24
private const val TELEMETRY_HOOK_SIZE_V1 = 64
private const val TELEMETRY_HOOK_SIZE_V2 = 192
private val DEFAULT_TELEMETRY_PATHS = listOf(ANDROID_TELEMETRY_PATH, HOST_TELEMETRY_PATH)

internal class TelemetryReader(
    private val telemetryPaths: List<String> = DEFAULT_TELEMETRY_PATHS
) {
    fun snapshot(): TelemetrySnapshot? {
        return telemetryPaths
            .asSequence()
            .map(::File)
            .filter { it.exists() && it.length() > 0L }
            .mapNotNull { file -> runCatching { readSnapshot(file) }.getOrNull() }
            .firstOrNull()
    }

    private fun readSnapshot(file: File): TelemetrySnapshot? {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val buffer = channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                0,
                channel.size()
            )
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return parse(buffer)
        }
    }

    private fun parse(buffer: ByteBuffer): TelemetrySnapshot? {
        if (buffer.remaining() < TELEMETRY_HEADER_SIZE) {
            return null
        }
        val magic = buffer.int.toUInt()
        if (magic != TELEMETRY_MAGIC) {
            return null
        }
        val version = buffer.int
        if (version != 1 && version != TELEMETRY_VERSION) {
            return null
        }
        val layoutSize = buffer.int
        if (layoutSize <= 0 || layoutSize > buffer.capacity()) {
            return null
        }
        val sampleCapacity = buffer.int
        if (sampleCapacity < 0) {
            return null
        }
        val writeIndex = buffer.int
        val sampleCount = buffer.int
        val totalCallbacks = buffer.long
        val totalCallbackNs = buffer.long
        val totalCpuNs = buffer.long
        val hookCapacity = buffer.int
        val hookCount = buffer.int
        if (hookCapacity < 0) {
            return null
        }
        val hookRecordSize = if (version >= 2) TELEMETRY_HOOK_SIZE_V2 else TELEMETRY_HOOK_SIZE_V1
        val expectedLayoutSize =
            TELEMETRY_HEADER_SIZE.toLong() +
                sampleCapacity.toLong() * TELEMETRY_SAMPLE_SIZE +
                hookCapacity.toLong() * hookRecordSize
        if (expectedLayoutSize > layoutSize || expectedLayoutSize > buffer.capacity()) {
            return null
        }
        val avgLatencyMs = buffer.float
        val avgCpuPercent = buffer.float
        val inputRms = buffer.float
        val outputRms = buffer.float
        val inputPeak = buffer.float
        val outputPeak = buffer.float
        val detectedPitch = buffer.float
        val targetPitch = buffer.float
        val formantShift = buffer.float
        val formantWidth = buffer.float
        val xruns = buffer.int
        val warningFlags = buffer.int

        val rawSamples = ArrayList<TelemetrySample>(sampleCapacity)
        repeat(sampleCapacity) {
            val timestamp = buffer.long
            val durationUs = buffer.int
            val cpuUs = buffer.int
            val flags = buffer.int
            val xrunsSample = buffer.int
            rawSamples += TelemetrySample(timestamp, durationUs, cpuUs, flags, xrunsSample)
        }
        val orderedSamples = ArrayList<TelemetrySample>()
        if (sampleCapacity > 0 && sampleCount > 0) {
            val used = sampleCount.coerceAtMost(sampleCapacity)
            val baseIndex = ((writeIndex % sampleCapacity) - used + sampleCapacity) % sampleCapacity
            repeat(used) { idx ->
                val index = (baseIndex + idx) % sampleCapacity
                orderedSamples += rawSamples[index]
            }
        }

        val hooks = ArrayList<HookTelemetry>()
        repeat(hookCapacity) {
            val nameBytes = ByteArray(32)
            buffer.get(nameBytes)
            val name = nameBytes.decodeToString().trimEnd('\u0000')
            val library = if (version >= 2) {
                val bytes = ByteArray(32)
                buffer.get(bytes)
                bytes.decodeToString().trimEnd('\u0000')
            } else {
                ""
            }
            val symbol = if (version >= 2) {
                val bytes = ByteArray(48)
                buffer.get(bytes)
                bytes.decodeToString().trimEnd('\u0000')
            } else {
                ""
            }
            val reason = if (version >= 2) {
                val bytes = ByteArray(48)
                buffer.get(bytes)
                bytes.decodeToString().trimEnd('\u0000')
            } else {
                ""
            }
            val attempts = buffer.int
            val successes = buffer.int
            val failures = buffer.int
            buffer.int // reserved
            val lastAttempt = buffer.long
            val lastSuccess = buffer.long
            if (name.isNotEmpty() || attempts > 0 || successes > 0) {
                hooks += HookTelemetry(
                    name,
                    library,
                    symbol,
                    reason,
                    attempts,
                    successes,
                    failures,
                    lastAttempt,
                    lastSuccess
                )
            }
        }

        val avgLatencyMsComputed = if (totalCallbacks == 0L) {
            0f
        } else {
            (totalCallbackNs / totalCallbacks) / 1_000_000f
        }
        val avgCpuPercentComputed = if (totalCallbackNs == 0L) 0f
        else (totalCpuNs.toDouble() / totalCallbackNs.toDouble() * 100.0).toFloat()

        return TelemetrySnapshot(
            totalCallbacks = totalCallbacks,
            averageLatencyMs = if (avgLatencyMs.isFinite()) avgLatencyMs else avgLatencyMsComputed,
            averageCpuPercent = if (avgCpuPercent.isFinite()) {
                avgCpuPercent
            } else {
                avgCpuPercentComputed
            },
            inputRms = inputRms,
            outputRms = outputRms,
            inputPeak = inputPeak,
            outputPeak = outputPeak,
            detectedPitchHz = detectedPitch,
            targetPitchHz = targetPitch,
            formantShiftCents = formantShift,
            formantWidth = formantWidth,
            xruns = xruns,
            warningFlags = warningFlags,
            samples = orderedSamples,
            hooks = hooks.take(hookCount.coerceAtMost(hooks.size))
        )
    }
}
