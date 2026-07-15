package com.echidna.control.service

import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "EchidnaSelinux"

// selinuxfs is mounted here when SELinux is enabled in the kernel; the `enforce`
// node holds "1" (enforcing) or "0" (permissive) and is world-readable.
private const val SELINUX_FS = "/sys/fs/selinux"
private const val SELINUX_ENFORCE = "$SELINUX_FS/enforce"

/**
 * Evaluates whether SELinux tweaks are possible on the current device.
 */
class SelinuxCompatChecker(private val rootExecutor: PrivilegedCommandRunner) {
    fun evaluate(): SelinuxState {
        if (!isSelinuxEnabled()) {
            Log.w(TAG, "SELinux appears to be disabled; falling back to permissive flow")
            return SelinuxState.DISABLED
        }
        if (!isSelinuxEnforcing()) {
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

    /**
     * True when SELinux is present on this kernel. Detected by the selinuxfs mount; if the
     * mount cannot be stat'd we assume SELinux is present (the conservative, fail-safe choice).
     */
    private fun isSelinuxEnabled(): Boolean {
        return try {
            File(SELINUX_FS).exists()
        } catch (e: SecurityException) {
            Log.d(TAG, "Cannot stat $SELINUX_FS; assuming SELinux present", e)
            true
        }
    }

    /**
     * True when SELinux is in enforcing mode. Reads the world-readable `enforce` node first and
     * falls back to `getenforce`. If neither yields a definite answer, assumes enforcing so the
     * caller takes the most restrictive (Java-only) path rather than a wrongly-permissive one.
     */
    private fun isSelinuxEnforcing(): Boolean {
        readEnforceFromSysfs()?.let { return it }
        readEnforceFromCommand()?.let { return it }
        Log.w(TAG, "Could not determine SELinux mode; assuming enforcing")
        return true
    }

    private fun readEnforceFromSysfs(): Boolean? {
        return try {
            val file = File(SELINUX_ENFORCE)
            if (!file.canRead()) {
                return null
            }
            when (file.readText().trim()) {
                "1" -> true
                "0" -> false
                else -> null
            }
        } catch (e: IOException) {
            Log.d(TAG, "Failed reading $SELINUX_ENFORCE", e)
            null
        } catch (e: SecurityException) {
            Log.d(TAG, "Denied reading $SELINUX_ENFORCE", e)
            null
        }
    }

    private fun readEnforceFromCommand(): Boolean? {
        val result = rootExecutor.runCommand(listOf("getenforce"))
        if (!result.success) {
            return null
        }
        return when (result.stdout.trim().lowercase()) {
            "enforcing" -> true
            "permissive", "disabled" -> false
            else -> null
        }
    }
}
