package com.echidna.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
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
    private val intent = Intent().apply {
        component = ComponentName(
            "com.echidna.control",
            "com.echidna.control.service.EchidnaControlService"
        )
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

    companion object {
        private const val TAG = "ControlServiceClient"
    }
}
