package com.echidna.app.data

import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.ControlState
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.RuntimeRouteTelemetry
import com.echidna.app.model.TelemetrySample
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2
import com.echidna.app.model.TELEMETRY_VERIFICATION_CALLER_ATTESTED_BINDER_V1
import com.echidna.app.model.TELEMETRY_VERIFICATION_EFFECT_HMAC_V1
import com.echidna.app.model.TELEMETRY_VERIFICATION_MIXED
import com.echidna.app.model.TELEMETRY_VERIFICATION_UNVERIFIED
import com.echidna.app.model.WhitelistBindings
import org.json.JSONArray
import org.json.JSONObject

internal object TelemetryParser {
    fun parse(json: String): TelemetrySnapshot? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val totalCallbacks = root.optLong("totalCallbacks")
        val averageLatency = root.optDouble("averageLatencyMs", 0.0).toFloat()
        val averageCpu = root.optDouble("averageCpuPercent", 0.0).toFloat()
        val inputRms = root.optDouble("inputRms", -120.0).toFloat()
        val outputRms = root.optDouble("outputRms", -120.0).toFloat()
        val inputPeak = root.optDouble("inputPeak", -120.0).toFloat()
        val outputPeak = root.optDouble("outputPeak", -120.0).toFloat()
        val detectedPitch = root.optDouble("detectedPitchHz", 0.0).toFloat()
        val targetPitch = root.optDouble("targetPitchHz", 0.0).toFloat()
        val formantShift = root.optDouble("formantShiftCents", 0.0).toFloat()
        val formantWidth = root.optDouble("formantWidth", 0.0).toFloat()
        val xruns = root.optInt("xruns", 0)
        val warningFlags = root.optInt("warningFlags", 0)
        val warnings = mutableListOf<String>()
        if (warningFlags and (1 shl 0) != 0) warnings += "Latency exceeded guard threshold"
        if (warningFlags and (1 shl 1) != 0) warnings += "CPU usage exceeded 75%"
        if (warningFlags and (1 shl 2) != 0) warnings += "XRuns detected"
        if (warningFlags and (1 shl 3) != 0) warnings += "Preset safety warning active"
        if (warningFlags and (1 shl 4) != 0) warnings += "Plugin signature verification failed"

        val samples = parseSamples(root.optJSONArray("samples"))
        val hooks = parseHooks(root.optJSONArray("hooks"))
        val rootVerification = root.optString("verification", "")
        val scopedV2 = root.optInt("schemaVersion", 0) == 2 &&
            root.optString("type", "") == "telemetrySnapshot" &&
            rootVerification in SUPPORTED_SNAPSHOT_VERIFICATIONS
        val currentPolicyGeneration = root.optLong("currentPolicyGeneration", 0L)
            .takeIf { scopedV2 && it > 0L }
            ?: 0L
        val routes = if (scopedV2) {
            parseRuntimeRoutes(root.optJSONArray("routes"), rootVerification)
        } else {
            emptyList()
        }

        return TelemetrySnapshot(
            totalCallbacks = totalCallbacks,
            averageLatencyMs = averageLatency,
            averageCpuPercent = averageCpu,
            inputRms = inputRms,
            outputRms = outputRms,
            inputPeak = inputPeak,
            outputPeak = outputPeak,
            detectedPitchHz = detectedPitch,
            targetPitchHz = targetPitch,
            formantShiftCents = formantShift,
            formantWidth = formantWidth,
            xruns = xruns,
            warnings = warnings,
            samples = samples,
            hooks = hooks,
            verification = if (scopedV2) rootVerification else TELEMETRY_VERIFICATION_UNVERIFIED,
            currentPolicyGeneration = currentPolicyGeneration,
            routes = routes
        )
    }

    /** Parses the combined status JSON (t2-e6 signatures §3) into a [ModuleStatus]. */
    fun parseModuleStatus(json: String): ModuleStatus? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val cpuObj = root.optJSONObject("cpu")
        val cpu = CpuArchInfo(
            primaryAbi = cpuObj?.optString("primaryAbi").orEmpty(),
            supportedAbis = cpuObj?.optJSONArray("supportedAbis").toStringList(),
            cpuFamily = cpuObj?.optString("cpuFamily").orEmpty().ifBlank { "Unknown" },
            is64Bit = cpuObj?.optBoolean("is64Bit", false) ?: false,
            zygiskAbi = cpuObj?.optString("zygiskAbi").orEmpty(),
            moduleSupported = cpuObj?.optBoolean("moduleSupported", false) ?: false,
            nativeHooksSupported = cpuObj?.optBoolean("nativeHooksSupported", false) ?: false,
            supportLevel = cpuObj?.optString("supportLevel").orEmpty().ifBlank { "unknown" },
            message = cpuObj?.optString("message").orEmpty()
        )
        val stackObj = root.optJSONObject("audioStack")
        val audioStack = AudioStackInfo(
            hal = stackObj?.optString("hal").orEmpty(),
            manufacturer = stackObj?.optString("manufacturer").orEmpty(),
            boardPlatform = stackObj?.optString("boardPlatform").orEmpty(),
            vendorFamily = stackObj?.optString("vendorFamily").orEmpty().ifBlank { "Unknown" },
            aaudioSupported = stackObj?.optBoolean("aaudioSupported", false) ?: false,
            openSlEsAvailable = stackObj?.optBoolean("openSlEsAvailable", false) ?: false,
            audioFlingerClientAvailable = stackObj?.optBoolean(
                "audioFlingerClientAvailable",
                false
            ) ?: false,
            tinyAlsaAvailable = stackObj?.optBoolean("tinyAlsaAvailable", false) ?: false,
            lowLatency = stackObj?.optBoolean("lowLatency", false) ?: false,
            proAudio = stackObj?.optBoolean("proAudio", false) ?: false,
            sampleRate = stackObj?.optInt("sampleRate", 0) ?: 0,
            framesPerBuffer = stackObj?.optInt("framesPerBuffer", 0) ?: 0
        )
        return ModuleStatus(
            magiskModuleInstalled = root.optBoolean("magiskModuleInstalled", false),
            zygiskEnabled = root.optBoolean("zygiskEnabled", false),
            selinuxState = root.optString("selinuxState").ifBlank { "UNKNOWN" },
            selinuxStatus = root.optString("selinuxStatus").ifBlank { "Unknown" },
            policyToolAvailable = root.optBoolean("policyToolAvailable", false),
            policyAppliedVerified = root.optBoolean("policyAppliedVerified", false),
            nativeRouteVerified = root.optBoolean("nativeRouteVerified", false),
            javaFallbackRecommended = root.optBoolean(
                "javaFallbackRecommended",
                root.optBoolean("javaFallbackActive", false),
            ),
            cpu = cpu,
            audioStack = audioStack,
            notes = root.optString("notes").ifBlank { null },
            lastError = root.optString("lastError").ifBlank { null }
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val values = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i).takeIf { it.isNotBlank() } ?: continue
            values += value
        }
        return values
    }

    /** Parses the global control-state JSON (t2-e6 signatures §4) into a [ControlState]. */
    fun parseControlState(json: String): ControlState? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return ControlState(
            masterEnabled = root.optBoolean("masterEnabled", true),
            bypass = root.optBoolean("bypass", false),
            panicUntilEpochMs = root.optLong("panicUntilEpochMs", 0L),
            sidetoneEnabled = root.optBoolean("sidetoneEnabled", false),
            sidetoneGainDb = root.optDouble("sidetoneGainDb", 0.0).toFloat(),
            engineMode = DspEngineMode.fromId(root.optString("engineMode"))
        )
    }

    /** Parses the whitelist/app-binding read-back JSON (t2-e6 signatures §2). */
    fun parseWhitelistBindings(json: String): WhitelistBindings? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val whitelist = mutableMapOf<String, Boolean>()
        root.optJSONObject("whitelist")?.let { obj ->
            obj.keys().forEach { key -> whitelist[key] = obj.optBoolean(key, false) }
        }
        val bindings = mutableMapOf<String, String>()
        root.optJSONObject("appBindings")?.let { obj ->
            obj.keys().forEach { key -> bindings[key] = obj.optString(key) }
        }
        return WhitelistBindings(whitelist, bindings)
    }

    private fun parseSamples(array: JSONArray?): List<TelemetrySample> {
        if (array == null) return emptyList()
        val list = ArrayList<TelemetrySample>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list += TelemetrySample(
                timestampNs = obj.optLong("timestampNs"),
                durationUs = obj.optInt("durationUs"),
                cpuUs = obj.optInt("cpuUs"),
                flags = obj.optInt("flags"),
                xruns = obj.optInt("xruns")
            )
        }
        return list
    }

    private fun parseHooks(array: JSONArray?): List<HookTelemetry> {
        if (array == null) return emptyList()
        val list = ArrayList<HookTelemetry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            list += HookTelemetry(
                name = name,
                library = obj.optString("library"),
                symbol = obj.optString("symbol"),
                reason = obj.optString("reason"),
                attempts = obj.optInt("attempts"),
                successes = obj.optInt("successes"),
                failures = obj.optInt("failures"),
                lastAttemptNs = obj.optLong("lastAttemptNs"),
                lastSuccessNs = obj.optLong("lastSuccessNs")
            )
        }
        return list
    }

    private fun parseRuntimeRoutes(
        array: JSONArray?,
        rootVerification: String,
    ): List<RuntimeRouteTelemetry> {
        if (array == null) return emptyList()
        val count = array.length().coerceAtMost(MAX_RUNTIME_ROUTES)
        val routes = ArrayList<RuntimeRouteTelemetry>(count)
        for (index in 0 until count) {
            val item = array.optJSONObject(index) ?: continue
            val generation = item.optLong("generation", 0L)
            val mutations = item.optLong("mutations", 0L)
            if (generation <= 0L || mutations < 0L) continue
            val routeVerification = if (item.has("verification")) {
                item.optString("verification", "")
                    .takeIf { it in SUPPORTED_ROUTE_VERIFICATIONS }
                    ?: TELEMETRY_VERIFICATION_UNVERIFIED
            } else {
                legacyRouteVerification(rootVerification)
            }
            routes += RuntimeRouteTelemetry(
                process = item.optString("process", ""),
                route = item.optString("route", "unknown"),
                generation = generation,
                state = item.optString("state", "error"),
                sequence = item.optLong("sequence", 0L),
                ageMs = item.optLong("ageMs", Long.MAX_VALUE).coerceAtLeast(0L),
                recentMutation = item.optBoolean("recentMutation", false),
                blocks = item.optLong("blocks", 0L).coerceAtLeast(0L),
                frames = item.optLong("frames", 0L).coerceAtLeast(0L),
                failures = item.optLong("failures", 0L).coerceAtLeast(0L),
                mutations = mutations,
                verification = routeVerification,
            )
        }
        return routes
    }

    private const val MAX_RUNTIME_ROUTES = 128

    private val SUPPORTED_ROUTE_VERIFICATIONS = setOf(
        TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2,
        TELEMETRY_VERIFICATION_EFFECT_HMAC_V1,
        TELEMETRY_VERIFICATION_CALLER_ATTESTED_BINDER_V1,
    )
    private val SUPPORTED_SNAPSHOT_VERIFICATIONS = SUPPORTED_ROUTE_VERIFICATIONS +
        TELEMETRY_VERIFICATION_MIXED

    private fun legacyRouteVerification(rootVerification: String): String =
        rootVerification.takeIf { it in SUPPORTED_ROUTE_VERIFICATIONS }
            ?: TELEMETRY_VERIFICATION_UNVERIFIED
}
