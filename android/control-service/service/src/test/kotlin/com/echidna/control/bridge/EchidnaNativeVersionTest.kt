package com.echidna.control.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchidnaNativeVersionTest {
    @Test
    fun `expected public API is version 1 2 0`() {
        assertEquals(0x0001_0200L, EchidnaNative.EXPECTED_API_VERSION)
    }

    @Test
    fun `same major and minor accepts compatible patch updates`() {
        assertTrue(EchidnaNative.isSupportedApiVersion(0x0001_0200L))
        assertTrue(EchidnaNative.isSupportedApiVersion(0x0001_02FFL))
    }

    @Test
    fun `unavailable and incompatible major or minor fail closed`() {
        assertFalse(EchidnaNative.isSupportedApiVersion(EchidnaNative.API_VERSION_UNAVAILABLE))
        assertFalse(EchidnaNative.isSupportedApiVersion(0x0001_0100L))
        assertFalse(EchidnaNative.isSupportedApiVersion(0x0002_0200L))
    }

    @Test
    fun `missing JNI library reports unavailable instead of expected version`() {
        assertEquals(EchidnaNative.API_VERSION_UNAVAILABLE, EchidnaNative.getApiVersion())
    }
}
