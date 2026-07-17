package com.echidna.control.service

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val ROOT_TAG = "EchidnaRoot"

/**
 * Command surface used by privileged control paths.
 */
interface PrivilegedCommandRunner {
    fun runCommand(command: String): CommandResult
    fun runCommand(arguments: List<String>): CommandResult
}

/**
 * Thin wrapper for executing commands through `su`.
 */
class RootCommandExecutor(
    private val suBinary: String = "su",
    private val timeoutSeconds: Long = 60,
) : PrivilegedCommandRunner {
    private val sequence = AtomicLong(0)

    override fun runCommand(command: String): CommandResult {
        if (command.isBlank()) {
            return CommandResult(false, "", "command cannot be blank")
        }
        return runCommandInternal(arrayOf(suBinary, "-c", command))
    }

    override fun runCommand(arguments: List<String>): CommandResult {
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

/**
 * Wraps a [PrivilegedCommandRunner] with a one-time root-availability gate (t17).
 *
 * The first privileged command probes root exactly once (a single `su` invocation → at most one
 * Magisk grant prompt) and caches the decision. On denial/unavailability every subsequent command
 * short-circuits to a failure result WITHOUT spawning `su` again, so a device that never granted
 * root is not re-prompted on each detection pass. This is what stops the first-run su grant loop:
 * a burst of privileged probes now costs a single prompt, and a denial is remembered rather than
 * re-asked. `su`'s own policy persistence keeps a granted device prompt-free thereafter.
 */
class CachedRootCommandRunner(
    private val delegate: PrivilegedCommandRunner,
    // Probes root once via the delegate. `id` returns 0 only from a granted root shell.
    private val rootProbe: (PrivilegedCommandRunner) -> Boolean = {
        it.runCommand(listOf("id")).success
    },
) : PrivilegedCommandRunner {
    // null = not yet attempted; true = granted; false = denied/unavailable (do not re-prompt).
    @Volatile private var rootGranted: Boolean? = null
    private val gateLock = Any()

    override fun runCommand(command: String): CommandResult =
        if (ensureRootAvailable()) delegate.runCommand(command) else ROOT_UNAVAILABLE

    override fun runCommand(arguments: List<String>): CommandResult =
        if (ensureRootAvailable()) delegate.runCommand(arguments) else ROOT_UNAVAILABLE

    private fun ensureRootAvailable(): Boolean {
        rootGranted?.let { return it }
        synchronized(gateLock) {
            rootGranted?.let { return it }
            val granted = rootProbe(delegate)
            rootGranted = granted
            if (!granted) {
                Log.w(ROOT_TAG, "Root unavailable/denied; suppressing further su invocations")
            }
            return granted
        }
    }

    private companion object {
        val ROOT_UNAVAILABLE = CommandResult(false, "", "root access unavailable", -1)
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
