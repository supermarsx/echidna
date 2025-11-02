package com.echidna.control.service

import org.json.JSONObject

/**
 * Describes the current state of the native Magisk/Zygisk integration.
 */
data class ModuleStatus(
    val magiskModuleInstalled: Boolean,
    val zygiskEnabled: Boolean,
    val selinuxState: SelinuxState,
    val javaFallbackActive: Boolean,
    val lastError: String? = null,
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("magiskModuleInstalled", magiskModuleInstalled)
        json.put("zygiskEnabled", zygiskEnabled)
        json.put("selinuxState", selinuxState.name)
        json.put("javaFallbackActive", javaFallbackActive)
        if (!lastError.isNullOrEmpty()) {
            json.put("lastError", lastError)
        }
        return json.toString()
    }
}

/**
 * Represents the outcome of SELinux capability probing.
 */
enum class SelinuxState {
    DISABLED,
    PERMISSIVE,
    ENFORCING_WITH_POLICY,
    ENFORCING_JAVA_ONLY,
}
