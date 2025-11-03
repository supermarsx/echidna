package com.echidna.control.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.echidna.control.bridge.EchidnaNative
import kotlin.math.min
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Exposes binder entry points for the companion app while dispatching privileged work
 * onto the root helper.
 */
class EchidnaControlService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val telemetryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private lateinit var profileStore: ProfileStore
    private lateinit var privilegedController: PrivilegedController
    private lateinit var telemetryExporter: TelemetryExporter
    private val telemetryCallbacks = object : RemoteCallbackList<IEchidnaTelemetryListener>() {
        override fun onCallbackDied(callback: IEchidnaTelemetryListener?) {
            telemetryListenerCount = (telemetryListenerCount - 1).coerceAtLeast(0)
            if (telemetryListenerCount == 0) {
                stopTelemetryStreaming()
            }
        }
    }
    @Volatile private var telemetryListenerCount = 0
    private var telemetryTask: ScheduledFuture<*>? = null

    override fun onCreate() {
        super.onCreate()
        val syncBridge = ProfileSyncBridge()
        profileStore = ProfileStore(File(filesDir, "profiles"), syncBridge)
        val rootExecutor = RootCommandExecutor()
        val selinuxChecker = SelinuxCompatChecker(rootExecutor)
        privilegedController = PrivilegedController(rootExecutor, selinuxChecker)
        telemetryExporter = TelemetryExporter(filesDir)
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
        telemetryTask?.cancel(true)
        telemetryExecutor.shutdownNow()
        telemetryCallbacks.kill()
        telemetryListenerCount = 0
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

        override fun getTelemetrySnapshot(): String {
            return telemetryExporter.snapshotJson()
        }

        override fun isTelemetryOptedIn(): Boolean {
            return telemetryExporter.isOptedIn()
        }

        override fun registerTelemetryListener(listener: IEchidnaTelemetryListener?) {
            if (listener == null) return
            telemetryCallbacks.register(listener)
            telemetryListenerCount += 1
            startTelemetryStreaming()
        }

        override fun unregisterTelemetryListener(listener: IEchidnaTelemetryListener?) {
            if (listener == null) return
            telemetryCallbacks.unregister(listener)
            telemetryListenerCount = (telemetryListenerCount - 1).coerceAtLeast(0)
            if (telemetryListenerCount == 0) {
                stopTelemetryStreaming()
            }
        }

        override fun setTelemetryOptIn(enabled: Boolean) {
            telemetryExporter.setOptIn(enabled)
        }

        override fun exportTelemetry(includeTrends: Boolean): String {
            return telemetryExporter.exportAnonymized(includeTrends)
        }

        override fun setProfile(profile: String?) {
            if (profile.isNullOrEmpty()) {
                return
            }
            EchidnaNative.setProfile(profile)
        }

        override fun getStatus(): Int = EchidnaNative.getStatus()

        override fun processBlock(
            input: FloatArray,
            output: FloatArray?,
            frames: Int,
            sampleRate: Int,
            channelCount: Int
        ): Int {
            if (frames <= 0 || sampleRate <= 0 || channelCount <= 0) {
                return -2
            }
            val requiredSamples = frames * channelCount
            if (input.size < requiredSamples) {
                return -2
            }
            val tempOutput = output?.let { FloatArray(requiredSamples) }
            val result = EchidnaNative.processBlock(input, tempOutput, frames, sampleRate, channelCount)
            if (output != null && tempOutput != null) {
                val limit = min(output.size, requiredSamples)
                for (index in 0 until limit) {
                    output[index] = tempOutput[index]
                }
            }
            return result
        }

        override fun getApiVersion(): Long = EchidnaNative.getApiVersion()
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

    private fun startTelemetryStreaming() {
        if (telemetryTask != null && !telemetryTask!!.isCancelled) {
            return
        }
        telemetryTask = telemetryExecutor.scheduleAtFixedRate({
            val payload = telemetryExporter.snapshotJson()
            broadcastTelemetry(payload)
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopTelemetryStreaming() {
        telemetryTask?.cancel(true)
        telemetryTask = null
    }

    private fun broadcastTelemetry(payload: String) {
        val count = telemetryCallbacks.beginBroadcast()
        if (count == 0) {
            telemetryCallbacks.finishBroadcast()
            stopTelemetryStreaming()
            return
        }
        for (i in 0 until count) {
            try {
                telemetryCallbacks.getBroadcastItem(i)?.onTelemetry(payload)
            } catch (ex: RemoteException) {
                Log.w(TAG, "Telemetry callback failed", ex)
            }
        }
        telemetryCallbacks.finishBroadcast()
    }

    companion object {
        private const val TAG = "EchidnaControlSvc"
    }
}
