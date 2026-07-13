package com.echidna.control.service

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val ROOT_TAG = "EchidnaRoot"

/**
 * Thin wrapper for executing commands through `su`.
 */
class RootCommandExecutor(private val suBinary: String = "su") {
    private val sequence = AtomicLong(0)

    fun runCommand(command: String): CommandResult {
        return runCommandInternal(arrayOf(suBinary, "-c", command))
    }

    fun runCommand(arguments: List<String>): CommandResult {
        require(arguments.isNotEmpty()) { "Command arguments cannot be empty" }
        val quoted = arguments.joinToString(separator = " ") { it.shellQuote() }
        return runCommand(quoted)
    }

    private fun runCommandInternal(command: Array<String>): CommandResult {
        val id = sequence.incrementAndGet()
        var process: Process? = null
        var stdoutText = ""
        var stderrText = ""
        return try {
            process = ProcessBuilder(*command).redirectErrorStream(false).start()
            val runningProcess = process!!
            val stdoutThread = thread(name = "echidna-root-$id-out") {
                stdoutText = runningProcess.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrThread = thread(name = "echidna-root-$id-err") {
                stderrText = runningProcess.errorStream.bufferedReader().use { it.readText() }
            }
            val exitCode = runningProcess.waitFor()
            stdoutThread.join()
            stderrThread.join()
            val success = exitCode == 0
            val stdout = stdoutText.trim()
            val stderr = stderrText.trim()
            if (!success) {
                Log.w(ROOT_TAG, "Command #$id failed ($exitCode): $stderr")
            } else {
                Log.d(ROOT_TAG, "Command #$id succeeded: $stdout")
            }
            CommandResult(success, stdout, stderr, exitCode)
        } catch (e: Exception) {
            Log.e(ROOT_TAG, "Command #$id threw exception", e)
            CommandResult(false, "", e.message ?: "")
        } finally {
            process?.destroy()
        }
    }
}

data class CommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int = if (success) 0 else -1,
)

private fun String.shellQuote(): String {
    if (isEmpty()) {
        return "''"
    }
    val builder = StringBuilder(length + 2)
    builder.append('\'')
    for (char in this) {
        if (char == '\'') {
            builder.append("'\\''")
        } else {
            builder.append(char)
        }
    }
    builder.append('\'')
    return builder.toString()
}
