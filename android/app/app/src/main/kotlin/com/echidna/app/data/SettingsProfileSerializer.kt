package com.echidna.app.data

import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.SettingsProfile
import com.echidna.app.model.SettingsState
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SettingsProfileStore(
    val settings: SettingsState,
    val profiles: List<SettingsProfile>,
    val activeProfileId: String?
)

object SettingsProfileSerializer {
    private const val PROFILE_KIND = "echidna.settings.profile"
    private const val STORE_KIND = "echidna.settings.store"
    private const val VERSION = 1

    fun settingsToJson(settings: SettingsState): String =
        settingsToJsonObject(settings).toString()

    fun settingsFromJson(json: String): SettingsState? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val settingsObj = root.optJSONObject("settings") ?: root
        return settingsFromJsonObject(settingsObj)
    }

    fun profileToJson(profile: SettingsProfile): String =
        profileToJsonObject(profile).toString()

    fun profileFromJson(json: String): SettingsProfile? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val settingsObj = root.optJSONObject("settings") ?: root.takeIf { it.has("engine") }
        val settings = settingsObj?.let(::settingsFromJsonObject) ?: return null
        val name = root.optString("name").trim().ifBlank { "Imported Settings" }
        return SettingsProfile(
            id = root.optString("id").takeIf { it.isUuidLike() } ?: UUID.randomUUID().toString(),
            name = name.take(80),
            createdAtEpochMs = root.optLong("createdAtEpochMs", now).coerceAtLeast(0L),
            updatedAtEpochMs = root.optLong("updatedAtEpochMs", now).coerceAtLeast(0L),
            settings = settings
        )
    }

    fun storeToJson(store: SettingsProfileStore): String {
        val profiles = JSONArray()
        store.profiles.forEach { profiles.put(profileToJsonObject(it)) }
        return JSONObject().apply {
            put("kind", STORE_KIND)
            put("version", VERSION)
            put("activeProfileId", store.activeProfileId)
            put("settings", settingsToJsonObject(store.settings))
            put("profiles", profiles)
        }.toString()
    }

    fun storeFromJson(json: String): SettingsProfileStore? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val settings = root.optJSONObject("settings")?.let(::settingsFromJsonObject)
            ?: SettingsState()
        val profilesArray = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = mutableListOf<SettingsProfile>()
        for (i in 0 until profilesArray.length()) {
            profilesArray.optJSONObject(i)?.let { obj ->
                profileFromJson(obj.toString())?.let { profiles.add(it) }
            }
        }
        val activeId = root.optString("activeProfileId").ifBlank { null }
            ?.takeIf { id -> profiles.any { it.id == id } }
        return SettingsProfileStore(settings, profiles, activeId)
    }

    private fun profileToJsonObject(profile: SettingsProfile): JSONObject =
        JSONObject().apply {
            put("kind", PROFILE_KIND)
            put("version", VERSION)
            put("id", profile.id)
            put("name", profile.name)
            put("createdAtEpochMs", profile.createdAtEpochMs)
            put("updatedAtEpochMs", profile.updatedAtEpochMs)
            put("settings", settingsToJsonObject(profile.settings))
        }

    private fun settingsToJsonObject(settings: SettingsState): JSONObject =
        JSONObject().apply {
            put("startup", JSONObject().apply {
                put("startWithSystem", settings.startWithSystem)
                put("autoStartEngine", settings.autoStartEngine)
                put("restoreLastProfile", settings.restoreLastProfile)
            })
            put("engine", JSONObject().apply {
                put("mode", settings.engineMode.id)
                put("latencyMode", latencyModeString(settings.latencyMode))
                put("sidetoneEnabled", settings.sidetoneEnabled)
                put("sidetoneLevelDb", settings.sidetoneLevelDb)
                put("masterEnabled", settings.masterEnabled)
                put("bypass", settings.bypass)
                put("defaultPresetId", settings.defaultPresetId)
            })
            put("diagnostics", JSONObject().apply {
                put("debugMode", settings.debugMode)
                put("telemetryOptIn", settings.telemetryOptIn)
                put("verboseLogging", settings.verboseLogging)
            })
            put("safety", JSONObject().apply {
                put("failClosed", settings.failClosed)
                put("autoBypassOnError", settings.autoBypassOnError)
                put("panicHoldMinutes", settings.panicHoldMinutes)
            })
            put("control", JSONObject().apply {
                put("persistentNotification", settings.persistentNotification)
                put("quickControlsEnabled", settings.quickControlsEnabled)
                put("widgetControlsEnabled", settings.widgetControlsEnabled)
            })
            put("alerts", JSONObject().apply {
                put("showInstallAlerts", settings.showInstallAlerts)
                put("showBridgeAlerts", settings.showBridgeAlerts)
                put("showHardwareAlerts", settings.showHardwareAlerts)
                put("showInstallMixupAlerts", settings.showInstallMixupAlerts)
                put("alertLatencyThresholdMs", settings.alertLatencyThresholdMs)
                put("alertXrunThreshold", settings.alertXrunThreshold)
                put("remindCompatibilityProbe", settings.remindCompatibilityProbe)
            })
        }

    private fun settingsFromJsonObject(root: JSONObject): SettingsState {
        val startup = root.optJSONObject("startup")
        val engine = root.optJSONObject("engine")
        val diagnostics = root.optJSONObject("diagnostics")
        val safety = root.optJSONObject("safety")
        val control = root.optJSONObject("control")
        val alerts = root.optJSONObject("alerts")
        return SettingsState(
            startWithSystem = startup.optBooleanCompat("startWithSystem", false),
            autoStartEngine = startup.optBooleanCompat("autoStartEngine", false),
            restoreLastProfile = startup.optBooleanCompat("restoreLastProfile", true),
            engineMode = DspEngineMode.fromId(engine?.optString("mode")),
            latencyMode = parseLatencyMode(engine?.optString("latencyMode")),
            sidetoneEnabled = engine.optBooleanCompat("sidetoneEnabled", false),
            sidetoneLevelDb = engine.optDoubleCompat("sidetoneLevelDb", -24.0)
                .toFloat()
                .coerceIn(-60f, -6f),
            debugMode = diagnostics.optBooleanCompat("debugMode", false),
            telemetryOptIn = diagnostics.optBooleanCompat("telemetryOptIn", false),
            verboseLogging = diagnostics.optBooleanCompat("verboseLogging", false),
            failClosed = safety.optBooleanCompat("failClosed", true),
            autoBypassOnError = safety.optBooleanCompat("autoBypassOnError", true),
            panicHoldMinutes = safety.optIntCompat("panicHoldMinutes", 5).coerceIn(1, 60),
            persistentNotification = control.optBooleanCompat("persistentNotification", true),
            quickControlsEnabled = control.optBooleanCompat("quickControlsEnabled", true),
            widgetControlsEnabled = control.optBooleanCompat("widgetControlsEnabled", true),
            showInstallAlerts = alerts.optBooleanCompat("showInstallAlerts", true),
            showBridgeAlerts = alerts.optBooleanCompat("showBridgeAlerts", true),
            showHardwareAlerts = alerts.optBooleanCompat("showHardwareAlerts", true),
            showInstallMixupAlerts = alerts.optBooleanCompat("showInstallMixupAlerts", true),
            alertLatencyThresholdMs = alerts.optIntCompat("alertLatencyThresholdMs", 40)
                .coerceIn(5, 250),
            alertXrunThreshold = alerts.optIntCompat("alertXrunThreshold", 3)
                .coerceIn(1, 100),
            remindCompatibilityProbe = alerts.optBooleanCompat("remindCompatibilityProbe", true),
            masterEnabled = engine.optBooleanCompat("masterEnabled", true),
            bypass = engine.optBooleanCompat("bypass", false),
            defaultPresetId = engine?.optString("defaultPresetId")?.ifBlank { null }
        )
    }

    private fun latencyModeString(mode: LatencyMode): String =
        when (mode) {
            LatencyMode.LOW_LATENCY -> "LL"
            LatencyMode.BALANCED -> "Balanced"
            LatencyMode.HIGH_QUALITY -> "HQ"
        }

    private fun parseLatencyMode(value: String?): LatencyMode =
        when (value) {
            "LL", "Low-Latency" -> LatencyMode.LOW_LATENCY
            "HQ", "High-Quality" -> LatencyMode.HIGH_QUALITY
            else -> LatencyMode.BALANCED
        }

    private fun JSONObject?.optBooleanCompat(name: String, fallback: Boolean): Boolean =
        this?.optBoolean(name, fallback) ?: fallback

    private fun JSONObject?.optDoubleCompat(name: String, fallback: Double): Double =
        this?.optDouble(name, fallback) ?: fallback

    private fun JSONObject?.optIntCompat(name: String, fallback: Int): Int =
        this?.optInt(name, fallback) ?: fallback

    private fun String.isUuidLike(): Boolean =
        runCatching { UUID.fromString(this) }.isSuccess
}
