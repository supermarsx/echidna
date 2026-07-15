package com.echidna.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.DeadObjectException
import android.os.RemoteException
import android.util.Log
import com.echidna.control.service.EchidnaControlService
import com.echidna.control.service.IEchidnaControlService
import com.echidna.control.service.IEchidnaTelemetryListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Complete app-owned state that must reach the in-process control service together.
 *
 * Keeping this as one immutable value lets connection recovery coalesce rapid UI changes and
 * replay only the newest preset/control state after an asynchronous bind or service restart.
 */
data class ControlServiceSyncSnapshot(
    val policyStateJson: String?,
    val telemetryOptIn: Boolean,
)

class ControlServiceClient(private val context: Context) {
    // The control service is hosted inside THIS APK (com.echidna.app); bind the
    // in-app component rather than the former phantom com.echidna.control package.
    private val intent = Intent().apply {
        component = ComponentName(context, EchidnaControlService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val bound = AtomicBoolean(false)
    private val bindRequested = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0L)
    private val syncVersion = AtomicLong(0L)
    private val latestSync = AtomicReference<VersionedSync?>(null)
    private val syncLock = Any()
    @Volatile private var service: IEchidnaControlService? = null
    @Volatile private var appliedGeneration = -1L
    @Volatile private var appliedVersion = -1L
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    private val _telemetryUpdates = MutableSharedFlow<String>(replay = 1)
    val telemetryUpdates: SharedFlow<String> = _telemetryUpdates

    private val listener = object : IEchidnaTelemetryListener.Stub() {
        override fun onTelemetry(telemetryJson: String?) {
            if (telemetryJson == null) return
            scope.launch {
                _telemetryUpdates.emit(telemetryJson)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connectedService = IEchidnaControlService.Stub.asInterface(binder)
            if (connectedService == null || !bindRequested.get()) {
                markDisconnected()
                releaseBinding()
                return
            }
            if (bound.get() && service?.asBinder() === connectedService.asBinder()) {
                return
            }
            service = connectedService
            bound.set(true)
            _connectionState.value = true
            connectionGeneration.incrementAndGet()
            // ServiceConnection callbacks run on the main thread. Replay on the IO scope because
            // an in-process AIDL interface executes synchronously on its caller's thread.
            scope.launch {
                try {
                    connectedService.registerTelemetryListener(listener)
                } catch (ex: RemoteException) {
                    Log.e(TAG, "Failed to register telemetry listener", ex)
                }
                flushLatestSync()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            markDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            markDisconnected()
            releaseBinding()
            bind()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "Control service returned a null binding")
            markDisconnected()
            releaseBinding()
        }
    }

    /** Starts at most one outstanding bind. Returns false when Android rejects it immediately. */
    fun bind(): Boolean {
        if (bound.get() || !bindRequested.compareAndSet(false, true)) {
            return bound.get() || bindRequested.get()
        }
        val accepted = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Failed to bind control service", exception)
            false
        }
        if (!accepted) {
            bindRequested.set(false)
            markDisconnected()
        }
        return accepted
    }

    fun isBound(): Boolean = bound.get()

    fun unbind() {
        if (bindRequested.get()) {
            try {
                service?.unregisterTelemetryListener(listener)
            } catch (ex: RemoteException) {
                Log.w(TAG, "Failed to unregister telemetry listener", ex)
            }
            releaseBinding()
        }
        markDisconnected()
    }

    fun shutdown() {
        unbind()
        scope.cancel()
    }

    fun fetchSnapshot(): String? {
        return try {
            service?.telemetrySnapshot
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to fetch telemetry snapshot", ex)
            null
        }
    }

    fun exportTelemetry(includeTrends: Boolean): String? {
        return try {
            service?.exportTelemetry(includeTrends)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to export telemetry", ex)
            null
        }
    }

    fun exportDiagnostics(includeTrends: Boolean): String? {
        return try {
            service?.exportDiagnostics(includeTrends)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to export diagnostics", ex)
            null
        }
    }

    fun pushProfile(id: String, json: String) {
        try {
            service?.pushProfile(id, json)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to push profile", ex)
        }
    }

    fun setProfile(profile: String) {
        try {
            service?.setProfile(profile)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set profile", ex)
        }
    }

    fun isTelemetryOptedIn(): Boolean {
        return try {
            service?.isTelemetryOptedIn ?: false
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to read telemetry opt-in", ex)
            false
        }
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        try {
            service?.setTelemetryOptIn(enabled)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to update telemetry opt-in", ex)
        }
    }

    fun updateWhitelist(packageName: String, enabled: Boolean) {
        try {
            service?.updateWhitelist(packageName, enabled)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to update whitelist", ex)
        }
    }

    fun pushProfileSnapshot(profileId: String, profileJson: String) {
        try {
            service?.pushProfileSnapshot(profileId, profileJson)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to push profile snapshot", ex)
        }
    }

    fun setLatencyModeOverride(profileId: String, latencyMode: String) {
        try {
            service?.setLatencyModeOverride(profileId, latencyMode)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set latency mode override", ex)
        }
    }

    fun setAppPresetBinding(packageName: String, presetId: String) {
        try {
            service?.setAppPresetBinding(packageName, presetId)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set app preset binding", ex)
        }
    }

    /** Combined module/SELinux/HAL status JSON (see t2-e6-signatures §3), or null. */
    fun getModuleStatus(): String? {
        return try {
            service?.moduleStatus
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to read module status", ex)
            null
        }
    }

    /** Forces a privileged status refresh and returns the fresh status JSON, or null. */
    fun refreshStatus(): String? {
        return try {
            service?.refreshStatus()
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to refresh module status", ex)
            null
        }
    }

    /** Read-back of persisted whitelist + app bindings as JSON, or null. */
    fun getWhitelistBindings(): String? {
        return try {
            service?.whitelistBindings
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to read whitelist bindings", ex)
            null
        }
    }

    fun listProfiles(): List<String> {
        return try {
            service?.listProfiles()?.toList() ?: emptyList()
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to list profiles", ex)
            emptyList()
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        try {
            service?.setMasterEnabled(enabled)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set master enabled", ex)
        }
    }

    fun setBypass(bypass: Boolean) {
        try {
            service?.setBypass(bypass)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set bypass", ex)
        }
    }

    fun triggerPanic(holdMs: Long) {
        try {
            service?.triggerPanic(holdMs)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to trigger panic", ex)
        }
    }

    fun setSidetone(enabled: Boolean, gainDb: Float) {
        try {
            service?.setSidetone(enabled, gainDb)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set sidetone", ex)
        }
    }

    /**
     * Queues the newest complete state and applies it once per connection generation.
     *
     * Calls made before the asynchronous bind completes are retained. Rapid changes coalesce to
     * the latest immutable snapshot, and a service reconnect replays that current snapshot once.
     */
    fun synchronize(snapshot: ControlServiceSyncSnapshot) {
        val version = syncVersion.incrementAndGet()
        latestSync.set(VersionedSync(version, snapshot))
        if (bound.get()) {
            scope.launch { flushLatestSync() }
        }
    }

    fun setEngineMode(engineMode: String) {
        try {
            service?.setEngineMode(engineMode)
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to set engine mode", ex)
        }
    }

    /** Read-back of the global control state JSON (see t2-e6-signatures §4), or null. */
    fun getControlState(): String? {
        return try {
            service?.controlState
        } catch (ex: RemoteException) {
            Log.w(TAG, "Failed to read control state", ex)
            null
        }
    }

    companion object {
        private const val TAG = "ControlServiceClient"
    }

    private fun markDisconnected() {
        bound.set(false)
        service = null
        _connectionState.value = false
    }

    private fun releaseBinding() {
        if (!bindRequested.getAndSet(false)) return
        try {
            context.unbindService(connection)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Control service binding was already released", exception)
        }
    }

    private fun flushLatestSync() {
        synchronized(syncLock) {
            val connectedService = service ?: return
            val generation = connectionGeneration.get()
            val pending = latestSync.get() ?: return
            if (appliedGeneration == generation && appliedVersion == pending.version) return
            try {
                val snapshot = pending.snapshot
                snapshot.policyStateJson?.let {
                    check(connectedService.synchronizePolicyState(it)) {
                        "control service rejected v2 policy state"
                    }
                }
                connectedService.setTelemetryOptIn(snapshot.telemetryOptIn)
                appliedGeneration = generation
                appliedVersion = pending.version
            } catch (exception: DeadObjectException) {
                Log.w(TAG, "Control service died while synchronizing state", exception)
                markDisconnected()
                releaseBinding()
                bind()
            } catch (exception: RemoteException) {
                Log.w(TAG, "Failed to synchronize current control state", exception)
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Control service rejected current state", exception)
            }
        }
    }

    private data class VersionedSync(
        val version: Long,
        val snapshot: ControlServiceSyncSnapshot,
    )
}
