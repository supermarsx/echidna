package com.echidna.control.service

import android.util.Log
import java.io.File

private const val PRIV_TAG = "EchidnaPriv"
private const val MODULE_ID = "echidna-control"

/**
 * Bridges Binder requests with privileged Magisk/root operations.
 */
class PrivilegedController(
    private val rootExecutor: RootCommandExecutor,
    private val selinuxChecker: SelinuxCompatChecker,
) {
    @Volatile
    private var cachedStatus: ModuleStatus = ModuleStatus(
        magiskModuleInstalled = false,
        zygiskEnabled = false,
        selinuxState = SelinuxState.DISABLED,
        javaFallbackActive = true,
        lastError = "status not yet queried",
    )

    fun installModule(moduleArchivePath: String?): ModuleStatus {
        if (moduleArchivePath.isNullOrBlank()) {
            Log.w(PRIV_TAG, "Cannot install module: archive path is empty")
            return updateStatus(lastError = "module archive missing")
        }
        val escapedPath = moduleArchivePath.trim().replace("\"", "\\\"")
        val command = "magisk --install-module \"$escapedPath\""
        val result = rootExecutor.runCommand(command)
        if (!result.success) {
            return updateStatus(lastError = "install failed: ${result.stderr}")
        }
        Log.i(PRIV_TAG, "Requested Magisk installation from $moduleArchivePath")
        applySelinuxTweaks()
        return refreshStatus()
    }

    fun uninstallModule(): ModuleStatus {
        val command = "magisk --remove-modules $MODULE_ID"
        val result = rootExecutor.runCommand(command)
        if (!result.success) {
            return updateStatus(lastError = "uninstall failed: ${result.stderr}")
        }
        Log.i(PRIV_TAG, "Requested Magisk removal for $MODULE_ID")
        return refreshStatus()
    }

    fun refreshStatus(): ModuleStatus {
        val installed = File("/data/adb/modules/$MODULE_ID/module.prop").exists()
        val zygiskActive = queryZygisk()
        val selinuxState = selinuxChecker.evaluate()
        val status = ModuleStatus(
            magiskModuleInstalled = installed,
            zygiskEnabled = zygiskActive,
            selinuxState = selinuxState,
            javaFallbackActive = selinuxState == SelinuxState.ENFORCING_JAVA_ONLY,
            lastError = null,
        )
        cachedStatus = status
        return status
    }

    fun lastKnownStatus(): ModuleStatus = cachedStatus

    fun applySelinuxTweaks(): SelinuxState {
        val state = selinuxChecker.evaluate()
        if (state != SelinuxState.ENFORCING_WITH_POLICY) {
            return state
        }
        val policyCommand = buildString {
            append("magiskpolicy --live ")
            append("'allow zygote zygote process dyntransition' ")
            append("'allow zygote zygote binder call' ")
            append("'allow zygote zygote binder transfer'")
        }
        val result = rootExecutor.runCommand(policyCommand)
        if (!result.success) {
            Log.w(PRIV_TAG, "Failed to apply SELinux policy: ${result.stderr}")
            return SelinuxState.ENFORCING_JAVA_ONLY
        }
        Log.i(PRIV_TAG, "SELinux policy patch applied successfully")
        return SelinuxState.ENFORCING_WITH_POLICY
    }

    private fun updateStatus(lastError: String? = null): ModuleStatus {
        val refreshed = cachedStatus.copy(lastError = lastError ?: cachedStatus.lastError)
        cachedStatus = refreshed
        return refreshed
    }

    private fun queryZygisk(): Boolean {
        val result = rootExecutor.runCommand("magisk --zygisk")
        if (!result.success) {
            Log.w(PRIV_TAG, "Unable to query Zygisk: ${result.stderr}")
            return false
        }
        return result.stdout.contains("enabled", ignoreCase = true)
    }
}
