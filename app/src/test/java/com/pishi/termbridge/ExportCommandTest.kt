package com.pishi.termbridge

import com.pishi.termbridge.domain.ExportCommand
import com.pishi.termbridge.domain.RemoteShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item F-02: shell-export fallback rendering + planning. */
class ExportCommandTest {

    @Test
    fun rendersPerShellDialect() {
        assertEquals(
            "export EDITOR='vim'",
            ExportCommand.render("EDITOR", "vim", RemoteShell.POSIX)
        )
        assertEquals(
            "\$env:EDITOR = 'vim'",
            ExportCommand.render("EDITOR", "vim", RemoteShell.POWERSHELL)
        )
        assertEquals(
            "set \"EDITOR=vim\"",
            ExportCommand.render("EDITOR", "vim", RemoteShell.CMD)
        )
        // A value with spaces stays one quoted argument.
        assertEquals(
            "export GREETING='hello world'",
            ExportCommand.render("GREETING", "hello world", RemoteShell.POSIX)
        )
    }

    @Test
    fun refusesControlCharactersInEveryShell() {
        val esc = 27.toChar()
        for (shell in RemoteShell.entries) {
            assertNull(ExportCommand.render("A", "one\ntwo", shell))
            assertNull(ExportCommand.render("A", "bad${esc}value", shell))
        }
    }

    @Test
    fun refusesBadNamesAndCmdUnsafeValues() {
        assertNull(ExportCommand.render("1BAD", "x", RemoteShell.POSIX))
        assertNull(ExportCommand.render("A B", "x", RemoteShell.POSIX))
        assertNull(ExportCommand.render("A", "100%", RemoteShell.CMD))
        assertNull(ExportCommand.render("A", "say \"hi\"", RemoteShell.CMD))
        // The same values are fine for POSIX (quoted).
        assertTrue(
            ExportCommand.render("A", "100%", RemoteShell.POSIX) != null
        )
    }

    @Test
    fun planCoversOnlyRefusedNamesWithSafeLines() {
        val plan = ExportCommand.planFallback(
            values = mapOf(
                "OK" to "1",
                "BAD" to "one\ntwo",
                "UNREFUSED" to "9"
            ),
            rejectedNames = listOf("OK", "BAD", "MISSING"),
            shell = RemoteShell.POSIX
        )
        assertEquals(listOf("OK"), plan.lines.map { it.first })
        assertEquals("export OK='1'", plan.lines[0].second)
        // BAD cannot be rendered safely; MISSING has no value.
        assertEquals(listOf("BAD", "MISSING"), plan.skippedNames)
    }
}
