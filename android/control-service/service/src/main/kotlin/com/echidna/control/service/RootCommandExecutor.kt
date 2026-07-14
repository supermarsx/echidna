package com.echidna.control.service

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val ROOT_TAG = "EchidnaRoot"

/**
 * Thin wrapper for executing commands through `su`.
 */
class RootCommandExecutor(
    private val suBinary: String = "su",
    private val timeoutSeconds: Long = 60,
) {
    private val sequence = AtomicLong(0)

    fun runCommand(command: String): CommandResult {
        if (command.isBlank()) {
            return CommandResult(false, "", "command cannot be blank")
        }
        return runCommandInternal(arrayOf(suBinary, "-c", command))
    }

    fun runCommand(arguments: List<String>): CommandResult {
        if (arguments.isEmpty()) {
            return CommandResult(false, "", "command arguments cannot be empty")
        }
        val quoted = arguments.joinToString(separator = " ") { it.shellQuote() }
        return runCommand(quoted)
    }

    private fun runCommandInternal(command: Array<String>): CommandResult {
        val id = sequence.incrementAndGet()
        var process: Process? = null
        var stdoutText = ""
        var stderrText = ""
        return try {
            val runningProcess = ProcessBuilder(*command).redirectErrorStream(false).start()
            process = runningProcess
            val stdoutThread = thread(name = "echidna-root-$id-out") {
                stdoutText = runningProcess.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrThread = thread(name = "echidna-root-$id-err") {
                stderrText = runningProcess.errorStream.bufferedReader().use { it.readText() }
            }
            val completed = runningProcess.waitFor(timeoutSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
            if (!completed) {
                runningProcess.destroyForcibly()
                stdoutThread.join(500)
                stderrThread.join(500)
                Log.w(ROOT_TAG, "Command #$id timed out after $timeoutSeconds seconds")
                return CommandResult(false, stdoutText.trim(), "command timed out", -1)
            }
            val exitCode = runningProcess.exitValue()
            stdoutThread.join(1000)
            stderrThread.join(1000)
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
            Log.e(ROOT_TAG, "Command #$id failed before completion", e)
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
