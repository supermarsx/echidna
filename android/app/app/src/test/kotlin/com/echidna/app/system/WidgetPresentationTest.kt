package com.echidna.app.system

import com.echidna.app.model.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the widget engine-status mapping is honest: it mirrors EngineStatusCard's precedence
 * and never fabricates ACTIVE. Pure JVM test (no Android framework).
 */
class WidgetPresentationTest {

    private fun status(
        nativeInstalled: Boolean = true,
        active: Boolean = false,
        bypass: Boolean = false,
        lastError: String? = null
    ) = EngineStatus(
        nativeInstalled = nativeInstalled,
        active = active,
        selinuxMode = "Enforcing",
        lastError = lastError,
        latencyMs = 10,
        xruns = 0,
        bypass = bypass
    )

    @Test
    fun notInstalledWins_evenWhenMasterOn() {
        val s = status(nativeInstalled = false)
        assertEquals(
            WidgetEngineState.NOT_INSTALLED,
            engineWidgetState(s, masterEnabled = true, bypass = false)
        )
    }

    @Test
    fun errorHasHighestPrecedence() {
        val s = status(nativeInstalled = false, lastError = "boom")
        assertEquals(
            WidgetEngineState.ERROR,
            engineWidgetState(s, masterEnabled = true, bypass = false)
        )
    }

    @Test
    fun activeOnlyWhenStatusActive() {
        val s = status(active = true)
        assertEquals(
            WidgetEngineState.ACTIVE,
            engineWidgetState(s, masterEnabled = true, bypass = false)
        )
    }

    @Test
    fun masterOffIsStandby_notActive_notBypassed() {
        // Even if a stale bypass flag is set, master-off resolves to STANDBY first.
        val s = status(active = false, bypass = true)
        assertEquals(
            WidgetEngineState.STANDBY,
            engineWidgetState(s, masterEnabled = false, bypass = true)
        )
    }

    @Test
    fun bypassWhenMasterOnAndNotActive() {
        val s = status(active = false, bypass = true)
        assertEquals(
            WidgetEngineState.BYPASSED,
            engineWidgetState(s, masterEnabled = true, bypass = true)
        )
    }

    @Test
    fun masterOnNoBypassNotActiveIsStandby() {
        val s = status(active = false, bypass = false)
        assertEquals(
            WidgetEngineState.STANDBY,
            engineWidgetState(s, masterEnabled = true, bypass = false)
        )
    }

    @Test
    fun neverFabricatesActiveWhenStatusInactive() {
        val s = status(active = false)
        // No combination of the control flags may yield ACTIVE when status.active is false.
        for (master in listOf(true, false)) {
            for (bypass in listOf(true, false)) {
                val result = engineWidgetState(s, master, bypass)
                assert(result != WidgetEngineState.ACTIVE) {
                    "fabricated ACTIVE for master=$master bypass=$bypass"
                }
            }
        }
    }
}
