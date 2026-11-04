package com.pishi.termbridge.domain

/**
 * Pure viewport/selection maths for the Canvas terminal
 * renderer (item Q-03). The composable measures, gestures and
 * draws; every decision that can be wrong — which lines are
 * visible, which cell a pixel hits, what a selection copies —
 * lives here and is unit-tested.
 *
 * Coordinates: rows/cols are 0-based over the snapshot's full
 * line list (scrollback followed by the visible screen, the
 * same indexing the snapshot's cursor uses).
 */
object TerminalViewport {

    /**
     * First line index of the visible window.
     *
     * [offsetFromBottom] is how many lines the user has
     * scrolled up from the live edge (0 = pinned to the
     * bottom). The window is clamped so it never starts before
     * line 0 and never scrolls past the bottom.
     */
    fun windowStart(totalLines: Int, visibleRows: Int, offsetFromBottom: Int): Int {
        if (totalLines <= 0 || visibleRows <= 0) return 0
        val maxStart = maxOf(0, totalLines - visibleRows)
        val start = totalLines - visibleRows - maxOf(0, offsetFromBottom)
        return start.coerceIn(0, maxStart)
    }

    /** A cell position in the full line list. */
    data class CellPos(val row: Int, val col: Int)

    /**
     * The cell under a pixel position inside the terminal
     * area, or null when the point is outside the grid (past
     * the last line, or geometry not measured yet).
     */
    fun cellAt(
        xPx: Float,
        yPx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
        windowStart: Int,
        totalLines: Int,
        cols: Int
    ): CellPos? {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return null
        if (xPx < 0f || yPx < 0f) return null
        val row = windowStart + (yPx / cellHeightPx).toInt()
        val col = (xPx / cellWidthPx).toInt()
        if (row !in 0 until totalLines) return null
        if (col !in 0 until cols) return null
        return CellPos(row, col)
    }

    /** A text selection, stored unordered; use [normalized]. */
    data class Selection(val start: CellPos, val end: CellPos) {
        /** (first, last) in reading order. */
        fun normalized(): Pair<CellPos, CellPos> =
            if (start.row < end.row || (start.row == end.row && start.col <= end.col)) {
                start to end
            } else {
                end to start
            }
    }

    /**
     * The text a selection copies: whole lines between the
     * edge rows, partial lines at the edges (end column
     * inclusive, as users expect from terminal selection),
     * trailing spaces trimmed per line (terminals copy text,
     * not padding), lines joined with '\n'.
     */
    fun extractText(lines: List<String>, selection: Selection): String {
        if (lines.isEmpty()) return ""
        val (first, last) = selection.normalized()
        val firstRow = first.row.coerceIn(0, lines.lastIndex)
        val lastRow = last.row.coerceIn(0, lines.lastIndex)
        val out = StringBuilder()
        for (row in firstRow..lastRow) {
            val line = lines[row]
            val from = if (row == firstRow) first.col.coerceIn(0, line.length) else 0
            val to = if (row == lastRow) {
                (last.col + 1).coerceIn(from, line.length)
            } else {
                line.length
            }
            if (row > firstRow) out.append('\n')
            out.append(line.substring(from, to).trimEnd())
        }
        return out.toString()
    }
}

/**
 * http(s) URL spans inside one terminal line (item Q-03: tap
 * a link to open it). Deliberately conservative: a URL starts
 * at "http://" or "https://", runs to the next whitespace or
 * one of <>"' and then sheds trailing sentence punctuation
 * (.,;:!?)]}). Anything else is not a link.
 */
object UrlDetect {
    private val TERMINATORS = charArrayOf(' ', '\t', '<', '>', '"', '\'')
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun findUrls(line: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var index = 0
        while (index < line.length) {
            val start = findStart(line, index)
            if (start < 0) break
            var end = start
            while (end < line.length && line[end] !in TERMINATORS) end++
            while (end > start && line[end - 1] in TRAILING) end--
            // Must contain at least one character past the scheme.
            val schemeEnd = if (line.startsWith("https://", start)) start + 8 else start + 7
            if (end > schemeEnd) spans.add(start until end)
            index = maxOf(end, start + 1)
        }
        return spans
    }

    private fun findStart(line: String, from: Int): Int {
        val http = line.indexOf("http://", from)
        val https = line.indexOf("https://", from)
        return when {
            http < 0 -> https
            https < 0 -> http
            else -> minOf(http, https)
        }
    }
}
