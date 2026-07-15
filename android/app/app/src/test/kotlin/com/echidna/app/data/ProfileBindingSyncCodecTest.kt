package com.echidna.app.data

import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ProfileBindingSyncCodecTest {
    @Test
    fun `non-active bound preset is serialized in the same document as its binding`() {
        val active = preset("active")
        val bound = preset("bound")

        val root = JSONObject(ProfileBindingSyncCodec.encode(
            presets = listOf(active, bound),
            defaultProfileId = active.id,
            appBindings = mapOf("com.example.recorder" to bound.id),
            whitelist = mapOf("com.example.recorder" to true),
            captureOwners = mapOf("com.example.recorder" to "zygisk"),
            control = control(),
        ))

        assertEquals(POLICY_SCHEMA_VERSION, root.getInt("schemaVersion"))
        assertEquals("active", root.getString("defaultProfileId"))
        assertEquals("bound", root.getJSONObject("appBindings").getString("com.example.recorder"))
        assertEquals("bound", root.getJSONObject("profiles").getJSONObject("bound").getString("id"))
        assertTrue(root.getJSONObject("whitelist").getBoolean("com.example.recorder"))
        assertEquals("zygisk", root.getJSONObject("captureOwners").getString("com.example.recorder"))
        assertTrue(root.getJSONObject("control").getBoolean("masterEnabled"))
    }

    @Test
    fun `dangling binding cannot be serialized`() {
        val failure = runCatching {
            ProfileBindingSyncCodec.encode(
                presets = listOf(preset("active")),
                defaultProfileId = "active",
                appBindings = mapOf("com.example.recorder" to "deleted"),
                whitelist = emptyMap(),
                captureOwners = emptyMap(),
                control = control(),
            )
        }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `process-specific binding and unknown capture owner are rejected`() {
        val processBinding = runCatching {
            ProfileBindingSyncCodec.encode(
                presets = listOf(preset("active")),
                defaultProfileId = "active",
                appBindings = mapOf("com.example.recorder:worker" to "active"),
                whitelist = mapOf("com.example.recorder:worker" to true),
                captureOwners = mapOf("com.example.recorder:worker" to "other"),
                control = control(),
            )
        }
        assertTrue(processBinding.exceptionOrNull() is IllegalArgumentException)
    }

    private fun control() = PolicyControlState(
        masterEnabled = true,
        bypass = false,
        panicUntilEpochMs = 0L,
        sidetoneEnabled = false,
        sidetoneGainDb = 0f,
        engineMode = "native_first",
    )

    private fun preset(id: String): Preset = Preset(
        id = id,
        name = id,
        description = null,
        tags = emptySet(),
        latencyMode = LatencyMode.LOW_LATENCY,
        dryWet = 50,
        modules = listOf(EffectModule.Mix(true, 50f, 0f)),
    )
}
