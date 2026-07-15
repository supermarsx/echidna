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

internal fun isCaptureOwnerClientApiSupported(clientApiVersion: Long): Boolean =
    clientApiVersion == CAPABILITY_PROVIDER_API_VERSION

/**
 * Explicit, exported, read-only Binder endpoint for LSPosed-injected target processes.
 *
 * The privileged control AIDL remains non-exported. This endpoint authenticates every process
 * claim against Binder's caller UID and returns only that package's exact/base policy view.
 */
class PolicySnapshotService : Service() {
    private data class Registration(
        val uid: Int,
        val pid: Int,
        val packageName: String,
        val processName: String,
        val handoffCapable: Boolean,
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
    private val handoffEndpoints = mutableMapOf<IBinder, BinderLsposedEndpoint>()
    private val pendingHandoffEndpoints = mutableMapOf<IBinder, BinderLsposedEndpoint>()
    private val pendingGeneration = AtomicLong(0L)
    private val invalidationScheduled = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var registryObservation: Closeable? = null
    private var handoffObservation: Closeable? = null
    private lateinit var handoffCoordinator: CaptureOwnerHandoffCoordinator
    private lateinit var capabilityIssuer: LegacyCapabilityIssuer
    private lateinit var issuanceLedger: LegacyCapabilityIssuanceLedger
    private lateinit var telemetryRelay: LegacyPreprocessorTelemetryRelay
    private lateinit var telemetryProofVerifier: LegacyPreprocessorTelemetryProofVerifier
    private val callbacks = object : RemoteCallbackList<IEchidnaPolicyListener>() {
        override fun onCallbackDied(
            callback: IEchidnaPolicyListener?,
            cookie: Any?,
        ) {
            callback?.asBinder()?.let { binder ->
                val endpoint = synchronized(registrationLock) {
                    registrations.remove(binder)
                    handoffEndpoints.remove(binder) ?: pendingHandoffEndpoints.remove(binder)
                }
                endpoint?.let(handoffCoordinator::unregisterLsposed)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handoffCoordinator = CaptureOwnerHandoffRegistry.get()
        registryObservation = PublishedPolicyRegistry.observe(::scheduleInvalidation)
        handoffObservation = handoffCoordinator.observe { _, generation, _ ->
            scheduleInvalidation(generation)
        }
        val flagStore = LegacyPreprocessorFlagStore(applicationContext)
        issuanceLedger = LegacyCapabilityIssuanceLedger()
        telemetryProofVerifier = LegacyPreprocessorTelemetryProofVerifier(
            AppPrivateTelemetryProofKeySource(
                File(File(filesDir, "echidna"), "preprocessor_telemetry_hmac.key"),
            ),
        )
        telemetryRelay = LegacyPreprocessorTelemetryRelay(
            issuanceLedger,
            AuthenticatedTelemetryRegistry.store,
            PublishedPolicyRegistry::generation,
            telemetryProofVerifier,
        )
        telemetryExecutor.execute { telemetryProofVerifier.prepare() }
        val signingExecutor = BoundedCapabilityExecutor()
        capabilityIssuer = LegacyCapabilityIssuer(
            enabled = flagStore::isEnabled,
            // Binder requests always provide an endpoint-pinned override below.
            policySource = { _, _ -> null },
            signer = AndroidKeyStoreLegacyCapabilitySigner(applicationContext),
            executor = signingExecutor,
        )
        capabilityIssuer.prepareKey()
        executor.execute(::loadPersistedPolicy)
    }

    override fun onDestroy() {
        stopping.set(true)
        registryObservation?.close()
        registryObservation = null
        handoffObservation?.close()
        handoffObservation = null
        callbacks.kill()
        val endpoints = synchronized(registrationLock) {
            registrations.clear()
            (handoffEndpoints.values + pendingHandoffEndpoints.values).distinct().also {
                handoffEndpoints.clear()
                pendingHandoffEndpoints.clear()
            }
        }
        endpoints.forEach(handoffCoordinator::unregisterLsposed)
        executor.shutdownNow()
        telemetryExecutor.shutdownNow()
        if (::telemetryProofVerifier.isInitialized) telemetryProofVerifier.close()
        if (::issuanceLedger.isInitialized) issuanceLedger.clear()
        if (::capabilityIssuer.isInitialized) capabilityIssuer.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private inner class BinderLsposedEndpoint(
        override val processName: String,
        val uid: Int,
        val pid: Int,
        val listener: IEchidnaPolicyListener,
    ) : LsposedCaptureEndpoint {
        override fun revoke(generation: Long, handoffToken: Long): Boolean = try {
            listener.onCaptureOwnerRevoked(generation, handoffToken)
            true
        } catch (_: RemoteException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        override fun policyChanged(generation: Long) {
            notifyListener(listener, generation)
        }
    }

    private val binder = object : IEchidnaPolicyProvider.Stub() {
        override fun getPolicySnapshot(processName: String?): String? {
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            val authorized = authorizeCapabilityCaller(uid, pid, processName) ?: return null
            val endpoint = currentHandoffEndpoint(uid, pid, processName!!) ?: return null
            return handoffCoordinator.lsposedPolicy(
                endpoint,
                authorized,
                processName,
            )
        }

        override fun registerListener(
            processName: String?,
            listener: IEchidnaPolicyListener?,
        ) {
            if (listener == null) return
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            val authorized = authorizeCaller(processName) ?: return
            val registration = Registration(uid, pid, authorized, processName!!, false)
            val callbackBinder = listener.asBinder()
            val registered = synchronized(registrationLock) {
                val uidCount = registrations.values.count { it.uid == uid }
                if (
                    stopping.get() ||
                    registrations.size >= MAX_POLICY_LISTENERS ||
                    uidCount >= MAX_POLICY_LISTENERS_PER_UID ||
                    registrations.containsKey(callbackBinder)
                ) {
                    false
                } else {
                    registrations[callbackBinder] = registration
                    callbacks.register(listener, registration).also { callbackRegistered ->
                        if (!callbackRegistered) registrations.remove(callbackBinder)
                    }
                }
            }
            if (registered) notifyListener(listener, PublishedPolicyRegistry.generation())
        }

        override fun unregisterListener(listener: IEchidnaPolicyListener?) {
            if (listener == null) return
            callbacks.unregister(listener)
            val endpoint = synchronized(registrationLock) {
                registrations.remove(listener.asBinder())
                handoffEndpoints.remove(listener.asBinder())
                    ?: pendingHandoffEndpoints.remove(listener.asBinder())
            }
            endpoint?.let(handoffCoordinator::unregisterLsposed)
        }

        override fun getApiVersion(): Long = CAPABILITY_PROVIDER_API_VERSION

        override fun registerCaptureOwnerClient(
            processName: String?,
            clientApiVersion: Long,
            listener: IEchidnaPolicyListener?,
        ): Boolean {
            if (listener == null || !isCaptureOwnerClientApiSupported(clientApiVersion)) return false
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            val authorized = authorizeCapabilityCaller(uid, pid, processName) ?: return false
            val ownedProcess = processName!!
            val endpoint = BinderLsposedEndpoint(ownedProcess, uid, pid, listener)
            val registration = Registration(uid, pid, authorized, ownedProcess, true)
            val callbackBinder = listener.asBinder()
            val reserved = synchronized(registrationLock) {
                val uidCount = registrations.values.count { it.uid == uid } +
                    pendingHandoffEndpoints.values.count { it.uid == uid }
                if (
                    stopping.get() ||
                    registrations.size + pendingHandoffEndpoints.size >= MAX_POLICY_LISTENERS ||
                    uidCount >= MAX_POLICY_LISTENERS_PER_UID ||
                    registrations.containsKey(callbackBinder) ||
                    pendingHandoffEndpoints.containsKey(callbackBinder) ||
                    (handoffEndpoints.values + pendingHandoffEndpoints.values).any { existing ->
                        existing.uid == uid && existing.pid == pid &&
                            existing.processName == ownedProcess
                    }
                ) {
                    false
                } else {
                    pendingHandoffEndpoints[callbackBinder] = endpoint
                    true
                }
            }
            if (!reserved) return false
            if (!callbackBinder.isBinderAlive || !handoffCoordinator.registerLsposed(endpoint)) {
                synchronized(registrationLock) {
                    pendingHandoffEndpoints.remove(callbackBinder, endpoint)
                }
                return false
            }
            val registered = synchronized(registrationLock) {
                if (
                    stopping.get() || !callbackBinder.isBinderAlive ||
                    pendingHandoffEndpoints[callbackBinder] !== endpoint
                ) {
                    pendingHandoffEndpoints.remove(callbackBinder, endpoint)
                    false
                } else {
                    registrations[callbackBinder] = registration
                    handoffEndpoints[callbackBinder] = endpoint
                    callbacks.register(listener, registration).also { callbackRegistered ->
                        pendingHandoffEndpoints.remove(callbackBinder, endpoint)
                        if (!callbackRegistered) {
                            registrations.remove(callbackBinder)
                            handoffEndpoints.remove(callbackBinder)
                        }
                    }
                }
            }
            if (!registered) handoffCoordinator.unregisterLsposed(endpoint)
            return registered
        }

        override fun reportCaptureOwnerInactive(
            processName: String?,
            generation: Long,
            handoffToken: Long,
        ) {
            if (generation <= 0L || handoffToken <= 0L) return
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            if (authorizeCapabilityCaller(uid, pid, processName) == null) return
            val endpoint = currentHandoffEndpoint(uid, pid, processName!!) ?: return
            handoffCoordinator.acknowledgeLsposedInactive(
                endpoint,
                processName,
                generation,
                handoffToken,
            )
        }

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
            val endpoint = if (packageName != null) {
                currentHandoffEndpoint(callingUid, callingPid, processName!!)
            } else {
                null
            }
            if (packageName == null || endpoint == null) {
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
                    override fun isAlive(): Boolean = callback.asBinder().isBinderAlive &&
                        currentHandoffEndpoint(
                            callingUid,
                            callingPid,
                            request.processName,
                        ) === endpoint

                    override fun complete(result: LegacyCapabilityResult) {
                        if (
                            !callback.asBinder().isBinderAlive ||
                            currentHandoffEndpoint(
                                callingUid,
                                callingPid,
                                request.processName,
                            ) !== endpoint
                        ) {
                            return
                        }
                        if (result.status == LegacyCapabilityStatus.OK) {
                            val currentPolicy = handoffCoordinator.capabilityPolicy(
                                endpoint,
                                request.packageName,
                                request.processName,
                                System.currentTimeMillis(),
                            )
                            if (currentPolicy?.generation != result.generation) {
                                notifyCapability(
                                    callback,
                                    LegacyCapabilityResult(
                                        LegacyCapabilityStatus.STALE,
                                        result.generation,
                                        diagnostic = LegacyCapabilityDiagnostic.STALE_GENERATION,
                                    ),
                                )
                                return
                            }
                            issuanceLedger.record(callingPid, request, result)
                        }
                        notifyCapability(callback, result)
                    }
                },
                requestPolicySource = { requestedPackage, requestedProcess ->
                    handoffCoordinator.capabilityPolicy(
                        endpoint,
                        requestedPackage,
                        requestedProcess,
                        System.currentTimeMillis(),
                    )
                },
            )
        }

        override fun reportLegacyPreprocessorTelemetry(
            audioSessionId: Int,
            processName: String?,
            generation: Long,
            snapshot: ByteArray?,
        ) {
            // Retain the append-only v3 transaction for APK/shim skew, but never ingest an
            // incarnation-unbound payload as processing evidence or diagnostic counters.
        }

        override fun reportLegacyPreprocessorTelemetryV4(
            audioSessionId: Int,
            processName: String?,
            generation: Long,
            capabilityNonce: ByteArray?,
            snapshot: ByteArray?,
        ) {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            val receivedAtMs = SystemClock.elapsedRealtime()
            val packageName = authorizeCapabilityCaller(callingUid, callingPid, processName)
                ?: return
            val ownedProcess = processName ?: return
            val endpoint = currentHandoffEndpoint(callingUid, callingPid, ownedProcess) ?: return
            if (
                packageName != ownedProcess.substringBefore(':') ||
                capabilityNonce == null ||
                capabilityNonce.size != PREPROCESSOR_TELEMETRY_CAPABILITY_NONCE_BYTES ||
                snapshot == null || snapshot.size != PREPROCESSOR_TELEMETRY_VALUE_BYTES ||
                !isCurrentLsposedOwner(
                    endpoint,
                    callingUid,
                    callingPid,
                    packageName,
                    ownedProcess,
                    generation,
                )
            ) {
                return
            }
            val ownedNonce = capabilityNonce.clone()
            val ownedSnapshot = snapshot.clone()
            try {
                telemetryExecutor.execute {
                    if (!isCurrentLsposedOwner(
                            endpoint,
                            callingUid,
                            callingPid,
                            packageName,
                            ownedProcess,
                            generation,
                        )) {
                        return@execute
                    }
                    telemetryRelay.report(
                        callingUid,
                        callingPid,
                        ownedProcess,
                        audioSessionId,
                        generation,
                        ownedNonce,
                        ownedSnapshot,
                        receivedAtMs,
                    )
                }
            } catch (_: RejectedExecutionException) {
                // One-way diagnostics are best effort; saturation must not block Binder threads.
            }
        }

        override fun reportLegacyPreprocessorTelemetryProofV5(
            audioSessionId: Int,
            processName: String?,
            generation: Long,
            proof: ByteArray?,
        ) {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            val receivedAtMs = SystemClock.elapsedRealtime()
            val packageName = authorizeCapabilityCaller(callingUid, callingPid, processName)
                ?: return
            val ownedProcess = processName ?: return
            val endpoint = currentHandoffEndpoint(callingUid, callingPid, ownedProcess) ?: return
            if (
                packageName != ownedProcess.substringBefore(':') ||
                proof == null || proof.size != PREPROCESSOR_TELEMETRY_PROOF_VALUE_BYTES ||
                !isCurrentLsposedOwner(
                    endpoint,
                    callingUid,
                    callingPid,
                    packageName,
                    ownedProcess,
                    generation,
                )
            ) {
                return
            }
            val ownedProof = proof.clone()
            try {
                telemetryExecutor.execute {
                    if (!isCurrentLsposedOwner(
                            endpoint,
                            callingUid,
                            callingPid,
                            packageName,
                            ownedProcess,
                            generation,
                        )) {
                        return@execute
                    }
                    telemetryRelay.reportProof(
                        callingUid,
                        callingPid,
                        ownedProcess,
                        audioSessionId,
                        generation,
                        ownedProof,
                        receivedAtMs,
                    )
                }
            } catch (_: RejectedExecutionException) {
                // One-way proof ingestion is best effort and must not block Binder threads.
            }
        }
    }

    private fun authorizeCaller(processName: String?): String? {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid)?.asList().orEmpty()
        return CallerPolicyAuthorizer.authorize(packages, processName)
    }

    private fun currentHandoffEndpoint(
        uid: Int,
        pid: Int,
        processName: String,
    ): BinderLsposedEndpoint? = synchronized(registrationLock) {
        (handoffEndpoints.values + pendingHandoffEndpoints.values).singleOrNull { endpoint ->
            endpoint.uid == uid && endpoint.pid == pid && endpoint.processName == processName
        }
    }

    private fun isCurrentLsposedOwner(
        endpoint: BinderLsposedEndpoint,
        uid: Int,
        pid: Int,
        packageName: String,
        processName: String,
        generation: Long,
    ): Boolean =
        currentHandoffEndpoint(uid, pid, processName) === endpoint &&
            handoffCoordinator.capabilityPolicy(
                endpoint,
                packageName,
                processName,
                System.currentTimeMillis(),
            )?.generation == generation

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
