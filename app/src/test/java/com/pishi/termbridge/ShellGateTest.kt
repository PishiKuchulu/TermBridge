package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandRunner
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.CommandTemplate.SubmissionBlock
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.PreparedCommand
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.TerminalSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remediation phase 1 regression tests: the shell execution
 * gate. These tests prove the restriction at the domain layer
 * — the layer SessionViewModel.actOnPrepared calls — without
 * any dialog, so they also prove the restriction survives a
 * UI bypass: a PreparedCommand built by any caller is still
 * refused when the shell is unconfirmed or stale.
 *
 * Mode separation (insert sends no Enter, execute sends
 * exactly one) is pinned here for this phase and also
 * covered by CommandRunnerTest.
 */
class ShellGateTest {
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

    private val paramButton = button(
        "echo {{v}}",
        listOf(CommandVariable("v", required = true))
    )
    private val plainButton = button("ls -la")

    @Test
    fun prepareRecordsShellAndConfirmation() {
        val confirmed = CommandTemplate.prepare(
            paramButton, mapOf("v" to "x"), RemoteShell.POWERSHELL, true
        )
        assertEquals(RemoteShell.POWERSHELL, confirmed.shell)
        assertTrue(confirmed.shellConfirmed)

        // Secure default: a caller that forgets the flag gets
        // an unconfirmed prepared command the gate refuses.
        val def = CommandTemplate.prepare(paramButton, mapOf("v" to "x"))
        assertEquals(RemoteShell.POSIX, def.shell)
        assertFalse(def.shellConfirmed)
    }

    @Test
    fun gateAllowsParameterFreeWhenUnconfirmed() {
        val prepared = CommandTemplate.prepare(plainButton, emptyMap())
        assertNull(
            CommandTemplate.blockReason(prepared, RemoteShell.POSIX, false)
        )
        assertNull(
            CommandTemplate.blockReason(prepared, RemoteShell.POWERSHELL, false)
        )
    }

    @Test
    fun gateBlocksParameterizedWhenUnconfirmed() {
        val prepared = CommandTemplate.prepare(
            paramButton, mapOf("v" to "x"), RemoteShell.POSIX, false
        )
        assertEquals(
            SubmissionBlock.SHELL_UNCONFIRMED,
            CommandTemplate.blockReason(prepared, RemoteShell.POSIX, false)
        )
    }

    @Test
    fun gateBlocksWhenPreparedConfirmedButCurrentIsNot() {
        // Preparation-time confirmation alone is not enough:
        // the current session must be confirmed too.
        val prepared = CommandTemplate.prepare(
            paramButton, mapOf("v" to "x"), RemoteShell.POSIX, true
        )
        assertEquals(
            SubmissionBlock.SHELL_UNCONFIRMED,
            CommandTemplate.blockReason(prepared, RemoteShell.POSIX, false)
        )
    }

    @Test
    fun gateBlocksWhenCurrentConfirmedButPreparedWasNot() {
        val prepared = CommandTemplate.prepare(
            paramButton, mapOf("v" to "x"), RemoteShell.POSIX, false
        )
        assertEquals(
            SubmissionBlock.SHELL_UNCONFIRMED,
            CommandTemplate.blockReason(prepared, RemoteShell.POSIX, true)
        )
    }

    @Test
    fun gateAllowsParameterizedWhenBothConfirmedAndMatching() {
        val prepared = CommandTemplate.prepare(
            paramButton, mapOf("v" to "x"), RemoteShell.POWERSHELL, true
        )
        assertNull(
            CommandTemplate.blockReason(prepared, RemoteShell.POWERSHELL, true)
        )
    }

    @Test
    fun gateBlocksStaleShellMismatch() {
        val prepared = CommandTemplate.prepare(
            paramButton, mapOf("v" to "x"), RemoteShell.POSIX, true
        )
        assertEquals(
            SubmissionBlock.STALE_SHELL,
            CommandTemplate.blockReason(prepared, RemoteShell.POWERSHELL, true)
        )
    }

    @Test
    fun gateIgnoresShellMismatchForParameterFree() {
        // No quoting is involved, so the shell is irrelevant.
        val prepared = CommandTemplate.prepare(plainButton, emptyMap())
        assertNull(
            CommandTemplate.blockReason(prepared, RemoteShell.POWERSHELL, true)
        )
    }

    @Test
    fun gateBlocksBypassBuiltPreparedCommand() {
        // Simulate a caller that bypasses the dialog entirely
        // and hands the execution layer a hand-built command.
        val smuggled = PreparedCommand(
            button = paramButton,
            rendered = "echo 'x'",
            shell = RemoteShell.POSIX,
            shellConfirmed = false
        )
        assertEquals(
            SubmissionBlock.SHELL_UNCONFIRMED,
            CommandTemplate.blockReason(smuggled, RemoteShell.POSIX, false)
        )
    }

    @Test
    fun gateBlocksSequenceWhenUnconfirmed() {
        val seq = button(
            "",
            listOf(CommandVariable("v", required = true)),
            CommandMode.SEQUENCE,
            listOf("echo {{v}}", "ls")
        )
        val prepared = CommandTemplate.prepare(
            seq, mapOf("v" to "x"), RemoteShell.POSIX, false
        )
        assertEquals(
            SubmissionBlock.SHELL_UNCONFIRMED,
            CommandTemplate.blockReason(prepared, RemoteShell.POSIX, false)
        )
    }

    @Test
    fun posixQuotingKeepsHostileValuesOneWord() {
        val cases = mapOf(
            "a'b" to "'a'\\''b'",
            "a b" to "'a b'",
            "ünïcode ✓" to "'ünïcode ✓'",
            "" to "''",
            "\$(touch /tmp/pwn)" to "'\$(touch /tmp/pwn)'",
            "`id`" to "'`id`'",
            "x\"; rm -rf /" to "'x\"; rm -rf /'"
        )
        for ((value, quoted) in cases) {
            val prepared = CommandTemplate.prepare(
                paramButton, mapOf("v" to value), RemoteShell.POSIX, true
            )
            assertEquals("echo $quoted", prepared.rendered)
        }
    }

    @Test
    fun powershellQuotingKeepsHostileValuesLiteral() {
        val cases = mapOf(
            "a'b" to "'a''b'",
            "a b" to "'a b'",
            "ünïcode ✓" to "'ünïcode ✓'",
            "" to "''",
            "\$(touch /tmp/pwn)" to "'\$(touch /tmp/pwn)'",
            "`id`" to "'`id`'"
        )
        for ((value, quoted) in cases) {
            val prepared = CommandTemplate.prepare(
                paramButton, mapOf("v" to value), RemoteShell.POWERSHELL, true
            )
            assertEquals("echo $quoted", prepared.rendered)
        }
    }

    @Test
    fun insertAndExecuteRemainDistinct() {
        val texts = mutableListOf<String>()
        var enters = 0
        val sender = object : TerminalSender {
            override fun sendText(text: String) {
                texts += text
            }

            override fun sendEnter() {
                enters++
            }
        }
        val runner = CommandRunner(sender)
        val insertButton = button("echo {{v}}", listOf(CommandVariable("v")),
            CommandMode.INSERT_ONLY)
        val execButton = button("echo {{v}}", listOf(CommandVariable("v")),
            CommandMode.EXECUTE_ONE)
        runner.insert(
            CommandTemplate.prepare(insertButton, mapOf("v" to "x"),
                RemoteShell.POSIX, true)
        )
        assertEquals(listOf("echo 'x'"), texts)
        assertEquals(0, enters)
        runner.executeOne(
            CommandTemplate.prepare(execButton, mapOf("v" to "x"),
                RemoteShell.POSIX, true)
        )
        assertEquals(listOf("echo 'x'", "echo 'x'"), texts)
        assertEquals(1, enters)
    }
}
