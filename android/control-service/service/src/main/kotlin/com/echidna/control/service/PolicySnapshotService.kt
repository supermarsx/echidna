package com.echidna.control.service

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private val telemetryExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
        { runnable -> Thread(runnable, "echidna-preprocessor-telemetry").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val registrationLock = Any()
    private val registrations = mutableMapOf<IBinder, Registration>()
    private val pendingGeneration = AtomicLong(0L)
    private val invalidationScheduled = AtomicBoolean(false)
    private var registryObservation: Closeable? = null
    private lateinit var capabilityIssuer: LegacyCapabilityIssuer
    private lateinit var issuanceLedger: LegacyCapabilityIssuanceLedger
    private lateinit var telemetryRelay: LegacyPreprocessorTelemetryRelay
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
        val flagStore = LegacyPreprocessorFlagStore(applicationContext)
        issuanceLedger = LegacyCapabilityIssuanceLedger()
        telemetryRelay = LegacyPreprocessorTelemetryRelay(
            issuanceLedger,
            AuthenticatedTelemetryRegistry.store,
            PublishedPolicyRegistry::generation,
        )
        val signingExecutor = BoundedCapabilityExecutor()
        capabilityIssuer = LegacyCapabilityIssuer(
            enabled = flagStore::isEnabled,
            policySource = { packageName, processName ->
                PublishedPolicyRegistry.capabilityForProcess(
                    packageName,
                    processName,
                    System.currentTimeMillis(),
                )
            },
            signer = AndroidKeyStoreLegacyCapabilitySigner(applicationContext),
            executor = signingExecutor,
        )
        capabilityIssuer.prepareKey()
        executor.execute(::loadPersistedPolicy)
    }

    override fun onDestroy() {
        registryObservation?.close()
        registryObservation = null
        callbacks.kill()
        synchronized(registrationLock) { registrations.clear() }
        executor.shutdownNow()
        telemetryExecutor.shutdownNow()
        if (::issuanceLedger.isInitialized) issuanceLedger.clear()
        if (::capabilityIssuer.isInitialized) capabilityIssuer.close()
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

        override fun getApiVersion(): Long = CAPABILITY_PROVIDER_API_VERSION

        override fun requestLegacyPreprocessorCapability(
            audioSessionId: Int,
            processName: String?,
            generation: Long,
            nonce: ByteArray?,
            callback: IEchidnaCapabilityCallback?,
        ) {
            if (callback == null) return
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            val packageName = authorizeCapabilityCaller(callingUid, callingPid, processName)
            if (packageName == null) {
                notifyCapability(
                    callback,
                    LegacyCapabilityResult(
                        LegacyCapabilityStatus.DENIED,
                        generation,
                        diagnostic = LegacyCapabilityDiagnostic.CALLER_UNAUTHORIZED,
                    ),
                )
                return
            }
            val request = LegacyCapabilityRequest(
                uid = callingUid,
                packageName = packageName,
                processName = processName!!,
                audioSessionId = audioSessionId,
                generation = generation,
                nonce = nonce?.clone() ?: ByteArray(0),
            )
            capabilityIssuer.request(
                request,
                object : LegacyCapabilityResultSink {
                    override fun isAlive(): Boolean = callback.asBinder().isBinderAlive

                    override fun complete(result: LegacyCapabilityResult) {
                        if (result.status == LegacyCapabilityStatus.OK) {
                            issuanceLedger.record(callingPid, request, result)
                        }
                        notifyCapability(callback, result)
                    }
                },
            )
        }

        override fun reportLegacyPreprocessorTelemetry(
            audioSessionId: Int,
            processName: String?,
            generation: Long,
            snapshot: ByteArray?,
        ) {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            val receivedAtMs = SystemClock.elapsedRealtime()
            val packageName = authorizeCapabilityCaller(callingUid, callingPid, processName)
                ?: return
            if (
                packageName != processName?.substringBefore(':') ||
                snapshot == null || snapshot.size != PREPROCESSOR_TELEMETRY_VALUE_BYTES
            ) {
                return
            }
            val ownedSnapshot = snapshot.clone()
            val ownedProcess = processName
            try {
                telemetryExecutor.execute {
                    telemetryRelay.report(
                        callingUid,
                        callingPid,
                        ownedProcess,
                        audioSessionId,
                        generation,
                        ownedSnapshot,
                        receivedAtMs,
                    )
                }
            } catch (_: RejectedExecutionException) {
                // One-way diagnostics are best effort; saturation must not block Binder threads.
            }
        }
    }

    private fun authorizeCaller(processName: String?): String? {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid)?.asList().orEmpty()
        return CallerPolicyAuthorizer.authorize(packages, processName)
    }

    private fun authorizeCapabilityCaller(
        callingUid: Int,
        callingPid: Int,
        processName: String?,
    ): String? {
        val packages = packageManager.getPackagesForUid(callingUid)?.asList().orEmpty()
        val running = getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            .orEmpty()
            .map { process ->
                CallerPolicyAuthorizer.RunningProcess(
                    pid = process.pid,
                    uid = process.uid,
                    processName = process.processName,
                    packageNames = process.pkgList?.toSet().orEmpty(),
                )
            }
        return CallerPolicyAuthorizer.authorizeCapability(
            callingUid,
            callingPid,
            packages,
            running,
            processName,
        )
    }

    private fun notifyCapability(
        callback: IEchidnaCapabilityCallback,
        result: LegacyCapabilityResult,
    ) {
        try {
            callback.onCapabilityResult(
                result.status,
                result.generation,
                result.envelope,
                result.diagnostic,
            )
        } catch (_: RemoteException) {
            // The request is one-shot; a dead client has no state to unregister.
        } catch (exception: RuntimeException) {
            Log.w(POLICY_PROVIDER_TAG, LegacyCapabilityDiagnostic.CALLBACK_FAILED, exception)
        }
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
