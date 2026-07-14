package com.echidna.control.service

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

private val MODULE_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
private val NATIVE_HOOK_ABIS = setOf("arm64-v8a", "x86_64")

/**
 * Reports CPU/ABI compatibility separately from the audio HAL probe.
 *
 * Zygisk chooses a module payload per process ABI. Echidna ships arm64-v8a,
 * armeabi-v7a, and x86_64 payloads, but active inline hooks are intentionally
 * enabled only where the trampoline implementation is supported.
 */
class CpuArchProbe {
    fun probe(): JSONObject {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val primaryAbi = supportedAbis.firstOrNull().orEmpty()
        val zygiskAbi = zygiskAbiFor(primaryAbi)
        val moduleSupported = zygiskAbi in MODULE_ABIS
        val nativeHooksSupported = zygiskAbi in NATIVE_HOOK_ABIS
        return JSONObject()
            .put("primaryAbi", primaryAbi)
            .put("supportedAbis", JSONArray(supportedAbis))
            .put("cpuFamily", cpuFamily(primaryAbi))
            .put("is64Bit", primaryAbi in setOf("arm64-v8a", "x86_64"))
            .put("zygiskAbi", zygiskAbi)
            .put("moduleSupported", moduleSupported)
            .put("nativeHooksSupported", nativeHooksSupported)
            .put("supportLevel", supportLevel(zygiskAbi, moduleSupported, nativeHooksSupported))
            .put("message", supportMessage(zygiskAbi, moduleSupported, nativeHooksSupported))
    }

    private fun zygiskAbiFor(primaryAbi: String): String = when (primaryAbi) {
        "arm64-v8a" -> "arm64-v8a"
        "armeabi-v7a", "armeabi" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> primaryAbi
    }

    private fun cpuFamily(primaryAbi: String): String = when (primaryAbi) {
        "arm64-v8a" -> "AArch64"
        "armeabi-v7a", "armeabi" -> "ARMv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        "" -> "Unknown"
        else -> primaryAbi
    }

    private fun supportLevel(
        zygiskAbi: String,
        moduleSupported: Boolean,
        nativeHooksSupported: Boolean
    ): String = when {
        nativeHooksSupported -> "native_hooks_supported"
        moduleSupported && zygiskAbi == "armeabi-v7a" -> "module_loads_hooks_disabled"
        moduleSupported -> "module_supported_hooks_unverified"
        else -> "unsupported"
    }

    private fun supportMessage(
        zygiskAbi: String,
        moduleSupported: Boolean,
        nativeHooksSupported: Boolean
    ): String = when {
        nativeHooksSupported ->
            "Native inline hooks are implemented for this process ABI."
        moduleSupported && zygiskAbi == "armeabi-v7a" ->
            "The module can load for 32-bit ARM, but audio hooks are disabled fail-closed."
        moduleSupported ->
            "The module package includes this ABI, but active hook support is not proven."
        zygiskAbi.isBlank() ->
            "No process ABI was reported by Android."
        else ->
            "Echidna does not ship a Zygisk payload for this process ABI."
    }
}
