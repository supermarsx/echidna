package com.echidna.control.service

import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONException
import org.json.JSONObject

private const val STORE_TAG = "EchidnaProfileStore"
private const val MAX_PROCESS_NAME_LENGTH = 255
private const val MAX_PROFILE_COUNT = 256
private const val ENGINE_MODE_NATIVE_FIRST = "native_first"
private const val ENGINE_MODE_LOW_LATENCY = "low_latency"
private const val ENGINE_MODE_COMPATIBILITY = "compatibility"
private const val MAX_PROFILE_ID_LENGTH = 128
private const val CLOSE_DRAIN_TIMEOUT_MS = 1_500L

/**
 * Maintains JSON profile definitions and pushes updates to the Zygisk bridge.
 */
class ProfileStore(
    storageDir: File,
    private val syncBridge: ProfileSyncChannel,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val panicScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "echidna-panic-expiry").apply { isDaemon = true }
        },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantReadWriteLock()
    private val storageFile = File(storageDir, "profiles.json")
    private val profiles = mutableMapOf<String, JSONObject>()
    private val whitelist = mutableMapOf<String, Boolean>()
    private val appBindings = mutableMapOf<String, String>()
    private val captureOwners = mutableMapOf<String, String>()
    private var defaultProfileId = ""
    private var generation = 0L
    private var lastAppPayload: String? = null
    private val flushLock = Any()
    private var pendingSnapshot: String? = null
    private var flushScheduled = false
    private val panicTaskLock = Any()
    private var panicExpiryTask: ScheduledFuture<*>? = null
    @Volatile private var closing = false

    /**
     * Global engine control state. Mutated by the master/bypass/panic/sidetone
     * binder entry points and published in the snapshot's "control" object so the
     * native engine and LSPosed shim observe it over the ProfileSyncBridge.
     */
    private var masterEnabled = true
    private var bypass = false
    private var panicUntilEpochMs = 0L
    private var sidetoneEnabled = false
    private var sidetoneGainDb = 0.0
    private var engineMode = ENGINE_MODE_NATIVE_FIRST

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        loadFromDisk()
    }

    fun listProfiles(): List<String> = lock.read { profiles.keys.toList() }

    fun resolveProfilePayload(request: String): String? = lock.read {
        profiles[request]?.toString()
    }

    fun saveProfile(id: String, profileJson: String) {
        if (!isValidProfileId(id)) {
            Log.w(STORE_TAG, "Rejected invalid profile id: $id")
            return
        }
        val parsed = try {
            JSONObject(profileJson)
        } catch (e: JSONException) {
            Log.w(STORE_TAG, "Rejected invalid profile JSON", e)
            return
        }
        if (profileJson.toByteArray(StandardCharsets.UTF_8).size > MAX_POLICY_PRESET_BYTES) {
            Log.w(STORE_TAG, "Rejected profile JSON: too large")
            return
        }
        if (!isStructuredPreset(parsed)) {
            Log.w(STORE_TAG, "Rejected profile JSON: missing modules/engine")
            return
        }
        val snapshot = lock.write {
            if (!profiles.containsKey(id) && profiles.size >= MAX_PROFILE_COUNT) return@write null
            profiles[id] = parsed
            if (defaultProfileId !in profiles) defaultProfileId = id
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    fun deleteProfile(id: String) {
        val snapshot = lock.write {
            if (profiles.remove(id) != null) {
                appBindings.entries.removeAll { (_, presetId) -> presetId == id }
                if (defaultProfileId == id) defaultProfileId = profiles.keys.firstOrNull().orEmpty()
                buildMutatedSnapshotLocked()
            } else {
                null
            }
        }
        snapshot?.let(::scheduleFlush)
    }

    fun updateWhitelist(processName: String, enabled: Boolean) {
        if (!isValidProcessName(processName)) {
            Log.w(STORE_TAG, "Rejected invalid process name: $processName")
            return
        }
        val snapshot = lock.write {
            if (!whitelist.containsKey(processName) && whitelist.size >= MAX_PROFILE_COUNT) {
                return@write null
            }
            whitelist[processName] = enabled
            if (enabled) {
                captureOwners.putIfAbsent(
                    processName,
                    if (engineMode == ENGINE_MODE_COMPATIBILITY) "lsposed" else "zygisk",
                )
            } else {
                captureOwners.remove(processName)
            }
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    fun close() {
        synchronized(flushLock) {
            if (closing) return
            closing = true
        }
        synchronized(panicTaskLock) {
            panicExpiryTask?.cancel(false)
            panicExpiryTask = null
        }
        panicScheduler.shutdownNow()
        executor.shutdown()
        // Service.onDestroy runs on Android's main thread. Enforce the timeout from a daemon
        // watchdog instead of joining the persistence worker from that lifecycle callback.
        Thread(
            {
                if (!awaitClosed(CLOSE_DRAIN_TIMEOUT_MS)) {
                    Log.w(STORE_TAG, "Timed out draining profile persistence; interrupting writer")
                    executor.shutdownNow()
                }
            },
            "echidna-profile-close-watchdog",
        ).apply { isDaemon = true }.start()
    }

    /** Test/process-teardown join; production lifecycle code calls non-blocking [close] only. */
    internal fun awaitClosed(timeoutMs: Long = CLOSE_DRAIN_TIMEOUT_MS): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val deadlineNanos = System.nanoTime() + timeoutNanos
        try {
            if (!executor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS)) {
                return false
            }
            val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            return panicScheduler.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    fun setLatencyOverride(profileId: String, latencyMode: String) {
        val snapshot = lock.write {
            val profile = profiles[profileId] ?: return
            profile.put("latencyOverride", latencyMode)
            profiles[profileId] = profile
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    fun setAppBinding(packageName: String, presetId: String) {
        if (!isValidPackageName(packageName)) {
            Log.w(STORE_TAG, "Rejected invalid package name: $packageName")
            return
        }
        val snapshot = lock.write {
            if (presetId.isBlank()) {
                if (appBindings.remove(packageName) == null) null else buildMutatedSnapshotLocked()
            } else if (!profiles.containsKey(presetId)) {
                null
            } else if (!appBindings.containsKey(packageName) && appBindings.size >= MAX_PROFILE_COUNT) {
                null
            } else {
                appBindings[packageName] = presetId
                buildMutatedSnapshotLocked()
            }
        }
        if (snapshot == null) {
            if (presetId.isNotBlank()) {
                Log.w(STORE_TAG, "Rejected dangling app binding to unknown preset: $presetId")
            }
            return
        }
        scheduleFlush(snapshot)
    }

    /** Atomically applies one complete v2 policy under a service-owned generation. */
    fun synchronizePolicyState(stateJson: String): Boolean {
        val next = PolicyEnvelopeCodec.parseRequest(stateJson) ?: run {
            Log.w(STORE_TAG, "Rejected invalid v2 policy request")
            return false
        }
        var snapshot: String? = null
        val accepted = lock.write {
            if (lastAppPayload == stateJson) return@write true
            val nextGeneration = nextGenerationLocked() ?: return@write false
            val encoded = PolicyEnvelopeCodec.encode(next, nextGeneration) ?: return@write false
            profiles.clear()
            profiles.putAll(next.profiles)
            defaultProfileId = next.defaultProfileId
            appBindings.clear()
            appBindings.putAll(next.appBindings)
            whitelist.clear()
            whitelist.putAll(next.whitelist)
            captureOwners.clear()
            captureOwners.putAll(next.captureOwners)
            masterEnabled = next.control.masterEnabled
            bypass = next.control.bypass
            panicUntilEpochMs = next.control.panicUntilEpochMs
            sidetoneEnabled = next.control.sidetoneEnabled
            sidetoneGainDb = next.control.sidetoneGainDb
            engineMode = next.control.engineMode
            generation = nextGeneration
            lastAppPayload = stateJson
            snapshot = encoded
            true
        }
        snapshot?.let {
            schedulePanicExpiry(lock.read { panicUntilEpochMs })
            scheduleFlush(it)
        }
        return accepted
    }

    fun getAppBindings(): Map<String, String> = lock.read {
        appBindings.toMap()
    }

    fun getWhitelist(): Map<String, Boolean> = lock.read {
        whitelist.toMap()
    }

    /** Read-back of persisted per-app policy: {"whitelist":{...},"appBindings":{...}}. */
    fun buildWhitelistBindingsJson(): String = lock.read {
        val json = JSONObject()
        val whitelistJson = JSONObject()
        whitelist.forEach { (process, allowed) -> whitelistJson.put(process, allowed) }
        json.put("whitelist", whitelistJson)
        val bindingsJson = JSONObject()
        appBindings.forEach { (pkg, presetId) -> bindingsJson.put(pkg, presetId) }
        json.put("appBindings", bindingsJson)
        json.toString()
    }

    /** Read-back of the global control state (see t2-e6-signatures §4). */
    fun buildControlStateJson(): String = lock.read {
        controlStateJsonLocked().toString()
    }

    fun setMasterEnabled(enabled: Boolean) {
        val snapshot = lock.write {
            masterEnabled = enabled
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    fun setBypass(enabled: Boolean) {
        val snapshot = lock.write {
            bypass = enabled
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    /**
     * Engages the time-bounded panic gate without overwriting the user's base master/bypass state.
     * The deadline is persisted and independently cleared so recovery survives process restarts.
     */
    fun panic(holdMs: Long) {
        val now = nowEpochMs()
        val deadline = when {
            holdMs <= 0L -> 0L
            holdMs >= Long.MAX_VALUE - now -> Long.MAX_VALUE
            else -> now + holdMs
        }
        val snapshot = lock.write {
            panicUntilEpochMs = deadline
            buildMutatedSnapshotLocked()
        }
        schedulePanicExpiry(deadline)
        snapshot?.let(::scheduleFlush)
    }

    fun setSidetone(enabled: Boolean, gainDb: Double) {
        val snapshot = lock.write {
            sidetoneEnabled = enabled
            sidetoneGainDb = gainDb
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    fun setEngineMode(mode: String) {
        val snapshot = lock.write {
            engineMode = sanitizeEngineMode(mode)
            val owner = if (engineMode == ENGINE_MODE_COMPATIBILITY) "lsposed" else "zygisk"
            whitelist.filterValues { it }.keys.forEach { captureOwners[it] = owner }
            buildMutatedSnapshotLocked()
        }
        snapshot?.let(::scheduleFlush)
    }

    private fun controlStateJsonLocked(): JSONObject {
        val control = JSONObject()
        control.put("masterEnabled", masterEnabled)
        control.put("bypass", bypass)
        control.put("panicUntilEpochMs", panicUntilEpochMs)
        control.put("sidetoneEnabled", sidetoneEnabled)
        control.put("sidetoneGainDb", sidetoneGainDb)
        control.put("engineMode", engineMode)
        return control
    }

    private fun buildMutatedSnapshotLocked(): String? {
        val nextGeneration = nextGenerationLocked() ?: return null
        val envelope = currentEnvelopeLocked() ?: return null
        val snapshot = PolicyEnvelopeCodec.encode(envelope, nextGeneration) ?: return null
        generation = nextGeneration
        lastAppPayload = null
        return snapshot
    }

    private fun buildSnapshotLocked(): String? {
        val envelope = currentEnvelopeLocked() ?: return null
        return PolicyEnvelopeCodec.encode(envelope, generation)
    }

    private fun currentEnvelopeLocked(): PolicyEnvelope? {
        if (profiles.isEmpty() || defaultProfileId !in profiles) return null
        return PolicyEnvelope(
            profiles = LinkedHashMap(profiles),
            defaultProfileId = defaultProfileId,
            appBindings = LinkedHashMap(appBindings),
            whitelist = LinkedHashMap(whitelist),
            captureOwners = LinkedHashMap(captureOwners),
            control = PolicyControl(
                masterEnabled = masterEnabled,
                bypass = bypass,
                panicUntilEpochMs = panicUntilEpochMs,
                sidetoneEnabled = sidetoneEnabled,
                sidetoneGainDb = sidetoneGainDb,
                engineMode = engineMode,
            ),
        )
    }

    private fun nextGenerationLocked(): Long? =
        if (generation == Long.MAX_VALUE) null else generation + 1L

    private fun scheduleFlush(snapshot: String) {
        if (!PublishedPolicyRegistry.publish(snapshot)) {
            Log.w(STORE_TAG, "Rejected non-monotonic policy publication")
            return
        }
        synchronized(flushLock) {
            if (closing || executor.isShutdown) return
            pendingSnapshot = snapshot
            if (flushScheduled) return
            flushScheduled = true
            try {
                // Submit while holding the same lock close() uses before shutdown, so the newest
                // accepted state cannot be stranded between marking and executor submission.
                executor.execute(::drainPendingSnapshots)
            } catch (e: RuntimeException) {
                flushScheduled = false
                Log.w(STORE_TAG, "Profile flush rejected; service is shutting down", e)
            }
        }
    }

    private fun schedulePanicExpiry(deadlineEpochMs: Long) {
        synchronized(panicTaskLock) {
            panicExpiryTask?.cancel(false)
            panicExpiryTask = null
            if (deadlineEpochMs <= 0L || closing || panicScheduler.isShutdown) {
                return
            }
            val delayMs = (deadlineEpochMs - nowEpochMs()).coerceAtLeast(0L)
            try {
                panicExpiryTask = panicScheduler.schedule(
                    { expirePanic(deadlineEpochMs) },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (exception: RuntimeException) {
                Log.w(STORE_TAG, "Unable to schedule panic expiry", exception)
            }
        }
    }

    private fun expirePanic(expectedDeadlineEpochMs: Long) {
        var rescheduleDeadline = 0L
        val snapshot = lock.write {
            if (panicUntilEpochMs != expectedDeadlineEpochMs) {
                return@write null
            }
            if (nowEpochMs() < expectedDeadlineEpochMs) {
                // Wall clocks can move backwards while a monotonic scheduled delay is pending.
                rescheduleDeadline = expectedDeadlineEpochMs
                return@write null
            }
            panicUntilEpochMs = 0L
            buildMutatedSnapshotLocked()
        }
        if (rescheduleDeadline > 0L) {
            schedulePanicExpiry(rescheduleDeadline)
        } else if (snapshot != null) {
            synchronized(panicTaskLock) {
                panicExpiryTask = null
            }
            scheduleFlush(snapshot)
        }
    }

    private fun drainPendingSnapshots() {
        while (!Thread.currentThread().isInterrupted) {
            val snapshot = synchronized(flushLock) {
                val next = pendingSnapshot
                pendingSnapshot = null
                if (next == null) flushScheduled = false
                next
            } ?: return
            writeToDisk(snapshot)
            try {
                syncBridge.pushProfiles(snapshot)
            } catch (exception: RuntimeException) {
                Log.e(STORE_TAG, "Failed to publish profile snapshot", exception)
            }
        }
    }

    private fun writeToDisk(payload: String) {
        try {
            writeProfileStoreAtomic(storageFile, payload)
        } catch (e: Exception) {
            Log.e(STORE_TAG, "Failed to persist profiles", e)
        }
    }

    private fun loadFromDisk() {
        if (!storageFile.exists()) {
            return
        }
        if (storageFile.length() > MAX_POLICY_ENVELOPE_BYTES) {
            Log.w(STORE_TAG, "Persisted profile store is too large; ignoring it")
            return
        }
        try {
            val content = readProfileStoreAtomic(storageFile)
            val published = PolicyEnvelopeCodec.parsePublished(content)
            val restored = published ?: PolicyEnvelopeCodec.migrateLegacy(content) ?: return
            var needsRewrite = published == null
            val snapshot = lock.write {
                val envelope = restored.envelope
                profiles.clear()
                profiles.putAll(envelope.profiles)
                defaultProfileId = envelope.defaultProfileId
                appBindings.clear()
                appBindings.putAll(envelope.appBindings)
                whitelist.clear()
                whitelist.putAll(envelope.whitelist)
                captureOwners.clear()
                captureOwners.putAll(envelope.captureOwners)
                masterEnabled = envelope.control.masterEnabled
                bypass = envelope.control.bypass
                panicUntilEpochMs = envelope.control.panicUntilEpochMs
                sidetoneEnabled = envelope.control.sidetoneEnabled
                sidetoneGainDb = envelope.control.sidetoneGainDb
                engineMode = envelope.control.engineMode
                generation = restored.generation
                if (panicUntilEpochMs > 0L && panicUntilEpochMs <= nowEpochMs()) {
                    panicUntilEpochMs = 0L
                    needsRewrite = true
                    buildMutatedSnapshotLocked()
                } else {
                    buildSnapshotLocked()
                }
            } ?: return
            if (!PublishedPolicyRegistry.publish(snapshot)) return
            syncBridge.pushProfiles(snapshot)
            val panicDeadline = lock.read { panicUntilEpochMs }
            schedulePanicExpiry(panicDeadline)
            if (needsRewrite) {
                scheduleFlush(snapshot)
            }
        } catch (e: Exception) {
            Log.w(STORE_TAG, "Unable to read persisted profiles", e)
        }
    }

    private fun isStructuredPreset(root: JSONObject): Boolean {
        return root.optJSONArray("modules") != null && root.optJSONObject("engine") != null
    }

    private fun isValidProcessName(processName: String): Boolean {
        if (processName.isBlank() || processName.length > MAX_PROCESS_NAME_LENGTH) {
            return false
        }
        for (char in processName) {
            val ok = char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '.' ||
                char == '_' ||
                char == ':'
            if (!ok) {
                return false
            }
        }
        return true
    }

    private fun isValidProfileId(profileId: String): Boolean {
        if (profileId.isBlank() || profileId.length > MAX_PROFILE_ID_LENGTH) return false
        return profileId.all { char ->
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '.' ||
                char == '_' ||
                char == '-'
        }
    }

    private fun isValidPackageName(packageName: String): Boolean =
        isValidProcessName(packageName) && ':' !in packageName

    private fun sanitizeEngineMode(mode: String?): String =
        when (mode) {
            ENGINE_MODE_NATIVE_FIRST,
            ENGINE_MODE_LOW_LATENCY,
            ENGINE_MODE_COMPATIBILITY -> mode
            else -> ENGINE_MODE_NATIVE_FIRST
        }
}
