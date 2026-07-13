package com.echidna.control.service

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONException
import org.json.JSONObject

private const val STORE_TAG = "EchidnaProfileStore"
private const val MAX_PROCESS_NAME_LENGTH = 128
private const val ENGINE_MODE_NATIVE_FIRST = "native_first"
private const val ENGINE_MODE_LOW_LATENCY = "low_latency"
private const val ENGINE_MODE_COMPATIBILITY = "compatibility"

/**
 * Maintains JSON profile definitions and pushes updates to the Zygisk bridge.
 */
class ProfileStore(
    storageDir: File,
    private val syncBridge: ProfileSyncChannel,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val lock = ReentrantReadWriteLock()
    private val storageFile = File(storageDir, "profiles.json")
    private val profiles = mutableMapOf<String, JSONObject>()
    private val whitelist = mutableMapOf<String, Boolean>()
    private val appBindings = mutableMapOf<String, String>()

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
        executor.shutdownNow()
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
                appBindings.remove(packageName)
            } else {
                appBindings[packageName] = presetId
            }
            buildSnapshotLocked()
        }
        scheduleFlush(snapshot)
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

    /** Engages panic: forces bypass and records an expiry the engine honours (spec §12). */
    fun panic(holdMs: Long) {
        val snapshot = lock.write {
            bypass = true
            masterEnabled = false
            panicUntilEpochMs = if (holdMs > 0L) System.currentTimeMillis() + holdMs else 0L
            buildSnapshotLocked()
        }
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
        if (executor.isShutdown) {
            return
        }
        executor.execute {
            writeToDisk(snapshot)
            syncBridge.pushProfiles(snapshot)
        }
    }

    private fun writeToDisk(payload: String) {
        try {
            FileOutputStream(storageFile).use {
                it.write(payload.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(STORE_TAG, "Failed to persist profiles", e)
        }
    }

    private fun loadFromDisk() {
        if (!storageFile.exists()) {
            return
        }
        try {
            val snapshot = FileInputStream(storageFile).use { input ->
                val content = input.readBytes().toString(StandardCharsets.UTF_8)
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
                        sidetoneEnabled = controlJson.optBoolean("sidetoneEnabled", sidetoneEnabled)
                        sidetoneGainDb = controlJson.optDouble("sidetoneGainDb", sidetoneGainDb)
                        val persistedEngineMode = controlJson.optString("engineMode", engineMode)
                        engineMode = sanitizeEngineMode(persistedEngineMode)
                    }
                    buildSnapshotLocked()
                }
            }
            syncBridge.pushProfiles(snapshot)
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
