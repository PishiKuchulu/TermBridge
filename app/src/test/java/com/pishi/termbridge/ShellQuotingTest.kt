package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.RiskyCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Security fix 16 (H1): quoting and risky classification are
 * PER SHELL. The audit proved the old code quoted POSIX-style
 * for every host, including cmd.exe (where single quotes have
 * no quoting meaning) and PowerShell (where the POSIX '\''
 * escape is not valid quoting). These tests pin the fixed
 * behaviour per shell. NOT RUN (sandbox Gradle limits).
 */
class ShellQuotingTest {
    private fun button(
        template: String,
        variables: List<CommandVariable> = emptyList(),
        mode: CommandMode = CommandMode.EXECUTE_ONE,
        steps: List<String> = emptyList()
    ) = CommandButton(
        hostId = 1L,
        label = "test",
        mode = mode,
        template = template,
        steps = steps,
        variables = variables
    )

    @Test
    fun posixQuotingUnchanged() {
        assertEquals(
            "'a'\\''b'",
            CommandTemplate.shellQuote("a'b", RemoteShell.POSIX)
        )
    }

    @Test
    fun powershellDoublesSingleQuotes() {
        // PowerShell single-quoted literal rule: '' doubling.
        assertEquals(
            "'o''brien'",
            CommandTemplate.shellQuote("o'brien", RemoteShell.POWERSHELL)
        )
    }

    @Test
    fun powershellMetacharValueBecomesOneWord() {
        val prepared = CommandTemplate.prepare(
            button("run {{x}}", listOf(CommandVariable("x", required = true))),
            mapOf("x" to "a; rm -rf /"),
            RemoteShell.POWERSHELL
        )
        assertEquals("run 'a; rm -rf /'", prepared.rendered)
    }

    @Test
    fun cmdPrepareWithParametersThrows() {
        try {
            CommandTemplate.prepare(
                button("run {{x}}", listOf(CommandVariable("x", required = true))),
                mapOf("x" to "a & del /q file"),
                RemoteShell.CMD
            )
            fail("cmd.exe parameterised prepare must throw, not POSIX-quote")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cmd.exe"))
        }
    }

    @Test
    fun cmdPrepareWithoutParametersStillWorks() {
        // No placeholder -> no quoting is involved, so the
        // author text prepares exactly on every shell.
        val prepared = CommandTemplate.prepare(
            button("dir C:\\temp"),
            emptyMap(),
            RemoteShell.CMD
        )
        assertEquals("dir C:\\temp", prepared.rendered)
    }

    @Test
    fun cmdSequenceWithVariableStepThrows() {
        try {
            CommandTemplate.prepare(
                button(
                    "",
                    listOf(CommandVariable("x", required = true)),
                    mode = CommandMode.SEQUENCE,
                    steps = listOf("echo {{x}}")
                ),
                mapOf("x" to "hi"),
                RemoteShell.CMD
            )
            fail("cmd.exe parameterised sequence must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cmd.exe"))
        }
    }

    @Test
    fun windowsDangerousCommandsFlaggedOnWindowsShells() {
        val cases = listOf(
            "del /f /s C:\\temp\\*" to RemoteShell.CMD,
            "Remove-Item -Recurse C:\\old" to RemoteShell.POWERSHELL,
            "format D:" to RemoteShell.CMD,
            "Stop-Computer" to RemoteShell.POWERSHELL,
            "reg delete HKCU\\Software\\X /f" to RemoteShell.CMD,
            "vssadmin delete shadows /all" to RemoteShell.CMD
        )
        for ((command, shell) in cases) {
            assertTrue(
                "$command on $shell must be risky",
                RiskyCommand.classify(command, shell).isRisky
            )
        }
    }

    @Test
    fun powershellRmAliasStillFlagged() {
        // PowerShell aliases rm to Remove-Item, so the POSIX
        // rm pattern still bites there.
        assertTrue(RiskyCommand.classify("rm -rf /", RemoteShell.POWERSHELL).isRisky)
    }

    @Test
    fun inertCommandsNotFlaggedOnForeignShell() {
        // rm means nothing to cmd.exe; del means nothing to
        // POSIX sh. Shell-aware classification does not cry
        // wolf for inert commands.
        assertFalse(RiskyCommand.classify("rm -rf /", RemoteShell.CMD).isRisky)
        assertFalse(RiskyCommand.classify("del /f file.txt", RemoteShell.POSIX).isRisky)
    }

    @Test
    fun posixRiskyBehaviourUnchanged() {
        assertTrue(RiskyCommand.classify("rm -rf /", RemoteShell.POSIX).isRisky)
        assertTrue(RiskyCommand.classify("sudo reboot", RemoteShell.POSIX).isRisky)
        assertFalse(RiskyCommand.classify("ls -la", RemoteShell.POSIX).isRisky)
    }

    @Test
    fun cdCommandPowershellDoublesQuotes() {
        // Same quoting rule as buttons: a PowerShell path with
        // a quote doubles it instead of POSIX-escaping it.
        assertEquals(
            "cd '/srv/o''brien'",
            com.pishi.termbridge.domain.CdCommand.render(
                "/srv/o'brien", RemoteShell.POWERSHELL
            )
        )
    }
}
