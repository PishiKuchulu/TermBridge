package com.pishi.termbridge

import com.pishi.termbridge.domain.Autocomplete
import com.pishi.termbridge.domain.CommandAdmission
import com.pishi.termbridge.domain.HistoryEntry
import com.pishi.termbridge.domain.InputLineTracker
import com.pishi.termbridge.domain.ShellHistoryImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-11: admission, suggestions, tracker, history parsing. */
class CommandHistoryTest {

    @Test
    fun admissionAcceptsOrdinaryCommands() {
        assertEquals("ls -la /var/log", CommandAdmission.admit("  ls -la /var/log  "))
        assertEquals("git status", CommandAdmission.admit("git status"))
    }

    @Test
    fun admissionDropsSecretsControlsAndTrivia() {
        assertNull(CommandAdmission.admit("x"))
        assertNull(CommandAdmission.admit(""))
        val withEsc = "ls " + 27.toChar() + "[31m"
        assertNull(CommandAdmission.admit(withEsc))
        // SecretScanner-flagged shapes never enter the store.
        assertNull(CommandAdmission.admit("export " + "TOKEN" + "=abc123"))
    }

    @Test
    fun admissionDropsScannerFlaggedLines() {
        assertNull(CommandAdmission.admit("curl -H 'Authorization: Bearer abc.def' x"))
        assertTrue(CommandAdmission.admit("curl https://example.com") != null)
    }

    @Test
    fun suggestionsRankByUsesThenRecency() {
        val entries = listOf(
            HistoryEntry("git status", uses = 5, lastUsedMs = 100),
            HistoryEntry("git stash", uses = 9, lastUsedMs = 50),
            HistoryEntry("git switch main", uses = 9, lastUsedMs = 90),
            HistoryEntry("ls -la", uses = 99, lastUsedMs = 999)
        )
        assertEquals(
            listOf("git switch main", "git stash", "git status"),
            Autocomplete.suggestions("git ", entries)
        )
        // The exact line itself is not a suggestion.
        assertEquals(
            emptyList<String>(),
            Autocomplete.suggestions("ls -la", entries)
        )
        // Too-short prefixes suggest nothing.
        assertEquals(emptyList<String>(), Autocomplete.suggestions("g", entries))
    }

    @Test
    fun trackerComposesBackspacesAndSubmits() {
        val tracker = InputLineTracker()
        tracker.type("git st")
        tracker.backspace()
        tracker.type("tatus")
        assertEquals("git status", tracker.currentLine)
        assertEquals("git status", tracker.submit())
        assertEquals("", tracker.currentLine)
        tracker.type("ls")
        tracker.reset()
        assertEquals("", tracker.currentLine)
    }

    @Test
    fun historyImportHandlesBashZshAndFish() {
        val raw = listOf(
            "ls -la",
            ": 1700000000:0;git status",
            "- cmd: docker ps",
            "  when: 1700000001",
            "export " + "API" + "_KEY=zzz999",
            "git status"
        ).joinToString("\n")
        val parsed = ShellHistoryImport.parse(raw)
        // Secret line dropped; duplicate git lines collapse to
        // one (last position); formats unwrapped.
        assertEquals(listOf("ls -la", "docker ps", "git status"), parsed)
    }
}
