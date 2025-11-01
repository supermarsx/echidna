package com.echidna.control.service

import android.content.Context
import android.os.Build
import android.os.SELinux
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val TAG = "EchidnaSelinux"

/**
 * Evaluates whether SELinux tweaks are possible on the current device.
 */
class SelinuxCompatChecker(private val context: Context) {
    fun evaluate(): SelinuxState {
        if (!SELinux.isSELinuxEnabled()) {
            Log.w(TAG, "SELinux appears to be disabled; falling back to permissive flow")
            return SelinuxState.DISABLED
        }
        if (!SELinux.isSELinuxEnforced()) {
            Log.i(TAG, "SELinux is already permissive; native engine may operate without extra policy")
            return SelinuxState.PERMISSIVE
        }

        val policyToolPresent = hasMagiskPolicyBinary()
        val hasRoot = hasRootAccess()

        return if (policyToolPresent && hasRoot) {
            Log.i(TAG, "magiskpolicy available; attempting enforcing mode policy patch")
            SelinuxState.ENFORCING_WITH_POLICY
        } else {
            Log.w(
                TAG,
                "SELinux is enforcing and no policy tool detected; switching to Java-only mode",
            )
            SelinuxState.ENFORCING_JAVA_ONLY
        }
    }

    private fun hasMagiskPolicyBinary(): Boolean {
        val candidates = listOf(
            "/system/bin/magiskpolicy",
            "/system/xbin/magiskpolicy",
            "/sbin/magiskpolicy",
            "/data/adb/magisk/magiskpolicy",
        )
        if (candidates.any { File(it).canExecute() }) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        var process: Process? = null
        return try {
            process = ProcessBuilder("magiskpolicy", "--live", "--help").start()
            process.waitFor()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLine() != null
            }
        } catch (ignored: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    private fun hasRootAccess(): Boolean {
        if (context.packageManager.hasSystemFeature("android.software.device_admin")) {
            return true
        }
        val suCandidates = listOf(
            "/sbin/su",
            "/system/xbin/su",
            "/system/bin/su",
        )
        if (suCandidates.any { File(it).canExecute() }) {
            return true
        }
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "exit"))
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (ignored: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }
}
