package com.echidna.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The alert -> Settings hand-off must fire once, so a later manual visit is not hijacked. */
class SettingsFocusRequestTest {

    @Before
    fun drain() {
        SettingsFocusRequest.consume()
    }

    @Test
    fun `no request consumes to null`() {
        assertNull(SettingsFocusRequest.consume())
    }

    @Test
    fun `a request is delivered exactly once`() {
        SettingsFocusRequest.request(SettingsFocus.ENGINE)

        assertEquals(SettingsFocus.ENGINE, SettingsFocusRequest.consume())
        assertNull(SettingsFocusRequest.consume())
    }
}
