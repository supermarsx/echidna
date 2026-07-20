package com.echidna.control.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for [ModuleStatus]. The companion app parses this payload to decide whether the
 * engine is installed and whether the native capture route is proven, so every flag must survive
 * serialisation independently and an absent error must not be reported as an empty one.
 */
class ModuleStatusTest {

    private fun status(
        magiskModuleInstalled: Boolean = false,
        zygiskEnabled: Boolean = false,
        selinuxState: SelinuxState = SelinuxState.ENFORCING,
        policyToolAvailable: Boolean = false,
        policyAppliedVerified: Boolean = false,
        nativeRouteVerified: Boolean = false,
        javaFallbackRecommended: Boolean = false,
        lastError: String? = null,
    ) = ModuleStatus(
        magiskModuleInstalled = magiskModuleInstalled,
        zygiskEnabled = zygiskEnabled,
        selinuxState = selinuxState,
        policyToolAvailable = policyToolAvailable,
        policyAppliedVerified = policyAppliedVerified,
        nativeRouteVerified = nativeRouteVerified,
        javaFallbackRecommended = javaFallbackRecommended,
        lastError = lastError,
    )

    @Test
    fun `a fully negative status serialises every flag as false`() {
        val json = JSONObject(status().toJson())

        assertFalse(json.getBoolean("magiskModuleInstalled"))
        assertFalse(json.getBoolean("zygiskEnabled"))
        assertFalse(json.getBoolean("policyToolAvailable"))
        assertFalse(json.getBoolean("policyAppliedVerified"))
        assertFalse(json.getBoolean("nativeRouteVerified"))
        assertFalse(json.getBoolean("javaFallbackRecommended"))
    }

    @Test
    fun `each boolean flag is carried on its own wire key`() {
        // Flip exactly one flag at a time: a swapped/duplicated key would make two of these fail.
        assertTrue(JSONObject(status(magiskModuleInstalled = true).toJson()).getBoolean("magiskModuleInstalled"))
        assertFalse(JSONObject(status(magiskModuleInstalled = true).toJson()).getBoolean("zygiskEnabled"))

        assertTrue(JSONObject(status(zygiskEnabled = true).toJson()).getBoolean("zygiskEnabled"))
        assertTrue(JSONObject(status(policyToolAvailable = true).toJson()).getBoolean("policyToolAvailable"))
        assertTrue(JSONObject(status(policyAppliedVerified = true).toJson()).getBoolean("policyAppliedVerified"))
        assertTrue(JSONObject(status(nativeRouteVerified = true).toJson()).getBoolean("nativeRouteVerified"))
        assertFalse(JSONObject(status(nativeRouteVerified = true).toJson()).getBoolean("policyAppliedVerified"))
        assertTrue(JSONObject(status(javaFallbackRecommended = true).toJson()).getBoolean("javaFallbackRecommended"))
    }

    @Test
    fun `selinux state travels as its enum name`() {
        SelinuxState.values().forEach { state ->
            assertEquals(
                state.name,
                JSONObject(status(selinuxState = state).toJson()).getString("selinuxState"),
            )
        }
    }

    @Test
    fun `lastError is omitted when there is no error and when it is blank`() {
        assertFalse(
            "a null error must not appear as a key",
            JSONObject(status(lastError = null).toJson()).has("lastError"),
        )
        assertFalse(
            "an empty error must not appear as a key",
            JSONObject(status(lastError = "").toJson()).has("lastError"),
        )
    }

    @Test
    fun `a real error is carried verbatim`() {
        val message = "magisk --install-module exited 1: no such file"
        assertEquals(
            message,
            JSONObject(status(lastError = message).toJson()).getString("lastError"),
        )
    }
}
