package com.echidna.control.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val POLICY_PROVIDER_TAG = "EchidnaPolicyProvider"
private const val MAX_POLICY_LISTENERS = 64
private const val MAX_POLICY_LISTENERS_PER_UID = 4

/**
 * Explicit, exported, read-only Binder endpoint for LSPosed-injected target processes.
 *
 * The privileged control AIDL remains non-exported. This endpoint authenticates every process
 * claim against Binder's caller UID and returns only that package's exact/base policy view.
 */
class PolicySnapshotService : Service() {
    private data class Registration(
        val uid: Int,
        val packageName: String,
        val processName: String,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "echidna-policy-provider").apply { isDaemon = true }
    }
    private val registrationLock = Any()
    private val registrations = mutableMapOf<IBinder, Registration>()
    private val pendingGeneration = AtomicLong(0L)
    private val invalidationScheduled = AtomicBoolean(false)
    private var registryObservation: Closeable? = null
    private val callbacks = object : RemoteCallbackList<IEchidnaPolicyListener>() {
        override fun onCallbackDied(
            callback: IEchidnaPolicyListener?,
            cookie: Any?,
        ) {
            callback?.asBinder()?.let { binder ->
                synchronized(registrationLock) { registrations.remove(binder) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registryObservation = PublishedPolicyRegistry.observe(::scheduleInvalidation)
        executor.execute(::loadPersistedPolicy)
    }

    override fun onDestroy() {
        registryObservation?.close()
        registryObservation = null
        callbacks.kill()
        synchronized(registrationLock) { registrations.clear() }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEchidnaPolicyProvider.Stub() {
        override fun getPolicySnapshot(processName: String?): String? {
            val authorized = authorizeCaller(processName) ?: return null
            return PublishedPolicyRegistry.scopedForProcess(
                authorized,
                processName!!,
            )
        }

        override fun registerListener(
            processName: String?,
            listener: IEchidnaPolicyListener?,
        ) {
            if (listener == null) return
            val uid = Binder.getCallingUid()
            val authorized = authorizeCaller(processName) ?: return
            val registration = Registration(uid, authorized, processName!!)
            val callbackBinder = listener.asBinder()
            val registered = synchronized(registrationLock) {
                val uidCount = registrations.values.count { it.uid == uid }
                if (
                    registrations.size >= MAX_POLICY_LISTENERS ||
                    uidCount >= MAX_POLICY_LISTENERS_PER_UID
                ) {
                    false
                } else if (callbacks.register(listener, registration)) {
                    registrations[callbackBinder] = registration
                    true
                } else {
                    false
                }
            }
            if (registered) notifyListener(listener, PublishedPolicyRegistry.generation())
        }

        override fun unregisterListener(listener: IEchidnaPolicyListener?) {
            if (listener == null) return
            callbacks.unregister(listener)
            synchronized(registrationLock) { registrations.remove(listener.asBinder()) }
        }
    }

    private fun authorizeCaller(processName: String?): String? {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid)?.asList().orEmpty()
        return CallerPolicyAuthorizer.authorize(packages, processName)
    }

    private fun loadPersistedPolicy() {
        if (PublishedPolicyRegistry.generation() > 0L) return
        val file = File(File(filesDir, "profiles"), "profiles.json")
        if (!file.isFile || file.length() > MAX_POLICY_ENVELOPE_BYTES) return
        runCatching { readProfileStoreAtomic(file) }
            .onSuccess(PublishedPolicyRegistry::publish)
            .onFailure { error ->
                Log.w(POLICY_PROVIDER_TAG, "Unable to load persisted policy", error)
            }
    }

    private fun scheduleInvalidation(generation: Long) {
        pendingGeneration.accumulateAndGet(generation, ::maxOf)
        if (!invalidationScheduled.compareAndSet(false, true)) return
        try {
            executor.execute(::drainInvalidations)
        } catch (_: RuntimeException) {
            invalidationScheduled.set(false)
        }
    }

    private fun drainInvalidations() {
        while (!Thread.currentThread().isInterrupted) {
            val generation = pendingGeneration.getAndSet(0L)
            if (generation > 0L) broadcastInvalidation(generation)
            invalidationScheduled.set(false)
            if (pendingGeneration.get() <= 0L || !invalidationScheduled.compareAndSet(false, true)) {
                return
            }
        }
    }

    private fun broadcastInvalidation(generation: Long) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                notifyListener(callbacks.getBroadcastItem(index), generation)
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun notifyListener(listener: IEchidnaPolicyListener, generation: Long) {
        try {
            listener.onPolicyChanged(generation)
        } catch (_: RemoteException) {
            // RemoteCallbackList removes dead binders through onCallbackDied.
        }
    }
}
