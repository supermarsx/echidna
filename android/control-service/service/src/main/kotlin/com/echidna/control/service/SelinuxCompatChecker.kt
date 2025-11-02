package com.echidna.control.service

import android.os.SELinux
import android.util.Log
import java.io.File

private const val TAG = "EchidnaSelinux"

/**
 * Evaluates whether SELinux tweaks are possible on the current device.
 */
class SelinuxCompatChecker(private val rootExecutor: RootCommandExecutor) {
    fun evaluate(): SelinuxState {
        if (!SELinux.isSELinuxEnabled()) {
            Log.w(TAG, "SELinux appears to be disabled; falling back to permissive flow")
            return SelinuxState.DISABLED
        }
        if (!SELinux.isSELinuxEnforced()) {
            Log.i(TAG, "SELinux is already permissive; native engine may operate without extra policy")
            return SelinuxState.PERMISSIVE
        }

        val hasRoot = hasRootAccess()
        val policyToolPresent = if (hasRoot) hasMagiskPolicyBinary() else false

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
        for (candidate in candidates) {
            val result = rootExecutor.runCommand(listOf("test", "-x", candidate))
            if (result.success) {
                Log.d(TAG, "magiskpolicy detected at $candidate")
                return true
            }
        }

        val helpResult = rootExecutor.runCommand(listOf("magiskpolicy", "--live", "--help"))
        if (helpResult.success) {
            Log.d(TAG, "magiskpolicy responded to --help probe")
            return true
        }

        if (helpResult.stderr.isNotEmpty()) {
            Log.d(TAG, "magiskpolicy probe failed: ${helpResult.stderr}")
        }
        return false
    }

    private fun hasRootAccess(): Boolean {
        val suCandidates = listOf(
            "/sbin/su",
            "/system/xbin/su",
            "/system/bin/su",
        )
        if (suCandidates.any { File(it).exists() }) {
            Log.d(TAG, "su binary located; verifying root shell availability")
        }
        val result = rootExecutor.runCommand(listOf("id"))
        if (!result.success) {
            Log.w(TAG, "Root shell unavailable: ${result.stderr.ifEmpty { "exit ${result.exitCode}" }}")
        }
        return result.success
    }
}
