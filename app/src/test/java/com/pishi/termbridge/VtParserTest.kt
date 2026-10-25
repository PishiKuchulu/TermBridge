package com.pishi.termbridge

import com.pishi.termbridge.terminal.VtParser
import com.pishi.termbridge.terminal.asText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VtParserTest {
    @Test
    fun printableTextAndNewline() {
        val parser = VtParser(80, 24)
        parser.feed("hello\r\nworld")
        val snapshot = parser.snapshot()
        assertEquals("hello", snapshot.lines[snapshot.scrollbackCount].asText())
        assertEquals("world", snapshot.lines[snapshot.scrollbackCount + 1].asText())
    }

    @Test
    fun backspaceAndTab() {
        val parser = VtParser(80, 24)
        parser.feed("abc\bX")
        assertEquals("abX", parser.snapshot().lines[0].asText())
        val tabParser = VtParser(80, 24)
        tabParser.feed("a\tb")
        val line = tabParser.snapshot().lines[0]
        // 'a' at 0, tab to 8, 'b' at 8.
        assertEquals('b', line[8].char)
    }

    @Test
    fun wrapAndScrollbackCap() {
        val parser = VtParser(10, 2, maxScrollback = 3)
        parser.feed("0123456789ABCDEFGHIJ0123456789ABCDEFGHIJ")
        val snapshot = parser.snapshot()
        assertTrue(snapshot.scrollbackCount <= 3)
        assertEquals(snapshot.scrollbackCount + 2, snapshot.lines.size)
    }

    @Test
    fun csiCursorAndErase() {
        val parser = VtParser(80, 24)
        parser.feed("hello\u001B[2DXY")
        assertEquals("helXY", parser.snapshot().lines[0].asText())
        parser.feed("\u001B[2K")
        assertEquals("", parser.snapshot().lines[0].asText())
        parser.feed("abc\u001B[1;3H*")
        val snapshot = parser.snapshot()
        // EL (2K) erases the line but does NOT move the cursor
        // (VT semantics), so "abc" lands at columns 5-7 and the
        // 1;3H '*' lands at column 2 (0-based), overwriting a space.
        assertEquals("  *  abc", snapshot.lines[0].asText())
    }

    @Test
    fun sgrColoursAndReset() {
        val parser = VtParser(80, 24)
        parser.feed("\u001B[31mR\u001B[0mN")
        val line = parser.snapshot().lines[0]
        assertEquals(1, line[0].fg) // 31 -> red (1)
        assertEquals(-1, line[1].fg)
        assertEquals('R', line[0].char)
        assertEquals('N', line[1].char)
    }

    @Test
    fun unsupportedSequencesAreIgnoredNotRendered() {
        val parser = VtParser(80, 24)
        // Prompt 14 note: ?1049 is NO LONGER in this test — it is
        // the alternate screen now (see alternateScreen tests
        // below), so feeding it here would move output to the
        // alternate screen instead of ignoring it. What remains
        // ignored: another private mode (?25 cursor visibility),
        // OSC title, and 256-colour SGR.
        parser.feed("A\u001B[?25lB\u001B]0;title\u0007C\u001B[38;5;196mD")
        val text = parser.snapshot().lines[0].asText()
        assertEquals("ABCD", text)
    }

    @Test
    fun alternateScreenEnterAndExitRestoresMain() {
        val parser = VtParser(80, 24)
        parser.feed("shell\r\n")
        parser.feed("\u001B[?1049h")
        assertTrue(parser.onAlternateScreen)
        parser.feed("vim-screen")
        assertEquals("vim-screen", parser.snapshot().lines[0].asText())
        parser.feed("\u001B[?1049l")
        assertTrue(!parser.onAlternateScreen)
        val snapshot = parser.snapshot()
        assertEquals("shell", snapshot.lines[0].asText())
        assertEquals("", snapshot.lines[1].asText())
    }

    @Test
    fun alternateScreenOutputDoesNotEnterScrollback() {
        val parser = VtParser(80, 2, maxScrollback = 10)
        parser.feed("l1\r\nl2")
        parser.feed("\u001B[?1049h")
        // Scroll the alternate screen hard: its lines are
        // discarded, never history.
        parser.feed("\r\na1\r\na2\r\na3\r\na4")
        assertEquals(0, parser.snapshot().scrollbackCount)
        parser.feed("\u001B[?1049l")
        val snapshot = parser.snapshot()
        assertEquals(0, snapshot.scrollbackCount)
        assertEquals("l1", snapshot.lines[0].asText())
        assertEquals("l2", snapshot.lines[1].asText())
    }

    @Test
    fun alternateScreenDoubleEnterAndStrayExitAreNoOps() {
        val parser = VtParser(80, 24)
        parser.feed("main")
        // Stray exit while on the main screen: nothing happens.
        parser.feed("\u001B[?1049l")
        assertTrue(!parser.onAlternateScreen)
        assertEquals("main", parser.snapshot().lines[0].asText())
        parser.feed("\u001B[?1049h")
        parser.feed("alt-one")
        // Second enter must not clobber the saved main screen.
        parser.feed("\u001B[?1049h")
        parser.feed("\u001B[?1049l")
        assertEquals("main", parser.snapshot().lines[0].asText())
    }

    @Test
    fun alternateScreenCursorRestoresOnExit() {
        val parser = VtParser(80, 24)
        parser.feed("ab")
        parser.feed("\u001B[?1049h")
        parser.feed("xy")
        parser.feed("\u001B[?1049l")
        val snapshot = parser.snapshot()
        // Cursor back where the shell left it: after "ab".
        assertEquals(0, snapshot.cursorRow)
        assertEquals(2, snapshot.cursorCol)
    }

    @Test
    fun alternateScreenSurvivesResize() {
        val parser = VtParser(10, 2)
        parser.feed("0123456789")
        parser.feed("\u001B[?1049h")
        parser.feed("alt")
        parser.resize(5, 2)
        assertEquals("alt", parser.snapshot().lines[0].asText())
        parser.feed("\u001B[?1049l")
        // Main screen resized too, not restored at the old width.
        assertEquals("01234", parser.snapshot().lines[0].asText())
        assertEquals(5, parser.cols)
    }

    @Test
    fun eraseDisplayClearsScreen() {
        val parser = VtParser(80, 24)
        parser.feed("hello\u001B[2J")
        assertEquals("", parser.snapshot().lines[0].asText())
    }

    @Test
    fun resizeTruncatesWithoutReflow() {
        val parser = VtParser(10, 2)
        parser.feed("0123456789")
        parser.resize(5, 2)
        assertEquals("01234", parser.snapshot().lines[0].asText())
        assertEquals(5, parser.cols)
    }

    @Test
    fun scrollbackDropsOldestFirst() {
        val parser = VtParser(80, 2, maxScrollback = 1)
        // 4 lines of output on a 2-row screen -> 2 scrolled lines,
        // capped to the newest 1 (l2; l1 was dropped).
        parser.feed("l1\r\nl2\r\nl3\r\nl4")
        val snapshot = parser.snapshot()
        assertEquals(1, snapshot.scrollbackCount)
        assertEquals("l2", snapshot.lines[0].asText())
        assertEquals("l3", snapshot.lines[1].asText())
        assertEquals("l4", snapshot.lines[2].asText())
    }
}
