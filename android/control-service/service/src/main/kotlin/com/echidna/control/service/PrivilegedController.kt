package com.echidna.control.service

import android.util.Log

private const val PRIV_TAG = "EchidnaPriv"
private const val MODULE_ID = "echidna"
private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
private const val ZYGISK_NEXT_MODULE_ID = "zygisksu"
private const val REZYGISK_MODULE_ID = "rezygisk"

private val STANDALONE_ZYGISK_PROBE = """
    for prop in /data/adb/modules/*/module.prop; do
      [ -f "${'$'}prop" ] || continue
      dir="${'$'}{prop%/module.prop}"
      [ -e "${'$'}dir/disable" ] && continue
      if grep -Eiq '^id=(${ZYGISK_NEXT_MODULE_ID}|${REZYGISK_MODULE_ID})' "${'$'}prop"; then
        exit 0
      fi
      if grep -Eiq '^name=.*(Zygisk Next|ReZygisk)' "${'$'}prop"; then
        exit 0
      fi
      if grep -Eiq '^description=.*Standalone implementation of Zygisk' "${'$'}prop"; then
        exit 0
      fi
    done
    exit 1
""".trimIndent()

/**
 * Bridges Binder requests with privileged Magisk/root operations.
 */
class PrivilegedController(
    private val rootExecutor: PrivilegedCommandRunner,
    private val selinuxChecker: SelinuxStateProbe,
) {
    @Volatile
    private var cachedStatus: ModuleStatus = ModuleStatus(
        magiskModuleInstalled = false,
        zygiskEnabled = false,
        selinuxState = SelinuxState.DISABLED,
        policyToolAvailable = false,
        policyAppliedVerified = false,
        nativeRouteVerified = false,
        javaFallbackRecommended = true,
        lastError = "status not yet queried",
    )

    fun installModule(moduleArchivePath: String?): ModuleStatus {
        if (moduleArchivePath.isNullOrBlank()) {
            Log.w(PRIV_TAG, "Cannot install module: archive path is empty")
            return updateStatus(lastError = "module archive missing")
        }
        val sanitizedPath = moduleArchivePath.trim()
        val result = rootExecutor.runCommand(
            listOf("magisk", "--install-module", sanitizedPath),
        )
        if (!result.success) {
            return updateStatus(lastError = "install failed: ${result.stderr}")
        }
        Log.i(PRIV_TAG, "Requested Magisk installation from $moduleArchivePath")
        return refreshStatus()
    }

    /**
     * Writes the Magisk `disable` marker so Zygisk stops loading the module on the next boot. A
     * live Zygisk module cannot be hot-unloaded, so this is the unload-first step that both the
     * install (clean-slate before overwrite) and uninstall flows run before touching the module.
     * Returns true only when the marker is confirmed present, so a failing disable can abort the
     * flow honestly instead of leaving the module active-but-being-removed.
     */
    fun disableModule(): Boolean {
        // Guard on the module directory: touch fails if the module was never installed. When it
        // does not exist there is nothing loaded to disable, which is a successful no-op.
        val result = rootExecutor.runCommand(
            listOf(
                "sh",
                "-c",
                "if [ -d $MODULE_DIR ]; then touch $MODULE_DIR/disable && " +
                    "test -f $MODULE_DIR/disable; else exit 0; fi",
            ),
        )
        if (!result.success) {
            Log.w(PRIV_TAG, "Failed to write module disable marker: ${result.stderr}")
            updateStatus(lastError = "disable failed: ${result.stderr}")
        } else {
            Log.i(PRIV_TAG, "Wrote Magisk disable marker for $MODULE_ID")
        }
        return result.success
    }

    /**
     * Best-effort privileged reboot to complete the load/unload. Prefers the graceful
     * `svc power reboot`, falling back to the plain `reboot` binary. The process may be torn down
     * mid-command as the system goes down, so the return value is advisory only.
     */
    fun rebootDevice(): Boolean {
        val graceful = rootExecutor.runCommand(listOf("svc", "power", "reboot"))
        if (graceful.success) {
            Log.i(PRIV_TAG, "Requested reboot via svc power reboot")
            return true
        }
        val fallback = rootExecutor.runCommand(listOf("reboot"))
        if (fallback.success) {
            Log.i(PRIV_TAG, "Requested reboot via reboot binary")
        } else {
            Log.w(PRIV_TAG, "Reboot request failed: ${fallback.stderr}")
        }
        return fallback.success
    }

    fun uninstallModule(): ModuleStatus {
        val result = rootExecutor.runCommand(
            listOf("magisk", "--remove-modules", MODULE_ID),
        )
        if (!result.success) {
            return updateStatus(lastError = "uninstall failed: ${result.stderr}")
        }
        Log.i(PRIV_TAG, "Requested Magisk removal for $MODULE_ID")
        return refreshStatus()
    }

    fun refreshStatus(): ModuleStatus {
        val (installed, installError) = queryModuleInstallationState()
        val zygiskActive = queryZygisk()
        val selinux = selinuxChecker.evaluate()
        val status = ModuleStatus(
            magiskModuleInstalled = installed,
            zygiskEnabled = zygiskActive,
            selinuxState = selinux.state,
            policyToolAvailable = selinux.policyToolAvailable,
            // Tool presence is not evidence that the module policy was applied successfully.
            policyAppliedVerified = false,
            // A route becomes verified only from transformed-buffer runtime telemetry.
            nativeRouteVerified = false,
            javaFallbackRecommended = selinux.state == SelinuxState.ENFORCING,
            lastError = installError,
        )
        cachedStatus = status
        return status
    }

    fun lastKnownStatus(): ModuleStatus = cachedStatus

    private fun updateStatus(lastError: String? = null): ModuleStatus {
        val refreshed = cachedStatus.copy(lastError = lastError ?: cachedStatus.lastError)
        cachedStatus = refreshed
        return refreshed
    }

    private fun queryZygisk(): Boolean {
        if (queryMagiskBuiltinZygisk()) {
            return true
        }
        return queryStandaloneZygiskModule()
    }

    private fun queryMagiskBuiltinZygisk(): Boolean {
        // `magisk --zygisk` is not a valid applet on any Magisk release, so it
        // always failed. The authoritative Zygisk state lives in Magisk's
        // settings table (key `zygisk`, value 1 = enabled). Read it via the
        // stable `magisk --sqlite` interface, which is version-robust across
        // Magisk 24+ (Zygisk requires 24+).
        val result = rootExecutor.runCommand(
            listOf("magisk", "--sqlite", "SELECT value FROM settings WHERE key='zygisk'"),
        )
        if (!result.success) {
            Log.w(PRIV_TAG, "Unable to query Zygisk: ${result.stderr}")
            return false
        }
        // `magisk --sqlite` prints matching rows as `key=value` (e.g. `value=1`).
        // An empty result means the row was never written, which Magisk treats
        // as the default-enabled state once Zygisk has been toggled on; be
        // conservative and only report enabled on an explicit value=1.
        val out = result.stdout.trim()
        val value = out
            .lineSequence()
            .mapNotNull { line ->
                line.substringAfter("value=", missingDelimiterValue = "")
                    .takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
            ?.trim()
        return value == "1"
    }

    private fun queryStandaloneZygiskModule(): Boolean {
        val result = rootExecutor.runCommand(STANDALONE_ZYGISK_PROBE)
        if (result.success) {
            Log.i(PRIV_TAG, "Standalone Zygisk module detected")
            return true
        }
        if (result.exitCode != 1 || result.stderr.isNotEmpty()) {
            val detail = result.stderr.ifEmpty { "exit ${result.exitCode}" }
            Log.d(
                PRIV_TAG,
                "Standalone Zygisk probe unavailable: $detail",
            )
        }
        return false
    }

    private fun queryModuleInstallationState(): Pair<Boolean, String?> {
        val result = rootExecutor.runCommand(
            listOf("test", "-f", "/data/adb/modules/$MODULE_ID/module.prop"),
        )
        if (result.exitCode == 0) {
            return true to null
        }
        if (result.exitCode == 1) {
            return false to null
        }
        val message = if (result.stderr.isNotEmpty()) {
            "unable to verify module installation: ${result.stderr}"
        } else {
            "unable to verify module installation (exit ${result.exitCode})"
        }
        Log.w(PRIV_TAG, message)
        return false to message
    }
}
