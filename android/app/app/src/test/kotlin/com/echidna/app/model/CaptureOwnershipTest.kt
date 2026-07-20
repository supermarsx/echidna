package com.echidna.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the rule that decides whether the LSPosed shim ever does anything: the companion publishes
 * `lsposed` as capture owner in Compatibility mode and `zygisk` in every other mode, and the shim
 * fails closed unless it is named. Diagnostics and the policy encoder both read this, so a drift
 * here would silently disable the shim while the UI claimed otherwise.
 */
class CaptureOwnershipTest {

    @Test
    fun `only compatibility mode assigns the lsposed shim as capture owner`() {
        assertEquals(CaptureOwner.LSPOSED, CaptureOwnership.ownerFor(DspEngineMode.COMPATIBILITY))
        assertEquals(CaptureOwner.ZYGISK, CaptureOwnership.ownerFor(DspEngineMode.NATIVE_FIRST))
        assertEquals(CaptureOwner.ZYGISK, CaptureOwnership.ownerFor(DspEngineMode.LOW_LATENCY))
    }

    @Test
    fun `owner ids match the wire values the shim compares against`() {
        assertEquals("lsposed", CaptureOwner.LSPOSED.id)
        assertEquals("zygisk", CaptureOwner.ZYGISK.id)
        assertEquals("", CaptureOwner.NONE.id)
    }

    @Test
    fun `the default engine mode leaves the shim without ownership`() {
        // NATIVE_FIRST is the shipped default, so an untouched install is exactly the reported bug:
        // the shim installs, LSPosed says it is active, and policy hands capture to Zygisk.
        val status = resolve(engineMode = SettingsState().engineMode)

        assertEquals(CaptureOwner.ZYGISK, status.owner)
        assertEquals(CaptureOwnerReason.ACTIVE, status.reason)
    }

    @Test
    fun `compatibility mode with a live policy hands capture to the shim`() {
        val status = resolve(engineMode = DspEngineMode.COMPATIBILITY)

        assertEquals(CaptureOwner.LSPOSED, status.owner)
        assertEquals(CaptureOwnerReason.ACTIVE, status.reason)
        assertEquals("LSPosed shim (Java AudioRecord)", status.summary)
    }

    @Test
    fun `unpublished policy reports no owner`() {
        val status = resolve(policyPublished = false)

        assertEquals(CaptureOwner.NONE, status.owner)
        assertEquals(CaptureOwnerReason.POLICY_NOT_PUBLISHED, status.reason)
        assertEquals("none", status.summary)
    }

    @Test
    fun `master off bypass and an active panic hold each report the engine disabled`() {
        assertEquals(
            CaptureOwnerReason.ENGINE_DISABLED,
            resolve(masterEnabled = false).reason
        )
        assertEquals(
            CaptureOwnerReason.ENGINE_DISABLED,
            resolve(bypass = true).reason
        )
        assertEquals(
            CaptureOwnerReason.ENGINE_DISABLED,
            resolve(panicUntilEpochMs = 2_000L, nowEpochMs = 1_000L).reason
        )
    }

    @Test
    fun `an expired panic hold no longer blocks ownership`() {
        val status = resolve(panicUntilEpochMs = 1_000L, nowEpochMs = 2_000L)

        assertEquals(CaptureOwnerReason.ACTIVE, status.reason)
        assertEquals(CaptureOwner.ZYGISK, status.owner)
    }

    @Test
    fun `an empty whitelist reports that ownership is assigned to nothing`() {
        val status = resolve(enabledWhitelistCount = 0)

        assertEquals(CaptureOwner.NONE, status.owner)
        assertEquals(CaptureOwnerReason.NO_WHITELISTED_APPS, status.reason)
    }

    private fun resolve(
        engineMode: DspEngineMode = DspEngineMode.NATIVE_FIRST,
        masterEnabled: Boolean = true,
        bypass: Boolean = false,
        panicUntilEpochMs: Long = 0L,
        nowEpochMs: Long = 1_000L,
        enabledWhitelistCount: Int = 1,
        policyPublished: Boolean = true,
    ): CaptureOwnerStatus = CaptureOwnership.resolve(
        engineMode = engineMode,
        masterEnabled = masterEnabled,
        bypass = bypass,
        panicUntilEpochMs = panicUntilEpochMs,
        nowEpochMs = nowEpochMs,
        enabledWhitelistCount = enabledWhitelistCount,
        policyPublished = policyPublished,
    )
}
