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
private const val MAX_PROCESS_NAME_LENGTH = 128
private const val MAX_PROFILE_STORE_BYTES = 10L * 1024L * 1024L
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
        val parsed = try {
            JSONObject(profileJson)
        } catch (e: JSONException) {
            Log.w(STORE_TAG, "Rejected invalid profile JSON", e)
            return
        }
        if (profileJson.toByteArray(StandardCharsets.UTF_8).size > 512 * 1024) {
            Log.w(STORE_TAG, "Rejected profile JSON: too large")
            return
        }
        if (!isStructuredPreset(parsed)) {
            Log.w(STORE_TAG, "Rejected profile JSON: missing modules/engine")
            return
        }
        val snapshot = lock.write {
            profiles[id] = parsed
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
    }

    fun deleteProfile(id: String) {
        val snapshot = lock.write {
            if (profiles.remove(id) != null) {
                appBindings.entries.removeAll { (_, presetId) -> presetId == id }
                buildSnapshotLocked()
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
            whitelist[processName] = enabled
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
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
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
    }

    fun setAppBinding(packageName: String, presetId: String) {
        if (!isValidProcessName(packageName)) {
            Log.w(STORE_TAG, "Rejected invalid package name: $packageName")
            return
        }
        val snapshot = lock.write {
            if (presetId.isBlank()) {
                if (appBindings.remove(packageName) == null) null else buildSnapshotLocked()
            } else if (!profiles.containsKey(presetId)) {
                null
            } else {
                appBindings[packageName] = presetId
                buildSnapshotLocked()
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

    /** Atomically replaces all app-owned profiles and bindings from one validated document. */
    fun synchronizeProfilesAndBindings(stateJson: String): Boolean {
        if (stateJson.toByteArray(StandardCharsets.UTF_8).size > MAX_PROFILE_STORE_BYTES) {
            Log.w(STORE_TAG, "Rejected profile/binding state: too large")
            return false
        }
        val root = try {
            JSONObject(stateJson)
        } catch (exception: JSONException) {
            Log.w(STORE_TAG, "Rejected invalid profile/binding state JSON", exception)
            return false
        }
        val profileJson = root.optJSONObject("profiles") ?: return false
        val bindingJson = root.optJSONObject("appBindings") ?: return false
        val nextProfiles = linkedMapOf<String, JSONObject>()
        val profileKeys = profileJson.keys()
        while (profileKeys.hasNext()) {
            val id = profileKeys.next()
            val preset = profileJson.optJSONObject(id) ?: return false
            if (id.isBlank() || id.length > MAX_PROFILE_ID_LENGTH || !isStructuredPreset(preset)) {
                return false
            }
            nextProfiles[id] = preset
        }
        if (nextProfiles.isEmpty()) return false

        val nextBindings = linkedMapOf<String, String>()
        val bindingKeys = bindingJson.keys()
        while (bindingKeys.hasNext()) {
            val packageName = bindingKeys.next()
            val presetId = bindingJson.optString(packageName)
            if (!isValidProcessName(packageName) || !nextProfiles.containsKey(presetId)) {
                Log.w(STORE_TAG, "Rejected dangling or invalid app binding: $packageName")
                return false
            }
            nextBindings[packageName] = presetId
        }

        val snapshot = lock.write {
            profiles.clear()
            profiles.putAll(nextProfiles)
            appBindings.clear()
            appBindings.putAll(nextBindings)
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
        return true
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
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
    }

    fun setBypass(enabled: Boolean) {
        val snapshot = lock.write {
            bypass = enabled
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
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
            buildSnapshotLocked()
        }
        schedulePanicExpiry(deadline)
        scheduleFlush(snapshot)
    }

    fun setSidetone(enabled: Boolean, gainDb: Double) {
        val snapshot = lock.write {
            sidetoneEnabled = enabled
            sidetoneGainDb = gainDb
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
    }

    fun setEngineMode(mode: String) {
        val snapshot = lock.write {
            engineMode = sanitizeEngineMode(mode)
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
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

    private fun buildSnapshotLocked(): String {
        val json = JSONObject()
        val profilesJson = JSONObject()
        profiles.forEach { (id, entry) -> profilesJson.put(id, entry) }
        json.put("profiles", profilesJson)
        val whitelistJson = JSONObject()
        whitelist.forEach { (process, allowed) -> whitelistJson.put(process, allowed) }
        json.put("whitelist", whitelistJson)
        val bindingsJson = JSONObject()
        appBindings.forEach { (pkg, presetId) -> bindingsJson.put(pkg, presetId) }
        json.put("appBindings", bindingsJson)
        json.put("control", controlStateJsonLocked())
        return json.toString()
    }

    private fun scheduleFlush(snapshot: String) {
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
            buildSnapshotLocked()
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
        if (storageFile.length() > MAX_PROFILE_STORE_BYTES) {
            Log.w(STORE_TAG, "Persisted profile store is too large; ignoring it")
            return
        }
        try {
            var panicWasExpired = false
            val snapshot = readProfileStoreAtomic(storageFile).let { content ->
                val json = JSONObject(content)
                lock.write {
                    val profilesJson = json.optJSONObject("profiles") ?: JSONObject()
                    profiles.clear()
                    profilesJson.keys().forEach { key ->
                        profiles[key] = profilesJson.getJSONObject(key)
                    }
                    val whitelistJson = json.optJSONObject("whitelist") ?: JSONObject()
                    whitelist.clear()
                    whitelistJson.keys().forEach { key ->
                        whitelist[key] = whitelistJson.getBoolean(key)
                    }
                    val bindingsJson = json.optJSONObject("appBindings") ?: JSONObject()
                    appBindings.clear()
                    bindingsJson.keys().forEach { key ->
                        appBindings[key] = bindingsJson.getString(key)
                    }
                    val controlJson = json.optJSONObject("control")
                    if (controlJson != null) {
                        masterEnabled = controlJson.optBoolean("masterEnabled", masterEnabled)
                        bypass = controlJson.optBoolean("bypass", bypass)
                        panicUntilEpochMs = controlJson.optLong("panicUntilEpochMs", panicUntilEpochMs)
                        if (panicUntilEpochMs > 0L && panicUntilEpochMs <= nowEpochMs()) {
                            panicUntilEpochMs = 0L
                            panicWasExpired = true
                        }
                        sidetoneEnabled = controlJson.optBoolean("sidetoneEnabled", sidetoneEnabled)
                        sidetoneGainDb = controlJson.optDouble("sidetoneGainDb", sidetoneGainDb)
                        val persistedEngineMode = controlJson.optString("engineMode", engineMode)
                        engineMode = sanitizeEngineMode(persistedEngineMode)
                    }
                    buildSnapshotLocked()
                }
            }
            syncBridge.pushProfiles(snapshot)
            val panicDeadline = lock.read { panicUntilEpochMs }
            schedulePanicExpiry(panicDeadline)
            if (panicWasExpired) {
                scheduleFlush(snapshot)
            }
        } catch (e: Exception) {
            Log.w(STORE_TAG, "Unable to read persisted profiles", e)
        }
    }

    private fun isStructuredPreset(root: JSONObject): Boolean {
        if (root.optJSONArray("modules") != null && root.optJSONObject("engine") != null) {
            return true
        }
        val profiles = root.optJSONObject("profiles") ?: return false
        val keys = profiles.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val preset = profiles.optJSONObject(key) ?: continue
            if (preset.optJSONArray("modules") != null && preset.optJSONObject("engine") != null) {
                return true
            }
        }
        return false
    }

    private fun isValidProcessName(processName: String): Boolean {
        if (processName.isBlank() || processName.length > MAX_PROCESS_NAME_LENGTH) {
            return false
        }
        for (char in processName) {
            val ok = char.isLetterOrDigit() || char == '.' || char == '_' || char == ':'
            if (!ok) {
                return false
            }
        }
        return true
    }

    private fun sanitizeEngineMode(mode: String?): String =
        when (mode) {
            ENGINE_MODE_NATIVE_FIRST,
            ENGINE_MODE_LOW_LATENCY,
            ENGINE_MODE_COMPATIBILITY -> mode
            else -> ENGINE_MODE_NATIVE_FIRST
        }
}
