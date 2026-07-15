package com.echidna.app.data

import com.echidna.app.model.Preset
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal const val POLICY_SCHEMA_VERSION = 2
internal const val MAX_POLICY_ENVELOPE_BYTES = 512 * 1024
internal const val MAX_POLICY_PRESET_BYTES = 256 * 1024
private const val MAX_POLICY_ENTRIES = 256
private const val MAX_PROFILE_ID_BYTES = 128
private const val MAX_PROCESS_NAME_BYTES = 255
private const val SERVICE_ENVELOPE_MARGIN_BYTES = 64
private val PROFILE_ID_PATTERN = Regex("[A-Za-z0-9._-]+")
private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9._]+")
private val PROCESS_NAME_PATTERN = Regex("[A-Za-z0-9._:]+")
private val CAPTURE_OWNERS = setOf("zygisk", "lsposed")
private val ENGINE_MODES = setOf("native_first", "low_latency", "compatibility")

internal data class PolicyControlState(
    val masterEnabled: Boolean,
    val bypass: Boolean,
    val panicUntilEpochMs: Long,
    val sidetoneEnabled: Boolean,
    val sidetoneGainDb: Float,
    val engineMode: String,
)

/** Builds the complete atomic app-to-service v2 policy request. */
internal object ProfileBindingSyncCodec {
    fun encode(
        presets: List<Preset>,
        defaultProfileId: String,
        appBindings: Map<String, String>,
        whitelist: Map<String, Boolean>,
        captureOwners: Map<String, String>,
        control: PolicyControlState,
    ): String {
        require(presets.isNotEmpty() && presets.size <= MAX_POLICY_ENTRIES) {
            "policy must contain 1..$MAX_POLICY_ENTRIES profiles"
        }
        require(appBindings.size <= MAX_POLICY_ENTRIES) { "too many app bindings" }
        require(whitelist.size <= MAX_POLICY_ENTRIES) { "too many whitelist entries" }
        require(captureOwners.size <= MAX_POLICY_ENTRIES) { "too many capture owners" }

        val profiles = JSONObject()
        presets.forEach { preset ->
            require(isValidProfileId(preset.id)) { "invalid profile id: ${preset.id}" }
            val rawPreset = PresetSerializer.toJson(preset)
            require(isWellFormedUtf16(rawPreset)) { "profile ${preset.id} contains invalid Unicode" }
            require(utf8Size(rawPreset) <= MAX_POLICY_PRESET_BYTES) {
                "profile ${preset.id} exceeds $MAX_POLICY_PRESET_BYTES bytes"
            }
            profiles.put(preset.id, JSONObject(rawPreset))
        }
        val validIds = presets.mapTo(mutableSetOf(), Preset::id)
        require(defaultProfileId in validIds) { "default profile must exist in the same policy" }

        val bindings = JSONObject()
        appBindings.forEach { (packageName, presetId) ->
            require(isValidPackageName(packageName) && presetId in validIds) {
                "binding must use a valid package and reference a profile in the same policy"
            }
            bindings.put(packageName, presetId)
        }

        val whitelistJson = JSONObject()
        whitelist.forEach { (processName, enabled) ->
            require(isValidProcessName(processName)) { "invalid whitelist process: $processName" }
            whitelistJson.put(processName, enabled)
        }

        val captureOwnersJson = JSONObject()
        captureOwners.forEach { (processName, owner) ->
            require(isValidProcessName(processName) && owner in CAPTURE_OWNERS) {
                "invalid capture owner for $processName"
            }
            captureOwnersJson.put(processName, owner)
        }

        require(control.panicUntilEpochMs >= 0L) { "panic deadline cannot be negative" }
        require(control.sidetoneGainDb.isFinite()) { "sidetone gain must be finite" }
        require(control.engineMode in ENGINE_MODES) { "unsupported engine mode" }
        val controlJson = JSONObject()
            .put("masterEnabled", control.masterEnabled)
            .put("bypass", control.bypass)
            .put("panicUntilEpochMs", control.panicUntilEpochMs)
            .put("sidetoneEnabled", control.sidetoneEnabled)
            .put("sidetoneGainDb", control.sidetoneGainDb.toDouble())
            .put("engineMode", control.engineMode)

        val payload = JSONObject()
            .put("schemaVersion", POLICY_SCHEMA_VERSION)
            .put("profiles", profiles)
            .put("defaultProfileId", defaultProfileId)
            .put("appBindings", bindings)
            .put("whitelist", whitelistJson)
            .put("captureOwners", captureOwnersJson)
            .put("control", controlJson)
            .toString()
        require(isWellFormedUtf16(payload)) { "policy envelope contains invalid Unicode" }
        require(utf8Size(payload) <= MAX_POLICY_ENVELOPE_BYTES - SERVICE_ENVELOPE_MARGIN_BYTES) {
            "policy envelope exceeds Binder/profile-sync limit"
        }
        return payload
    }

    private fun isValidProfileId(id: String): Boolean =
        id.isNotEmpty() && utf8Size(id) <= MAX_PROFILE_ID_BYTES && PROFILE_ID_PATTERN.matches(id)

    private fun isValidProcessName(name: String): Boolean =
        name.isNotEmpty() &&
            utf8Size(name) <= MAX_PROCESS_NAME_BYTES &&
            PROCESS_NAME_PATTERN.matches(name)

    private fun isValidPackageName(name: String): Boolean =
        name.isNotEmpty() &&
            utf8Size(name) <= MAX_PROCESS_NAME_BYTES &&
            PACKAGE_NAME_PATTERN.matches(name)

    private fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        return false
                    }
                    index += 2
                }
                current.isLowSurrogate() -> return false
                else -> index += 1
            }
        }
        return true
    }
}
