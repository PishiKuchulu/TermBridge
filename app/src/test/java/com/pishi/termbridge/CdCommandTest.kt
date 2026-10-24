package com.pishi.termbridge

import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.RemoteShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Prompt 15 gap closure: CdCommand (prompt 09) had no test.
 * The `cd` is the single documented exception to "never prepend
 * shell text" — it runs once per shell, visibly — so its exact
 * rendering per shell dialect is pinned here. In particular: no
 * `&&` chaining (a failed cd must never silently change where
 * later commands run), and cmd.exe gets `cd /d` with double
 * quotes because cmd has no single-quote literal.
 */
class CdCommandTest {
    @Test
    fun posixRendersSingleQuotedCd() {
        assertEquals("cd '/srv/app'", CdCommand.render("/srv/app", RemoteShell.POSIX))
    }

    @Test
    fun posixEscapesEmbeddedSingleQuote() {
        // shellQuote: ' -> '\''
        assertEquals(
            "cd '/srv/o'\\''brien'",
            CdCommand.render("/srv/o'brien", RemoteShell.POSIX)
        )
    }

    @Test
    fun powershellMatchesPosixQuoting() {
        assertEquals("cd '/srv/my dir'", CdCommand.render("/srv/my dir", RemoteShell.POWERSHELL))
    }

    @Test
    fun cmdRendersCdSlashDWithDoubleQuotes() {
        assertEquals(
            "cd /d \"C:\\Users\\name\\My Dir\"",
            CdCommand.render("C:\\Users\\name\\My Dir", RemoteShell.CMD)
        )
    }

    @Test
    fun noDialectChainsCommands() {
        for (shell in RemoteShell.values()) {
            assertFalse(
                "cd must never chain with && for $shell",
                CdCommand.render("/srv/app", shell).contains("&&")
            )
        }
    }
}
