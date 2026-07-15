package com.echidna.control.service

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val OPT_IN_FILENAME = "telemetry_opt_in"
private const val DIAGNOSTICS_SCHEMA = "echidna.diagnostics.v1"

internal class TelemetryExporter(private val filesDir: File) {
    private val reader = TelemetryReader()
    private val optInFile = File(filesDir, OPT_IN_FILENAME)

    fun isOptedIn(): Boolean {
        return optInFile.exists() && optInFile.readText().trim() == "1"
    }

    fun setOptIn(enabled: Boolean) {
        if (enabled) {
            optInFile.writeText("1")
        } else {
            if (optInFile.exists()) {
                optInFile.delete()
            }
        }
    }

    fun snapshotJson(): String {
        val snapshot = reader.snapshot()
        return snapshot?.toJson(includeSamples = true) ?: "{}"
    }

    fun exportAnonymized(includeTrends: Boolean): String {
        if (!isOptedIn()) {
            return "{}"
        }
        val snapshot = reader.snapshot() ?: return "{}"
        return if (includeTrends) snapshot.toJson(includeSamples = false) else snapshot.anonymizedJson()
    }

    fun exportDiagnostics(
        includeTrends: Boolean,
        statusJson: String,
        whitelistBindingsJson: String,
        controlStateJson: String
    ): String {
        if (!isOptedIn()) {
            return "{}"
        }
        val status = sanitizeStatus(parseJson(statusJson))
        val whitelist = sanitizeWhitelist(parseJson(whitelistBindingsJson))
        val control = sanitizeControl(parseJson(controlStateJson))
        val telemetry = reader.snapshot()?.diagnosticsJson(includeTrends) ?: JSONObject()
        return JSONObject()
            .put("schema", DIAGNOSTICS_SCHEMA)
            .put("privacy", privacyJson())
            .put("status", status)
            .put("control", control)
            .put("whitelist", whitelist)
            .put("telemetry", telemetry)
            .put("actions", buildActionableSteps(status, whitelist, control))
            .toString()
    }

    fun latestSnapshot(): TelemetrySnapshot? = reader.snapshot()

    private fun privacyJson(): JSONObject = JSONObject()
        .put("rawPackageNames", false)
        .put("rawPresetIds", false)
        .put("rawDeviceNames", false)
        .put("rawSampleTimestamps", false)
        .put("identifierFormat", "sha256-prefix-16")

    private fun sanitizeStatus(input: JSONObject): JSONObject {
        val output = JSONObject()
        copyBoolean(input, output, "magiskModuleInstalled")
        copyBoolean(input, output, "zygiskEnabled")
        copyString(input, output, "selinuxState")
        copyString(input, output, "selinuxStatus")
        copyBoolean(input, output, "javaFallbackActive")
        input.optString("lastError", "").takeIf { it.isNotBlank() }?.let {
            output.put("lastError", redactText(it))
        }
        input.optString("notes", "").takeIf { it.isNotBlank() }?.let {
            output.put("notes", redactText(it))
        }
        input.optJSONObject("cpu")?.let { output.put("cpu", sanitizeCpu(it)) }
        input.optJSONObject("audioStack")?.let { output.put("audioStack", sanitizeAudioStack(it)) }
        return output
    }

    private fun sanitizeCpu(input: JSONObject): JSONObject {
        val output = JSONObject()
        listOf(
            "primaryAbi",
            "cpuFamily",
            "zygiskAbi",
            "supportLevel",
            "message"
        ).forEach { key -> copyString(input, output, key) }
        copyBoolean(input, output, "is64Bit")
        copyBoolean(input, output, "moduleSupported")
        copyBoolean(input, output, "nativeHooksSupported")
        input.optJSONArray("supportedAbis")?.let { output.put("supportedAbis", it) }
        return output
    }

    private fun sanitizeAudioStack(input: JSONObject): JSONObject {
        val output = JSONObject()
        copyString(input, output, "vendorFamily")
        listOf(
            "aaudioSupported",
            "openSlEsAvailable",
            "audioFlingerClientAvailable",
            "tinyAlsaAvailable",
            "lowLatency",
            "proAudio"
        ).forEach { key -> copyBoolean(input, output, key) }
        copyInt(input, output, "sampleRate")
        copyInt(input, output, "framesPerBuffer")
        putHashIfPresent(input, output, "hal", "halHash")
        putHashIfPresent(input, output, "manufacturer", "manufacturerHash")
        putHashIfPresent(input, output, "boardPlatform", "boardPlatformHash")
        return output
    }

    private fun sanitizeWhitelist(input: JSONObject): JSONObject {
        val whitelist = input.optJSONObject("whitelist") ?: JSONObject()
        val appBindings = input.optJSONObject("appBindings") ?: JSONObject()
        val enabled = JSONArray()
        val disabled = JSONArray()
        jsonKeys(whitelist).forEach { key ->
            if (whitelist.optBoolean(key, false)) {
                enabled.put(hashIdentifier(key))
            } else {
                disabled.put(hashIdentifier(key))
            }
        }
        val bindings = JSONArray()
        jsonKeys(appBindings).forEach { packageName ->
            bindings.put(
                JSONObject()
                    .put("packageHash", hashIdentifier(packageName))
                    .put("presetHash", hashIdentifier(appBindings.optString(packageName)))
            )
        }
        return JSONObject()
            .put(
                "counts",
                JSONObject()
                    .put("enabledWhitelist", enabled.length())
                    .put("disabledWhitelist", disabled.length())
                    .put("appBindings", bindings.length())
            )
            .put("enabledPackageHashes", enabled)
            .put("disabledPackageHashes", disabled)
            .put("bindingHashes", bindings)
    }

    private fun sanitizeControl(input: JSONObject): JSONObject {
        val output = JSONObject()
        output.put("masterEnabled", input.optBoolean("masterEnabled", true))
        output.put("bypass", input.optBoolean("bypass", false))
        output.put("sidetoneEnabled", input.optBoolean("sidetoneEnabled", false))
        output.put("sidetoneGainDb", input.optDouble("sidetoneGainDb", 0.0))
        output.put("engineMode", input.optString("engineMode", "native_first"))
        output.put(
            "panicActive",
            input.optLong("panicUntilEpochMs", 0L) > System.currentTimeMillis()
        )
        return output
    }

    private fun buildActionableSteps(
        status: JSONObject,
        whitelist: JSONObject,
        control: JSONObject
    ): JSONArray {
        val actions = JSONArray()
        val counts = whitelist.optJSONObject("counts") ?: JSONObject()
        if (
            control.optBoolean("masterEnabled", true) &&
            !control.optBoolean("bypass", false) &&
            counts.optInt("enabledWhitelist", 0) == 0
        ) {
            actions.put(
                action(
                    "configure_whitelist",
                    "Enable at least one target app in the Per-App Whitelist.",
                    "The native and shim paths fail closed when no app is explicitly allowed."
                )
            )
        }
        if (!status.optBoolean("magiskModuleInstalled", false)) {
            actions.put(
                action(
                    "install_module",
                    "Install or re-flash the Echidna Magisk module and reboot.",
                    "Native app-process hooks are unavailable until the module is present."
                )
            )
        }
        if (!status.optBoolean("zygiskEnabled", false)) {
            actions.put(
                action(
                    "enable_zygisk",
                    "Enable Zygisk in Magisk and reboot.",
                    "Zygisk is required for native target-app injection."
                )
            )
        }
        return actions
    }

    private fun action(code: String, title: String, detail: String): JSONObject = JSONObject()
        .put("code", code)
        .put("title", title)
        .put("detail", detail)

    private fun putHashIfPresent(
        input: JSONObject,
        output: JSONObject,
        sourceKey: String,
        targetKey: String
    ) {
        input.optString(sourceKey, "").takeIf { it.isNotBlank() }?.let {
            output.put(targetKey, hashIdentifier(it))
        }
    }

    private fun copyBoolean(input: JSONObject, output: JSONObject, key: String) {
        if (input.has(key)) output.put(key, input.optBoolean(key))
    }

    private fun copyInt(input: JSONObject, output: JSONObject, key: String) {
        if (input.has(key)) output.put(key, input.optInt(key))
    }

    private fun copyString(input: JSONObject, output: JSONObject, key: String) {
        input.optString(key, "").takeIf { it.isNotBlank() }?.let {
            output.put(key, redactText(it))
        }
    }

    private fun parseJson(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: JSONException) {
        JSONObject()
    }

    private fun jsonKeys(json: JSONObject): List<String> {
        val keys = mutableListOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            keys += iterator.next()
        }
        return keys.sorted()
    }

    private fun hashIdentifier(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val prefix = bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "sha256:$prefix"
    }

    private fun redactText(value: String): String =
        value
            .replace(PATH_PATTERN, "[redacted-path]")
            .replace(PACKAGE_PATTERN, "[redacted-id]")

    private companion object {
        val PATH_PATTERN = Regex("""/(?:data|sdcard|storage|mnt)/[^\s;:,]+""")
        val PACKAGE_PATTERN = Regex("""\b[a-zA-Z][\w]*(?:\.[a-zA-Z][\w-]*){1,}\b""")
    }
}
