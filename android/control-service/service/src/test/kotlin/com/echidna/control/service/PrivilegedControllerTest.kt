package com.echidna.control.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedControllerTest {
    @Test
    fun `install never widens live zygote policy`() {
        val runner = RecordingInstallRunner()
        val controller = PrivilegedController(
            runner,
            SelinuxStateProbe {
                SelinuxAssessment(SelinuxState.ENFORCING, policyToolAvailable = true)
            },
        )

        controller.installModule("/data/local/tmp/echidna.zip")

        val commandText = runner.commands.flatten().joinToString(" ")
        assertFalse(commandText.contains("magiskpolicy"))
        assertFalse(commandText.contains("dyntransition"))
        assertFalse(commandText.contains("allow zygote"))
        val status = controller.lastKnownStatus()
        assertTrue(status.policyToolAvailable)
        assertFalse(status.policyAppliedVerified)
        assertFalse(status.nativeRouteVerified)
    }

    @Test
    fun `reports zygisk enabled from Magisk settings`() {
        val runner = FakeCommandRunner(
            magiskSqlite = CommandResult(true, "value=1", ""),
        )
        val status = PrivilegedController(runner, SelinuxCompatChecker(runner)).refreshStatus()

        assertTrue(status.magiskModuleInstalled)
        assertTrue(status.zygiskEnabled)
    }

    @Test
    fun `disableModule writes the Magisk disable marker and reports success`() {
        val runner = DisableRunner(disableSucceeds = true)
        val controller = PrivilegedController(runner, SelinuxCompatChecker(runner))

        val disabled = controller.disableModule()

        assertTrue(disabled)
        val command = runner.commands.joinToString(" ")
        // The disable step touches the Magisk disable marker so Zygisk stops loading the module.
        assertTrue(command.contains("touch"))
        assertTrue(command.contains("/data/adb/modules/echidna/disable"))
    }

    @Test
    fun `disableModule reports failure so the caller can abort honestly`() {
        val runner = DisableRunner(disableSucceeds = false)
        val controller = PrivilegedController(runner, SelinuxCompatChecker(runner))

        assertFalse(controller.disableModule())
    }

    @Test
    fun `refreshStatus through the cached gate probes su once and degrades when root is denied`() {
        // Regression (t17): the whole privileged detection burst must cost a single root probe. When
        // root is denied the gate short-circuits every command, so nothing else spawns su and the
        // status degrades honestly to "not installed".
        val counting = SuCountingRunner(rootGranted = false)
        val runner = CachedRootCommandRunner(counting)
        val status = PrivilegedController(runner, SelinuxCompatChecker(runner)).refreshStatus()

        assertEquals(1, counting.idProbes)
        assertEquals(0, counting.otherCommands)
        assertFalse(status.magiskModuleInstalled)
        assertFalse(status.zygiskEnabled)
    }

    @Test
    fun `reports zygisk enabled from standalone Zygisk Next module`() {
        val runner = FakeCommandRunner(
            magiskSqlite = CommandResult(true, "value=0", ""),
            standaloneZygisk = CommandResult(true, "", ""),
        )
        val status = PrivilegedController(runner, SelinuxCompatChecker(runner)).refreshStatus()

        assertTrue(status.magiskModuleInstalled)
        assertTrue(status.zygiskEnabled)
    }
}

private class SuCountingRunner(private val rootGranted: Boolean) : PrivilegedCommandRunner {
    @Volatile var idProbes = 0
    @Volatile var otherCommands = 0

    override fun runCommand(command: String): CommandResult = runCommand(command.split(" "))

    override fun runCommand(arguments: List<String>): CommandResult {
        return if (arguments == listOf("id")) {
            idProbes += 1
            CommandResult(rootGranted, if (rootGranted) "uid=0(root)" else "", "", if (rootGranted) 0 else 1)
        } else {
            otherCommands += 1
            CommandResult(false, "", "", 1)
        }
    }
}

private class DisableRunner(
    private val disableSucceeds: Boolean,
) : PrivilegedCommandRunner {
    val commands = mutableListOf<String>()

    override fun runCommand(command: String): CommandResult {
        commands += command
        return CommandResult(false, "", "", 1)
    }

    override fun runCommand(arguments: List<String>): CommandResult {
        val joined = arguments.joinToString(" ")
        commands += joined
        return when {
            arguments.firstOrNull() == "sh" && joined.contains("/disable") ->
                CommandResult(disableSucceeds, "", if (disableSucceeds) "" else "touch: permission denied", if (disableSucceeds) 0 else 1)
            else -> CommandResult(false, "", "", 1)
        }
    }
}

private class RecordingInstallRunner : PrivilegedCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun runCommand(command: String): CommandResult {
        commands += listOf(command)
        return CommandResult(false, "", "", 1)
    }

    override fun runCommand(arguments: List<String>): CommandResult {
        commands += arguments
        return when {
            arguments.take(2) == listOf("magisk", "--install-module") ->
                CommandResult(true, "", "")
            arguments == listOf("test", "-f", "/data/adb/modules/echidna/module.prop") ->
                CommandResult(true, "", "")
            arguments.take(2) == listOf("magisk", "--sqlite") ->
                CommandResult(true, "value=1", "")
            else -> CommandResult(false, "", "", 1)
        }
    }
}

private class FakeCommandRunner(
    private val magiskSqlite: CommandResult = CommandResult(false, "", "magisk unavailable", 127),
    private val standaloneZygisk: CommandResult = CommandResult(false, "", "", 1),
) : PrivilegedCommandRunner {
    override fun runCommand(command: String): CommandResult {
        return when {
            command.contains("/data/adb/modules/*/module.prop") -> standaloneZygisk
            else -> CommandResult(false, "", "unexpected command: $command", 127)
        }
    }

    override fun runCommand(arguments: List<String>): CommandResult {
        return when {
            arguments == listOf("test", "-f", "/data/adb/modules/echidna/module.prop") ->
                CommandResult(true, "", "")
            arguments.take(2) == listOf("magisk", "--sqlite") -> magiskSqlite
            arguments == listOf("getenforce") -> CommandResult(true, "Disabled", "")
            arguments == listOf("id") -> CommandResult(true, "uid=0(root)", "")
            arguments.firstOrNull() == "test" -> CommandResult(false, "", "", 1)
            arguments.firstOrNull() == "magiskpolicy" -> CommandResult(false, "", "", 1)
            else -> CommandResult(false, "", "unexpected arguments: $arguments", 127)
        }
    }
}
