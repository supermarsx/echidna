package com.echidna.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.echidna.control.service.EchidnaControlService
import com.echidna.control.service.IEchidnaControlService
import com.echidna.control.service.IEchidnaTelemetryListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class ControlServiceClient(private val context: Context) {
    // The control service is hosted inside THIS APK (com.echidna.app); bind the
    // in-app component rather than the former phantom com.echidna.control package.
    private val intent = Intent().apply {
        component = ComponentName(context, EchidnaControlService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val bound = AtomicBoolean(false)
    private var service: IEchidnaControlService? = null
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
            service = IEchidnaControlService.Stub.asInterface(binder)
            bound.set(true)
            try {
                service?.registerTelemetryListener(listener)
            } catch (ex: RemoteException) {
                Log.e(TAG, "Failed to register telemetry listener", ex)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound.set(false)
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            bound.set(false)
            service = null
            bind()
        }
    }

    fun bind() {
        if (!bound.get()) {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun isBound(): Boolean = bound.get()

    fun unbind() {
        if (bound.getAndSet(false)) {
            try {
                service?.unregisterTelemetryListener(listener)
            } catch (ex: RemoteException) {
                Log.w(TAG, "Failed to unregister telemetry listener", ex)
            }
            context.unbindService(connection)
            service = null
        }
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
}
