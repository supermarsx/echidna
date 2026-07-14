package com.echidna.control.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandExecutorTest {
    @Test
    fun emptyArgumentListReturnsFailureInsteadOfThrowing() {
        val result = RootCommandExecutor(suBinary = "unused").runCommand(emptyList())

        assertFalse(result.success)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("empty"))
    }

    @Test
    fun blankCommandReturnsFailureInsteadOfLaunchingShell() {
        val result = RootCommandExecutor(suBinary = "unused").runCommand("   ")

        assertFalse(result.success)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("blank"))
    }
}
