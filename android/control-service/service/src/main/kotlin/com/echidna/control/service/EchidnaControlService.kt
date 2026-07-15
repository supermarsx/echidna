package com.echidna.control.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.echidna.control.bridge.EchidnaNative
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal const val PROCESS_RESULT_OK = 0
internal const val PROCESS_RESULT_INVALID_ARGUMENT = -2

/**
 * Validates Binder-owned arrays before JNI and commits output only after complete native success.
 * This prevents truncated output and keeps caller buffers unchanged on failure.
 */
internal fun processBlockValidated(
    input: FloatArray,
    output: FloatArray?,
    frames: Int,
    sampleRate: Int,
    channelCount: Int,
    nativeProcessor: (FloatArray, FloatArray?, Int, Int, Int) -> Int,
): Int {
    if (frames <= 0 || sampleRate <= 0 || channelCount <= 0) {
        return PROCESS_RESULT_INVALID_ARGUMENT
    }
    val requiredSamples = frames.toLong() * channelCount.toLong()
    if (
        requiredSamples <= 0L ||
        requiredSamples > Int.MAX_VALUE.toLong() ||
        input.size.toLong() < requiredSamples ||
        (output != null && output.size.toLong() < requiredSamples)
    ) {
        return PROCESS_RESULT_INVALID_ARGUMENT
    }

    val sampleCount = requiredSamples.toInt()
    val transactionalOutput = output?.let { FloatArray(sampleCount) }
    val result = nativeProcessor(input, transactionalOutput, frames, sampleRate, channelCount)
    if (result == PROCESS_RESULT_OK && output != null && transactionalOutput != null) {
        for (index in 0 until sampleCount) {
            output[index] = transactionalOutput[index]
        }
    }
    return result
}

/**
 * Exposes binder entry points for the companion app while dispatching privileged work
 * onto the root helper.
 */
class EchidnaControlService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val telemetryExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private lateinit var authenticatedTelemetryStore: AuthenticatedTelemetryStore
    private lateinit var syncBridge: ProfileSyncBridge
    private lateinit var profileStore: ProfileStore
    private lateinit var privilegedController: PrivilegedController
    private lateinit var telemetryExporter: TelemetryExporter
    private lateinit var audioStackProbe: AudioStackProbe
    private lateinit var cpuArchProbe: CpuArchProbe
    private lateinit var legacyPreprocessorFlagStore: LegacyPreprocessorFlagStore
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
        authenticatedTelemetryStore = AuthenticatedTelemetryStore()
        syncBridge = ProfileSyncBridge(applicationContext, authenticatedTelemetryStore)
        profileStore = ProfileStore(File(filesDir, "profiles"), syncBridge)
        val rootExecutor = RootCommandExecutor()
        val selinuxChecker = SelinuxCompatChecker(rootExecutor)
        privilegedController = PrivilegedController(rootExecutor, selinuxChecker)
        telemetryExporter = TelemetryExporter(filesDir, authenticatedTelemetryStore)
        audioStackProbe = AudioStackProbe(this)
        cpuArchProbe = CpuArchProbe()
        legacyPreprocessorFlagStore = LegacyPreprocessorFlagStore(applicationContext)
        executor.execute {
            // Runtime policy belongs to the Magisk module's reviewed sepolicy.rule. The app must
            // never widen zygote policy live merely because a policy tool happens to be present.
            privilegedController.refreshStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::profileStore.isInitialized) {
            profileStore.close()
        }
        if (::syncBridge.isInitialized) {
            syncBridge.close()
        }
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

        override fun refreshStatus(): String {
            // Synchronous refresh so the caller reads a fresh combined status.
            return safeBinder("refresh status", fallbackStatusJson("status refresh failed")) {
                buildStatusJson(privilegedController.refreshStatus())
            }
        }

        override fun getModuleStatus(): String {
            return safeBinder("get module status", fallbackStatusJson("status unavailable")) {
                buildStatusJson(privilegedController.lastKnownStatus())
            }
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
            return safeBinder("list profiles", emptyArray()) {
                profileStore.listProfiles().toTypedArray()
            }
        }

        override fun getTelemetrySnapshot(): String {
            return safeBinder("get telemetry snapshot", "{}") {
                telemetryExporter.snapshotJson()
            }
        }

        override fun isTelemetryOptedIn(): Boolean {
            return safeBinder("read telemetry opt-in", false) {
                telemetryExporter.isOptedIn()
            }
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
            safeBinder("set telemetry opt-in", Unit) {
                telemetryExporter.setOptIn(enabled)
            }
        }

        override fun exportTelemetry(includeTrends: Boolean): String {
            return safeBinder("export telemetry", "{}") {
                telemetryExporter.exportAnonymized(includeTrends)
            }
        }

        override fun exportDiagnostics(includeTrends: Boolean): String {
            return safeBinder("export diagnostics", "{}") {
                telemetryExporter.exportDiagnostics(
                    includeTrends = includeTrends,
                    statusJson = buildStatusJson(privilegedController.lastKnownStatus()),
                    whitelistBindingsJson = profileStore.buildWhitelistBindingsJson(),
                    controlStateJson = profileStore.buildControlStateJson()
                )
            }
        }

        override fun setProfile(profile: String?) {
            if (profile.isNullOrEmpty()) {
                return
            }
            val payload = profileStore.resolveProfilePayload(profile)
                ?: profile.takeIf { it.trimStart().startsWith("{") }
            if (payload == null) {
                Log.w(TAG, "Profile '$profile' not found; ignoring request")
                return
            }
            safeBinder("set native profile", Unit) {
                EchidnaNative.setProfile(payload)
            }
        }

        override fun pushProfileSnapshot(profileId: String?, profileJson: String?) {
            if (profileId.isNullOrBlank() || profileJson.isNullOrBlank()) {
                return
            }
            safeBinder("push profile snapshot", Unit) {
                profileStore.saveProfile(profileId, profileJson)
                EchidnaNative.setProfile(profileJson)
            }
        }

        override fun setLatencyModeOverride(profileId: String?, latencyMode: String?) {
            if (profileId.isNullOrBlank() || latencyMode.isNullOrBlank()) return
            safeBinder("set latency override", Unit) {
                profileStore.setLatencyOverride(profileId, latencyMode)
            }
        }

        override fun setAppPresetBinding(packageName: String?, presetId: String?) {
            if (packageName.isNullOrBlank()) return
            safeBinder("set app preset binding", Unit) {
                profileStore.setAppBinding(packageName, presetId.orEmpty())
            }
        }

        override fun synchronizePolicyState(stateJson: String?): Boolean {
            if (stateJson.isNullOrBlank()) return false
            return safeBinder("synchronize v2 policy", false) {
                profileStore.synchronizePolicyState(stateJson)
            }
        }

        override fun getWhitelistBindings(): String {
            return safeBinder("get whitelist bindings", "{\"whitelist\":{},\"appBindings\":{}}") {
                profileStore.buildWhitelistBindingsJson()
            }
        }

        override fun setMasterEnabled(enabled: Boolean) {
            safeBinder("set master enabled", Unit) {
                profileStore.setMasterEnabled(enabled)
            }
        }

        override fun setBypass(bypass: Boolean) {
            safeBinder("set bypass", Unit) {
                profileStore.setBypass(bypass)
            }
        }

        override fun triggerPanic(holdMs: Long) {
            safeBinder("trigger panic", Unit) {
                profileStore.panic(holdMs.coerceIn(0L, 60L * 60L * 1000L))
            }
        }

        override fun setSidetone(enabled: Boolean, gainDb: Float) {
            safeBinder("set sidetone", Unit) {
                profileStore.setSidetone(enabled, gainDb.coerceIn(-60f, -6f).toDouble())
            }
        }

        override fun setEngineMode(engineMode: String?) {
            if (engineMode.isNullOrBlank()) return
            safeBinder("set engine mode", Unit) {
                profileStore.setEngineMode(engineMode)
            }
        }

        override fun getControlState(): String {
            return safeBinder("get control state", "{}") {
                profileStore.buildControlStateJson()
            }
        }

        override fun getStatus(): Int = safeBinder("get native status", 3) {
            EchidnaNative.getStatus()
        }

        override fun processBlock(
            input: FloatArray,
            output: FloatArray?,
            frames: Int,
            sampleRate: Int,
            channelCount: Int
        ): Int = safeBinder("process block", -1) {
            processBlockValidated(input, output, frames, sampleRate, channelCount) {
                    validatedInput,
                    validatedOutput,
                    validatedFrames,
                    validatedSampleRate,
                    validatedChannelCount ->
                EchidnaNative.processBlock(
                    validatedInput,
                    validatedOutput,
                    validatedFrames,
                    validatedSampleRate,
                    validatedChannelCount,
                )
            }
        }

        override fun getApiVersion(): Long = safeBinder("get API version", API_VERSION_UNAVAILABLE) {
            EchidnaNative.getApiVersion()
        }

        override fun isLegacyPreprocessorEnabled(): Boolean {
            if (Binder.getCallingUid() != applicationInfo.uid) return false
            return safeBinder("read legacy preprocessor gate", false) {
                legacyPreprocessorFlagStore.isEnabled()
            }
        }

        override fun setLegacyPreprocessorEnabled(enabled: Boolean): Boolean {
            if (Binder.getCallingUid() != applicationInfo.uid) return false
            return safeBinder("persist legacy preprocessor gate", false) {
                legacyPreprocessorFlagStore.setEnabled(enabled)
            }
        }
    }

    /**
     * Combines real module/SELinux state with a live audio-stack probe into the
     * status JSON the companion app reads (engine status + compatibility wizard).
     * Replaces the app's former fabricated constants. Schema: t2-e6-signatures §3.
     */
    private fun buildStatusJson(status: ModuleStatus): String {
        val json = JSONObject(status.toJson())
        json.put("selinuxStatus", humanReadableSelinux(status.selinuxState))
        json.put("cpu", cpuArchProbe.probe())
        json.put("audioStack", audioStackProbe.probe())
        val notes = statusNotes(status)
        if (notes.isNotEmpty()) {
            json.put("notes", notes)
        }
        return json.toString()
    }

    private fun humanReadableSelinux(state: SelinuxState): String = when (state) {
        SelinuxState.DISABLED -> "Disabled"
        SelinuxState.PERMISSIVE -> "Permissive"
        SelinuxState.ENFORCING -> "Enforcing (policy and capture route unverified)"
    }

    private fun statusNotes(status: ModuleStatus): String = buildList {
        if (status.policyToolAvailable) {
            add("magiskpolicy is available, but this does not prove module policy was applied.")
        } else if (status.selinuxState == SelinuxState.ENFORCING) {
            add("No usable policy tool was verified while SELinux is enforcing.")
        }
        if (!status.magiskModuleInstalled) {
            add("Echidna Magisk module not detected; install it to enable the native engine.")
        } else if (!status.zygiskEnabled) {
            add("Zygisk is disabled; enable it in Magisk to hook target apps.")
        }
        if (!status.nativeRouteVerified) {
            add("No recent transformed-buffer proof is available; runtime capture remains unverified.")
        }
        if (status.javaFallbackRecommended) {
            add("LSPosed compatibility mode is recommended until a native route is verified.")
        }
    }.joinToString(" ")

    private fun dispatchPrivileged(action: () -> ModuleStatus) {
        executor.execute {
            val status = try {
                action.invoke()
            } catch (exception: Exception) {
                Log.e(TAG, "Privileged action failed", exception)
                ModuleStatus(
                    magiskModuleInstalled = false,
                    zygiskEnabled = false,
                    selinuxState = SelinuxState.DISABLED,
                    policyToolAvailable = false,
                    policyAppliedVerified = false,
                    nativeRouteVerified = false,
                    javaFallbackRecommended = true,
                    lastError = exception.message ?: "privileged action failed",
                )
            }
            if (status.javaFallbackRecommended) {
                Log.w(TAG, "Native route unverified; Java compatibility mode recommended")
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
            try {
                val payload = telemetryExporter.snapshotJson()
                broadcastTelemetry(payload)
            } catch (exception: Exception) {
                Log.w(TAG, "Telemetry streaming tick failed", exception)
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopTelemetryStreaming() {
        telemetryTask?.cancel(true)
        telemetryTask = null
    }

    private fun broadcastTelemetry(payload: String) {
        val count = telemetryCallbacks.beginBroadcast()
        try {
            if (count == 0) {
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
        } finally {
            telemetryCallbacks.finishBroadcast()
        }
    }

    private inline fun <T> safeBinder(label: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (exception: Exception) {
            Log.e(TAG, "Binder $label failed", exception)
            fallback
        }
    }

    private fun fallbackStatusJson(message: String): String {
        val json = JSONObject()
        json.put("magiskModuleInstalled", false)
        json.put("zygiskEnabled", false)
        json.put("selinuxState", SelinuxState.DISABLED.name)
        json.put("selinuxStatus", humanReadableSelinux(SelinuxState.DISABLED))
        json.put("policyToolAvailable", false)
        json.put("policyAppliedVerified", false)
        json.put("nativeRouteVerified", false)
        json.put("javaFallbackRecommended", true)
        json.put("lastError", message)
        json.put("notes", message)
        return json.toString()
    }

    companion object {
        private const val TAG = "EchidnaControlSvc"
        private const val API_VERSION_UNAVAILABLE = 0L
    }
}
