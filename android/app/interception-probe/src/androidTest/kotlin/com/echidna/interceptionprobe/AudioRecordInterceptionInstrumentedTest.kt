package com.echidna.interceptionprobe

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioRecordInterceptionInstrumentedTest {
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun audioRecordReadNativeHookReportsHitAndRoutesBuffer() {
        assumeTrue("root shell is required for module and telemetry checks", canUseRoot())

        val processId = Process.myPid()
        val before = readTelemetry()

        clearProbeEvidence()
        clearLogcat()
        val samplesRead = readAudioRecordSamples()
        assertTrue("AudioRecord must return captured PCM samples", samplesRead > 0)

        val after = before?.let { waitForTelemetryAfter(it.totalCallbacks) }
        val probeEvidence = waitForProbeEvidence("AudioRecord read intercepted")
        val interceptedLogcat = probeEvidence ?: waitForLogcatLine(
            processId,
            "AudioRecord read intercepted",
        )
        val telemetryAdvanced = before != null &&
            after != null &&
            after.totalCallbacks > before.totalCallbacks
        assertTrue(
            "AudioRecord.read should emit current-process hook evidence",
            interceptedLogcat != null,
        )
        assertTrue(
            "AudioRecord.read hook should route the captured buffer through DSP",
            interceptedLogcat!!.contains("processed=1"),
        )
        if (telemetryAdvanced) {
            assertTrue(
                "AudioRecord hook metadata should still be present after the read",
                after!!.hasSuccessfulHook("AudioRecord"),
            )
        }
    }

    private fun readAudioRecordSamples(): Int {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assumeTrue("AudioRecord minimum buffer is unavailable: $minBufferBytes", minBufferBytes > 0)

        val bufferBytes = max(minBufferBytes, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        assumeTrue(
            "AudioRecord failed to initialize",
            audioRecord.state == AudioRecord.STATE_INITIALIZED,
        )

        return try {
            val buffer = ShortArray(bufferBytes / BYTES_PER_SAMPLE)
            audioRecord.startRecording()
            var totalSamples = 0
            repeat(READ_ATTEMPTS) {
                val read = audioRecord.read(buffer, 0, min(buffer.size, READ_CHUNK_SAMPLES))
                if (read > 0) {
                    totalSamples += read
                }
            }
            totalSamples
        } finally {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
        }
    }

    private fun waitForTelemetryAfter(previousCallbacks: Long): TelemetryProbeSnapshot? {
        repeat(TELEMETRY_POLL_ATTEMPTS) {
            val snapshot = readTelemetry()
            if (snapshot != null && snapshot.totalCallbacks > previousCallbacks) {
                return snapshot
            }
            Thread.sleep(TELEMETRY_POLL_MS)
        }
        return readTelemetry()
    }

    private fun clearProbeEvidence() {
        probeEvidenceFile().delete()
    }

    private fun waitForProbeEvidence(fragment: String): String? {
        repeat(LOGCAT_POLL_ATTEMPTS) {
            val line = probeEvidenceFile()
                .takeIf { it.isFile }
                ?.readLines()
                ?.firstOrNull { line -> line.contains(fragment) }
            if (line != null) {
                return line
            }
            Thread.sleep(LOGCAT_POLL_MS)
        }
        return probeEvidenceFile()
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { line -> line.contains(fragment) }
    }

    private fun probeEvidenceFile(): File {
        return File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            PROBE_EVIDENCE_NAME,
        )
    }

    private fun canUseRoot(): Boolean {
        val result = runSu("id")
        return result.exitCode == 0 && result.stdout.contains("uid=0")
    }

    private fun readTelemetry(): TelemetryProbeSnapshot? {
        val result = runSu("dd if=$TELEMETRY_PATH bs=4096 count=1 2>/dev/null | base64")
        if (result.exitCode != 0) {
            return null
        }
        val encoded = result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("base64:") }
            .joinToString(separator = "")
        if (encoded.isEmpty()) {
            return null
        }
        return try {
            parseTelemetry(Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseTelemetry(bytes: ByteArray): TelemetryProbeSnapshot? {
        if (bytes.size < HEADER_SIZE) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        if (magic != TELEMETRY_MAGIC) {
            return null
        }
        val version = buffer.int
        if (version != TELEMETRY_VERSION) {
            return null
        }
        val layoutSize = buffer.int
        if (layoutSize <= 0 || layoutSize > bytes.size) {
            return null
        }
        val sampleCapacity = buffer.int
        buffer.int // write_index
        buffer.int // sample_count
        val totalCallbacks = buffer.long
        buffer.long // total_callback_ns
        buffer.long // total_cpu_ns
        val hookCapacity = buffer.int
        val hookCount = buffer.int

        val hooksOffset = HEADER_SIZE + sampleCapacity * SAMPLE_RECORD_SIZE
        if (hooksOffset >= bytes.size) {
            return TelemetryProbeSnapshot(totalCallbacks, emptyList())
        }
        buffer.position(hooksOffset)

        val hooks = ArrayList<TelemetryHookProbe>()
        repeat(min(hookCapacity, hookCount)) {
            if (buffer.remaining() < HOOK_RECORD_SIZE) {
                return@repeat
            }
            val name = buffer.readFixedString(32)
            val library = buffer.readFixedString(32)
            val symbol = buffer.readFixedString(48)
            buffer.readFixedString(48) // reason
            val attempts = buffer.int
            val successes = buffer.int
            buffer.int // failures
            buffer.int // reserved
            buffer.long // last_attempt_ns
            buffer.long // last_success_ns
            hooks += TelemetryHookProbe(name, library, symbol, attempts, successes)
        }
        return TelemetryProbeSnapshot(totalCallbacks, hooks)
    }

    private fun ByteBuffer.readFixedString(size: Int): String {
        val bytes = ByteArray(size)
        get(bytes)
        val end = bytes.indexOf(0).let { if (it >= 0) it else bytes.size }
        return bytes.copyOf(end).decodeToString()
    }

    private fun runSu(command: String): ShellResult {
        val output = runShellBytes("su -c '$command'; echo $SHELL_EXIT_PREFIX$?").decodeToString()
        return parseShellResult(output)
    }

    private fun runShell(command: String): ShellResult {
        val output = runShellBytes("$command; echo $SHELL_EXIT_PREFIX$?").decodeToString()
        return parseShellResult(output)
    }

    private fun parseShellResult(output: String): ShellResult {
        val marker = output.lastIndexOf(SHELL_EXIT_PREFIX)
        if (marker < 0) {
            return ShellResult(exitCode = -1, stdout = output, stderr = "missing exit marker")
        }
        val stdout = output.substring(0, marker)
        val exitCode = output.substring(marker + SHELL_EXIT_PREFIX.length)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.toIntOrNull()
            ?: -1
        return ShellResult(exitCode, stdout, "")
    }

    private fun readEchidnaLogcat(): String {
        val rootResult = runSu("logcat -d -v threadtime -s echidna")
        if (rootResult.exitCode == 0 && rootResult.stdout.isNotBlank()) {
            return rootResult.stdout
        }
        val shellResult = runShell("logcat -d -v threadtime -s echidna")
        return if (shellResult.exitCode == 0) shellResult.stdout else ""
    }

    private fun clearLogcat() {
        if (runSu("logcat -c").exitCode != 0) {
            runShell("logcat -c")
        }
    }

    private fun waitForLogcatLine(processId: Int, fragment: String): String? {
        repeat(LOGCAT_POLL_ATTEMPTS) {
            val line = readEchidnaLogcat().currentProcessLine(processId, fragment)
            if (line != null) {
                return line
            }
            Thread.sleep(LOGCAT_POLL_MS)
        }
        return readEchidnaLogcat().currentProcessLine(processId, fragment)
    }

    private fun String.hasCurrentProcessLine(processId: Int, fragment: String): Boolean {
        return currentProcessLine(processId, fragment) != null
    }

    private fun String.currentProcessLine(processId: Int, fragment: String): String? {
        return lineSequence().firstOrNull { line ->
            val match = LOGCAT_THREADTIME.matchEntire(line.trim())
            val currentPid = match?.groupValues?.get(1)?.toIntOrNull() == processId
            val currentProcess = line.contains("process='$PROBE_PACKAGE'")
            (currentPid || currentProcess) && line.contains(fragment)
        }
    }

    private fun runShellBytes(command: String): ByteArray {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes()
        }
    }

    private data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class TelemetryHookProbe(
        val name: String,
        val library: String,
        val symbol: String,
        val attempts: Int,
        val successes: Int,
    )

    private data class TelemetryProbeSnapshot(
        val totalCallbacks: Long,
        val hooks: List<TelemetryHookProbe>,
    ) {
        fun hasSuccessfulHook(fragment: String): Boolean =
            hooks.any { hook ->
                hook.successes > 0 &&
                    (hook.name.contains(fragment) ||
                        hook.symbol.contains(fragment) ||
                        hook.library.contains(fragment))
            }
    }

    private companion object {
        private const val PROBE_PACKAGE = "com.echidna.interceptionprobe"
        private const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_SAMPLE = 2
        private const val READ_ATTEMPTS = 5
        private const val READ_CHUNK_SAMPLES = 2_048
        private const val SHELL_EXIT_PREFIX = "__ECHIDNA_EXIT__:"
        private const val TELEMETRY_POLL_ATTEMPTS = 10
        private const val TELEMETRY_POLL_MS = 100L
        private const val LOGCAT_POLL_ATTEMPTS = 10
        private const val LOGCAT_POLL_MS = 100L
        private const val TELEMETRY_PATH = "/data/local/tmp/echidna/echidna_telemetry.bin"
        private const val PROBE_EVIDENCE_NAME = "echidna_audio_record_probe.log"
        private val LOGCAT_THREADTIME =
            Regex("""^\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+[A-Z]\s+echidna\s*:\s+(.*)$""")

        private const val TELEMETRY_MAGIC = 0xEDC1DA10.toInt()
        private const val TELEMETRY_VERSION = 2
        private const val HEADER_SIZE = 104
        private const val SAMPLE_RECORD_SIZE = 24
        private const val HOOK_RECORD_SIZE = 192
    }
}
