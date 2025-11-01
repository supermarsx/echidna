package com.echidna.control.service

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

private const val ROOT_TAG = "EchidnaRoot"

/**
 * Thin wrapper for executing commands through `su`.
 */
class RootCommandExecutor(private val suBinary: String = "su") {
    private val sequence = AtomicLong(0)

    fun runCommand(command: String): CommandResult {
        val id = sequence.incrementAndGet()
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf(suBinary, "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val success = exitCode == 0
            if (!success) {
                Log.w(ROOT_TAG, "Command #$id failed ($exitCode): $stderr")
            } else {
                Log.d(ROOT_TAG, "Command #$id succeeded: $stdout")
            }
            CommandResult(success, stdout.trim(), stderr.trim(), exitCode)
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
