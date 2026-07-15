package com.echidna.control.service

import org.json.JSONObject

/**
 * Describes the current state of the native Magisk/Zygisk integration.
 */
data class ModuleStatus(
    val magiskModuleInstalled: Boolean,
    val zygiskEnabled: Boolean,
    val selinuxState: SelinuxState,
    val policyToolAvailable: Boolean,
    val policyAppliedVerified: Boolean,
    val nativeRouteVerified: Boolean,
    val javaFallbackRecommended: Boolean,
    val lastError: String? = null,
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("magiskModuleInstalled", magiskModuleInstalled)
        json.put("zygiskEnabled", zygiskEnabled)
        json.put("selinuxState", selinuxState.name)
        json.put("policyToolAvailable", policyToolAvailable)
        json.put("policyAppliedVerified", policyAppliedVerified)
        json.put("nativeRouteVerified", nativeRouteVerified)
        json.put("javaFallbackRecommended", javaFallbackRecommended)
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
    ENFORCING,
}

data class SelinuxAssessment(
    val state: SelinuxState,
    val policyToolAvailable: Boolean,
)
