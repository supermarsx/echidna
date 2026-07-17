package com.echidna.control.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the first-run su grant loop (t17). The cached gate must request root at most
 * once and, on denial, never spawn `su` again — so the ~2s status poll and the privileged detection
 * burst can no longer re-prompt on every cycle.
 */
class CachedRootCommandRunnerTest {
    @Test
    fun `probes root once then reuses the grant across a burst of commands`() {
        val delegate = CountingRunner(rootGranted = true)
        val runner = CachedRootCommandRunner(delegate)

        // A privileged burst like PrivilegedController.refreshStatus() issues many commands.
        repeat(8) { runner.runCommand(listOf("test", "-f", "/data/adb/modules/echidna/module.prop")) }

        // Exactly one `id` root probe regardless of how many commands ran.
        assertEquals(1, delegate.idProbes)
    }

    @Test
    fun `denied root is remembered and never re-prompts`() {
        val delegate = CountingRunner(rootGranted = false)
        val runner = CachedRootCommandRunner(delegate)

        val results = (1..20).map { runner.runCommand(listOf("magisk", "--sqlite", "x")) }

        // The gate probed `su -c id` exactly once; the denial short-circuits every later command
        // without spawning su, and no non-probe command ever reached the delegate.
        assertEquals(1, delegate.idProbes)
        assertEquals(0, delegate.nonProbeCommands)
        assertTrue(results.all { !it.success })
        assertEquals("root access unavailable", results.first().stderr)
    }

    @Test
    fun `passes real commands through once root is granted`() {
        val delegate = CountingRunner(rootGranted = true)
        val runner = CachedRootCommandRunner(delegate)

        val result = runner.runCommand(listOf("getenforce"))

        assertTrue(result.success)
        assertEquals(1, delegate.idProbes)
        assertEquals(1, delegate.nonProbeCommands)
    }

    @Test
    fun `concurrent first calls probe root only once`() {
        val delegate = CountingRunner(rootGranted = true)
        val runner = CachedRootCommandRunner(delegate)

        val threads = (1..16).map {
            Thread { runner.runCommand(listOf("test", "-f", "/data/adb/modules/echidna/module.prop")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, delegate.idProbes)
    }

    private class CountingRunner(private val rootGranted: Boolean) : PrivilegedCommandRunner {
        @Volatile var idProbes = 0
        @Volatile var nonProbeCommands = 0

        @Synchronized
        override fun runCommand(arguments: List<String>): CommandResult {
            return if (arguments == listOf("id")) {
                idProbes += 1
                CommandResult(rootGranted, if (rootGranted) "uid=0(root)" else "", "", if (rootGranted) 0 else 1)
            } else {
                nonProbeCommands += 1
                CommandResult(true, "", "")
            }
        }

        override fun runCommand(command: String): CommandResult =
            runCommand(command.split(" "))
    }

    @Test
    fun `blank passthrough still respects the gate`() {
        val delegate = CountingRunner(rootGranted = false)
        val runner = CachedRootCommandRunner(delegate)

        val result = runner.runCommand("whoami")

        assertFalse(result.success)
        assertEquals(0, delegate.nonProbeCommands)
    }
}
