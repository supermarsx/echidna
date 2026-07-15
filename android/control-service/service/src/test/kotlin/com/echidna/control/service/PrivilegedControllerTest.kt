package com.echidna.control.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedControllerTest {
    @Test
    fun `install never widens live zygote policy`() {
        val runner = RecordingInstallRunner()
        val controller = PrivilegedController(
            runner,
            SelinuxStateProbe { SelinuxState.ENFORCING_WITH_POLICY },
        )

        controller.installModule("/data/local/tmp/echidna.zip")

        val commandText = runner.commands.flatten().joinToString(" ")
        assertFalse(commandText.contains("magiskpolicy"))
        assertFalse(commandText.contains("dyntransition"))
        assertFalse(commandText.contains("allow zygote"))
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
