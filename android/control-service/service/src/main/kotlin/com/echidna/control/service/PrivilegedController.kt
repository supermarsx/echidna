package com.echidna.control.service

import android.util.Log

private const val PRIV_TAG = "EchidnaPriv"
private const val MODULE_ID = "echidna"
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
        javaFallbackActive = true,
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
        val selinuxState = selinuxChecker.evaluate()
        val status = ModuleStatus(
            magiskModuleInstalled = installed,
            zygiskEnabled = zygiskActive,
            selinuxState = selinuxState,
            javaFallbackActive = selinuxState == SelinuxState.ENFORCING_JAVA_ONLY,
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
