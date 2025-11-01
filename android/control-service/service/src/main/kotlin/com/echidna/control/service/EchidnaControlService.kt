package com.echidna.control.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Exposes binder entry points for the companion app while dispatching privileged work
 * onto the root helper.
 */
class EchidnaControlService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var profileStore: ProfileStore
    private lateinit var privilegedController: PrivilegedController

    override fun onCreate() {
        super.onCreate()
        val syncBridge = ProfileSyncBridge()
        profileStore = ProfileStore(File(filesDir, "profiles"), syncBridge)
        val selinuxChecker = SelinuxCompatChecker(applicationContext)
        privilegedController = PrivilegedController(RootCommandExecutor(), selinuxChecker)
        executor.execute {
            val selinuxState = privilegedController.applySelinuxTweaks()
            if (selinuxState == SelinuxState.ENFORCING_JAVA_ONLY) {
                Log.w(
                    TAG,
                    "Device SELinux policy blocks native engine; defaulting to Java compatibility mode",
                )
            }
            privilegedController.refreshStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        profileStore.close()
        executor.shutdownNow()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEchidnaControlService.Stub() {
        override fun installModule(archivePath: String?) {
            dispatchPrivileged { privilegedController.installModule(archivePath) }
        }

        override fun uninstallModule() {
            dispatchPrivileged { privilegedController.uninstallModule() }
        }

        override fun refreshStatus() {
            dispatchPrivileged { privilegedController.refreshStatus() }
        }

        override fun getModuleStatus(): String {
            return privilegedController.lastKnownStatus().toJson()
        }

        override fun updateWhitelist(processName: String?, enabled: Boolean) {
            if (!processName.isNullOrBlank()) {
                profileStore.updateWhitelist(processName, enabled)
            }
        }

        override fun pushProfile(profileId: String?, profileJson: String?) {
            if (!profileId.isNullOrBlank() && !profileJson.isNullOrBlank()) {
                profileStore.saveProfile(profileId, profileJson)
            }
        }

        override fun listProfiles(): Array<String> {
            return profileStore.listProfiles().toTypedArray()
        }
    }

    private fun dispatchPrivileged(action: () -> ModuleStatus) {
        executor.execute {
            val status = action.invoke()
            if (status.javaFallbackActive) {
                Log.w(TAG, "Native engine unavailable; Java-only mode active: ${status.lastError}")
            } else {
                Log.d(TAG, "Native engine status: ${status.toJson()}")
            }
        }
    }

    companion object {
        private const val TAG = "EchidnaControlSvc"
    }
}
