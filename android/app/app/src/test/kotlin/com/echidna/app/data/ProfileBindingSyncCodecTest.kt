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
            listOf(active, bound),
            mapOf("com.example.recorder" to bound.id),
        ))

        assertEquals("bound", root.getJSONObject("appBindings").getString("com.example.recorder"))
        assertEquals("bound", root.getJSONObject("profiles").getJSONObject("bound").getString("id"))
    }

    @Test
    fun `dangling binding cannot be serialized`() {
        val failure = runCatching {
            ProfileBindingSyncCodec.encode(
                listOf(preset("active")),
                mapOf("com.example.recorder" to "deleted"),
            )
        }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

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
