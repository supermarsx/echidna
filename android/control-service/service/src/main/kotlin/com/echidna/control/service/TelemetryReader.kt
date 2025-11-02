package com.echidna.control.service

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.ArrayList

private const val TELEMETRY_MAGIC = 0xEDC1DA10u
private const val TELEMETRY_VERSION = 1
private const val SHARED_MEMORY_PATH = "/dev/shm/echidna_telemetry"

internal class TelemetryReader {
    fun snapshot(): TelemetrySnapshot? {
        val file = File(SHARED_MEMORY_PATH)
        if (!file.exists() || file.length() == 0L) {
            return null
        }
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val buffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return parse(buffer)
        }
    }

    private fun parse(buffer: ByteBuffer): TelemetrySnapshot? {
        if (buffer.remaining() < 4 * 6 + 8 * 3 + 4 * 8) {
            return null
        }
        val magic = buffer.int.toUInt()
        if (magic != TELEMETRY_MAGIC) {
            return null
        }
        val version = buffer.int
        if (version != TELEMETRY_VERSION) {
            return null
        }
        val layoutSize = buffer.int
        if (layoutSize <= 0 || layoutSize > buffer.capacity()) {
            return null
        }
        val sampleCapacity = buffer.int
        val writeIndex = buffer.int
        val sampleCount = buffer.int
        val totalCallbacks = buffer.long
        val totalCallbackNs = buffer.long
        val totalCpuNs = buffer.long
        val hookCapacity = buffer.int
        val hookCount = buffer.int
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
            val attempts = buffer.int
            val successes = buffer.int
            val failures = buffer.int
            buffer.int // reserved
            val lastAttempt = buffer.long
            val lastSuccess = buffer.long
            if (name.isNotEmpty() || attempts > 0 || successes > 0) {
                hooks += HookTelemetry(name, attempts, successes, failures, lastAttempt, lastSuccess)
            }
        }

        val avgLatencyMsComputed = if (totalCallbacks == 0L) 0f else (totalCallbackNs / totalCallbacks) / 1_000_000f
        val avgCpuPercentComputed = if (totalCallbackNs == 0L) 0f
        else (totalCpuNs.toDouble() / totalCallbackNs.toDouble() * 100.0).toFloat()

        return TelemetrySnapshot(
            totalCallbacks = totalCallbacks,
            averageLatencyMs = if (avgLatencyMs.isFinite()) avgLatencyMs else avgLatencyMsComputed,
            averageCpuPercent = if (avgCpuPercent.isFinite()) avgCpuPercent else avgCpuPercentComputed,
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
