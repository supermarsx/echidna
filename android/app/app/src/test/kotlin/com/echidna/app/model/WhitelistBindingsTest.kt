package com.echidna.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain-JVM model test for [WhitelistBindings] (no Android framework classes, so no
 * Robolectric needed). Locks the shape the whitelist editor binds against.
 */
class WhitelistBindingsTest {

    @Test
    fun `exposes whitelist flags and per-app preset bindings`() {
        val bindings = WhitelistBindings(
            whitelist = mapOf("com.foo" to true, "com.bar" to false),
            appBindings = mapOf("com.foo" to "preset-a")
        )
        assertEquals(true, bindings.whitelist["com.foo"])
        assertEquals(false, bindings.whitelist["com.bar"])
        assertEquals("preset-a", bindings.appBindings["com.foo"])
        assertNull(bindings.appBindings["com.bar"])
    }

    @Test
    fun `empty read-back is represented by empty maps`() {
        val empty = WhitelistBindings(emptyMap(), emptyMap())
        assertEquals(0, empty.whitelist.size)
        assertEquals(0, empty.appBindings.size)
    }
}
