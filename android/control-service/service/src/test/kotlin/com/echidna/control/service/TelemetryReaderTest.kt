package com.echidna.control.service

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private val TEST_TELEMETRY_MAGIC = 0xEDC1DA10u.toInt()
private const val TEST_TELEMETRY_VERSION = 2
private const val TEST_SAMPLE_CAPACITY = 96
private const val TEST_HOOK_CAPACITY = 8
private const val TEST_HEADER_SIZE = 104
private const val TEST_SAMPLE_SIZE = 24
private const val TEST_HOOK_SIZE = 192
private const val TEST_LAYOUT_SIZE =
    TEST_HEADER_SIZE +
        TEST_SAMPLE_CAPACITY * TEST_SAMPLE_SIZE +
        TEST_HOOK_CAPACITY * TEST_HOOK_SIZE

class TelemetryReaderTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("telemetry-reader").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `reads first populated telemetry path`() {
        val androidPath = File(tempDir, "echidna_telemetry.bin")
        val hostPath = File(tempDir, "echidna_telemetry")
        writeTelemetry(androidPath, totalCallbacks = 9L, hookName = "AAudio")
        writeTelemetry(hostPath, totalCallbacks = 3L, hookName = "OpenSL")

        val snapshot = TelemetryReader(
            listOf(androidPath.absolutePath, hostPath.absolutePath)
        ).snapshot()

        assertNotNull(snapshot)
        assertEquals(9L, snapshot!!.totalCallbacks)
        assertEquals("AAudio", snapshot.hooks.single().name)
    }

    @Test
    fun `falls back to host telemetry path when Android path is unavailable`() {
        val androidPath = File(File(tempDir, "missing"), "echidna_telemetry.bin")
        val hostPath = File(tempDir, "echidna_telemetry")
        writeTelemetry(hostPath, totalCallbacks = 3L, hookName = "OpenSL")

        val snapshot = TelemetryReader(
            listOf(androidPath.absolutePath, hostPath.absolutePath)
        ).snapshot()

        assertNotNull(snapshot)
        assertEquals(3L, snapshot!!.totalCallbacks)
        assertEquals("OpenSL", snapshot.hooks.single().name)
    }

    @Test
    fun `falls back to host telemetry path when Android path is corrupt`() {
        val androidPath = File(tempDir, "echidna_telemetry.bin")
        val hostPath = File(tempDir, "echidna_telemetry")
        androidPath.writeBytes(ByteArray(16) { 0x7f })
        writeTelemetry(hostPath, totalCallbacks = 5L, hookName = "tinyalsa")

        val snapshot = TelemetryReader(
            listOf(androidPath.absolutePath, hostPath.absolutePath)
        ).snapshot()

        assertNotNull(snapshot)
        assertEquals(5L, snapshot!!.totalCallbacks)
        assertEquals("tinyalsa", snapshot.hooks.single().name)
    }

    @Test
    fun `returns null for malformed telemetry capacities`() {
        val telemetryPath = File(tempDir, "echidna_telemetry.bin")
        telemetryPath.writeBytes(buildMalformedCapacityTelemetryBytes())

        val snapshot = TelemetryReader(listOf(telemetryPath.absolutePath)).snapshot()

        assertNull(snapshot)
    }

    @Test
    fun `parses samples and hook metadata from selected telemetry file`() {
        val telemetryPath = File(tempDir, "echidna_telemetry.bin")
        writeTelemetry(telemetryPath, totalCallbacks = 3L, hookName = "AudioRecord")

        val snapshot = TelemetryReader(listOf(telemetryPath.absolutePath)).snapshot()

        assertNotNull(snapshot)
        assertEquals(3L, snapshot!!.totalCallbacks)
        assertEquals(2.5, snapshot.averageLatencyMs.toDouble(), 0.0001)
        assertEquals(33.25, snapshot.averageCpuPercent.toDouble(), 0.0001)
        assertEquals(7, snapshot.warningFlags)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), snapshot.samples.map { it.timestampNs })
        assertEquals(10, snapshot.samples[0].durationUs)
        assertEquals(2, snapshot.samples[1].cpuUs)
        assertEquals(3, snapshot.samples[2].flags)

        val hook = snapshot.hooks.single()
        assertEquals("AudioRecord", hook.name)
        assertEquals("libaudioclient.so", hook.library)
        assertEquals("AudioRecord::read", hook.symbol)
        assertEquals("installed", hook.reason)
        assertEquals(2, hook.attempts)
        assertEquals(1, hook.successes)
        assertEquals(1, hook.failures)
        assertEquals(4_000L, hook.lastAttemptNs)
        assertEquals(3_500L, hook.lastSuccessNs)
    }

    private fun writeTelemetry(file: File, totalCallbacks: Long, hookName: String) {
        file.parentFile?.mkdirs()
        file.writeBytes(buildTelemetryBytes(totalCallbacks, hookName))
    }

    private fun buildTelemetryBytes(totalCallbacks: Long, hookName: String): ByteArray {
        val buffer = ByteBuffer.allocate(TEST_LAYOUT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(TEST_TELEMETRY_MAGIC)
        buffer.putInt(TEST_TELEMETRY_VERSION)
        buffer.putInt(TEST_LAYOUT_SIZE)
        buffer.putInt(TEST_SAMPLE_CAPACITY)
        buffer.putInt(3)
        buffer.putInt(3)
        buffer.putLong(totalCallbacks)
        buffer.putLong(7_500_000L)
        buffer.putLong(2_493_750L)
        buffer.putInt(TEST_HOOK_CAPACITY)
        buffer.putInt(1)
        buffer.putFloat(2.5f)
        buffer.putFloat(33.25f)
        buffer.putFloat(-42.0f)
        buffer.putFloat(-39.0f)
        buffer.putFloat(-6.0f)
        buffer.putFloat(-4.0f)
        buffer.putFloat(140.0f)
        buffer.putFloat(180.0f)
        buffer.putFloat(25.0f)
        buffer.putFloat(0.8f)
        buffer.putInt(1)
        buffer.putInt(7)

        putSample(buffer, timestampNs = 1_000L, durationUs = 10, cpuUs = 1, flags = 1, xruns = 0)
        putSample(buffer, timestampNs = 2_000L, durationUs = 20, cpuUs = 2, flags = 2, xruns = 0)
        putSample(buffer, timestampNs = 3_000L, durationUs = 30, cpuUs = 3, flags = 3, xruns = 1)
        repeat(TEST_SAMPLE_CAPACITY - 3) {
            putSample(buffer, timestampNs = 0L, durationUs = 0, cpuUs = 0, flags = 0, xruns = 0)
        }

        putHook(buffer, hookName)
        repeat(TEST_HOOK_CAPACITY - 1) {
            putEmptyHook(buffer)
        }
        return buffer.array()
    }

    private fun buildMalformedCapacityTelemetryBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(TEST_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(TEST_TELEMETRY_MAGIC)
        buffer.putInt(TEST_TELEMETRY_VERSION)
        buffer.putInt(TEST_HEADER_SIZE)
        buffer.putInt(TEST_SAMPLE_CAPACITY)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putLong(0L)
        buffer.putLong(0L)
        buffer.putLong(0L)
        buffer.putInt(TEST_HOOK_CAPACITY)
        buffer.putInt(0)
        repeat(10) {
            buffer.putFloat(0f)
        }
        buffer.putInt(0)
        buffer.putInt(0)
        return buffer.array()
    }

    private fun putSample(
        buffer: ByteBuffer,
        timestampNs: Long,
        durationUs: Int,
        cpuUs: Int,
        flags: Int,
        xruns: Int
    ) {
        buffer.putLong(timestampNs)
        buffer.putInt(durationUs)
        buffer.putInt(cpuUs)
        buffer.putInt(flags)
        buffer.putInt(xruns)
    }

    private fun putHook(buffer: ByteBuffer, hookName: String) {
        buffer.putFixedString(hookName, 32)
        buffer.putFixedString("libaudioclient.so", 32)
        buffer.putFixedString("AudioRecord::read", 48)
        buffer.putFixedString("installed", 48)
        buffer.putInt(2)
        buffer.putInt(1)
        buffer.putInt(1)
        buffer.putInt(0)
        buffer.putLong(4_000L)
        buffer.putLong(3_500L)
    }

    private fun putEmptyHook(buffer: ByteBuffer) {
        repeat(TEST_HOOK_SIZE) {
            buffer.put(0.toByte())
        }
    }

    private fun ByteBuffer.putFixedString(value: String, size: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val count = minOf(bytes.size, size - 1)
        put(bytes, 0, count)
        repeat(size - count) {
            put(0.toByte())
        }
    }
}
