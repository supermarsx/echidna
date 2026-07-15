package com.echidna.app.data

import com.echidna.app.model.Preset
import org.json.JSONObject

/** Builds the atomic app-to-service profile definition and binding document. */
internal object ProfileBindingSyncCodec {
    fun encode(presets: List<Preset>, appBindings: Map<String, String>): String {
        require(presets.isNotEmpty()) { "profile sync cannot be empty" }
        val profiles = JSONObject()
        presets.forEach { preset ->
            profiles.put(preset.id, JSONObject(PresetSerializer.toJson(preset)))
        }
        val validIds = presets.mapTo(mutableSetOf(), Preset::id)
        val bindings = JSONObject()
        appBindings.forEach { (packageName, presetId) ->
            require(packageName.isNotBlank() && presetId in validIds) {
                "binding must reference a profile in the same snapshot"
            }
            bindings.put(packageName, presetId)
        }
        return JSONObject()
            .put("profiles", profiles)
            .put("appBindings", bindings)
            .toString()
    }
}
