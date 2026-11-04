package com.pishi.termbridge

import com.pishi.termbridge.domain.TerminalViewport
import com.pishi.termbridge.domain.TerminalViewport.CellPos
import com.pishi.termbridge.domain.TerminalViewport.Selection
import com.pishi.termbridge.domain.UrlDetect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Item Q-03: viewport, hit-testing, selection copy, URLs. */
class TerminalViewportTest {

    @Test
    fun windowPinnedToBottomShowsLastRows() {
        assertEquals(70, TerminalViewport.windowStart(100, 30, 0))
    }

    @Test
    fun windowScrolledUpMovesBack() {
        assertEquals(60, TerminalViewport.windowStart(100, 30, 10))
    }

    @Test
    fun windowClampsAtTop() {
        assertEquals(0, TerminalViewport.windowStart(100, 30, 500))
        assertEquals(0, TerminalViewport.windowStart(10, 30, 0))
        assertEquals(0, TerminalViewport.windowStart(0, 30, 0))
    }

    @Test
    fun cellAtMapsPixelsToCells() {
        // Window starts at line 70; cell 10x20 px.
        assertEquals(
            CellPos(70, 0),
            TerminalViewport.cellAt(0f, 0f, 10f, 20f, 70, 100, 80)
        )
        assertEquals(
            CellPos(71, 3),
            TerminalViewport.cellAt(35f, 25f, 10f, 20f, 70, 100, 80)
        )
    }

    @Test
    fun cellAtRejectsOutsideTheGrid() {
        assertNull(TerminalViewport.cellAt(-1f, 0f, 10f, 20f, 70, 100, 80))
        assertNull(TerminalViewport.cellAt(0f, 0f, 0f, 20f, 70, 100, 80))
        // Row past the last line.
        assertNull(TerminalViewport.cellAt(0f, 610f, 10f, 20f, 70, 100, 80))
        // Column past the grid width.
        assertNull(TerminalViewport.cellAt(805f, 0f, 10f, 20f, 70, 100, 80))
    }

    @Test
    fun extractSingleLineSelectionIsInclusive() {
        val lines = listOf("hello world")
        val text = TerminalViewport.extractText(
            lines, Selection(CellPos(0, 0), CellPos(0, 4))
        )
        assertEquals("hello", text)
    }

    @Test
    fun extractMultiLineTrimsEdgesAndPadding() {
        val lines = listOf("abc   ", "middle", "xyz   ")
        val text = TerminalViewport.extractText(
            lines, Selection(CellPos(0, 1), CellPos(2, 1))
        )
        assertEquals("bc\nmiddle\nxy", text)
    }

    @Test
    fun extractNormalizesReversedSelection() {
        val lines = listOf("abcdef")
        val text = TerminalViewport.extractText(
            lines, Selection(CellPos(0, 4), CellPos(0, 1))
        )
        assertEquals("bcde", text)
    }

    @Test
    fun urlsAreFoundWithSpans() {
        val line = "see https://example.com/path?q=1 for details"
        val spans = UrlDetect.findUrls(line)
        assertEquals(1, spans.size)
        assertEquals("https://example.com/path?q=1", line.substring(spans[0]))
    }

    @Test
    fun urlTrailingPunctuationIsShed() {
        val line = "(link: http://a.b/c)."
        val spans = UrlDetect.findUrls(line)
        assertEquals(1, spans.size)
        assertEquals("http://a.b/c", line.substring(spans[0]))
    }

    @Test
    fun bareSchemeIsNotAUrl() {
        assertEquals(0, UrlDetect.findUrls("broken https:// link").size)
        assertEquals(0, UrlDetect.findUrls("no links here").size)
    }

    @Test
    fun twoUrlsInOneLine() {
        val line = "http://a.io and https://b.io/x"
        val spans = UrlDetect.findUrls(line)
        assertEquals(2, spans.size)
        assertEquals("http://a.io", line.substring(spans[0]))
        assertEquals("https://b.io/x", line.substring(spans[1]))
    }
}
