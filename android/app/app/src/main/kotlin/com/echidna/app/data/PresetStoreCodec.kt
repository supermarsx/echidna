package com.echidna.app.data

import com.echidna.app.model.Preset
import org.json.JSONArray
import org.json.JSONObject

internal data class PersistedPresetStore(
    val presets: List<Preset>,
    val activePresetId: String,
    val defaultPresetId: String,
    /** Null means a legacy store whose service-owned bindings must be adopted once. */
    val appBindings: Map<String, String>? = null,
)

/** Version-tolerant codec for the app-owned preset selection store. */
internal object PresetStoreCodec {
    fun encode(store: PersistedPresetStore): String {
        require(store.presets.isNotEmpty()) { "preset store cannot be empty" }
        val array = JSONArray()
        store.presets.forEach { preset ->
            array.put(JSONObject(PresetSerializer.toJson(preset)))
        }
        val root = JSONObject()
            .put("activePresetId", store.activePresetId)
            .put("defaultPresetId", store.defaultPresetId)
            .put("presets", array)
        store.appBindings?.let { bindings ->
            val bindingJson = JSONObject()
            val validIds = store.presets.mapTo(mutableSetOf(), Preset::id)
            bindings.forEach { (packageName, presetId) ->
                if (packageName.isNotBlank() && presetId in validIds) {
                    bindingJson.put(packageName, presetId)
                }
            }
            root.put("appBindings", bindingJson)
        }
        return root.toString()
    }

    fun decode(json: String): PersistedPresetStore? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val array = root.optJSONArray("presets") ?: return null
        val presets = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                PresetSerializer.fromJson(item.toString())?.let(::add)
            }
        }
        if (presets.isEmpty()) return null
        val active = root.optString("activePresetId")
            .takeIf { candidate -> presets.any { it.id == candidate } }
            ?: presets.first().id
        val default = root.optString("defaultPresetId")
            .takeIf { candidate -> presets.any { it.id == candidate } }
            ?: presets.first().id
        val validIds = presets.mapTo(mutableSetOf(), Preset::id)
        val appBindings: Map<String, String>? = if (root.has("appBindings")) {
            buildMap {
                val bindings = root.optJSONObject("appBindings") ?: JSONObject()
                val keys = bindings.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    val presetId = bindings.optString(packageName)
                    if (packageName.isNotBlank() && presetId in validIds) {
                        put(packageName, presetId)
                    }
                }
            }
        } else {
            null
        }
        return PersistedPresetStore(presets, active, default, appBindings)
    }
}
