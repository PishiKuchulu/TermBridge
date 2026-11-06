package com.pishi.termbridge.terminal

/**
 * One terminal cell. Colour codes are ANSI numbers: -1 = default,
 * 0–7 standard, 8–15 bright. Bold is a flag, not a colour. The UI
 * maps these numbers to actual colours; the parser never picks a
 * Compose colour, so it stays UI-independent and JVM-testable.
 */
data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = -1,
    val bg: Int = -1,
    val bold: Boolean = false
)

/** Cursor shapes, set by DECSCUSR or by the user's setting. */
enum class CursorShape { BLOCK, UNDERLINE, BAR }

data class TerminalSnapshot(
    /** Scrollback lines followed by visible screen lines. */
    val lines: List<List<TerminalCell>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val scrollbackCount: Int,
    /** Number of BEL characters received so far (for a haptic tick). */
    val bellCount: Int = 0,
    /** Cursor shape from the last DECSCUSR (item T-12). */
    val cursorShape: CursorShape = CursorShape.BLOCK,
    /** Whether the DECSCUSR shape blinks. */
    val cursorBlinking: Boolean = false,
    /** True once the remote app set the cursor style itself;
     *  until then the user's setting is the effective one. */
    val cursorStyleSetByApp: Boolean = false,
    /** CSI ?25: applications may hide the cursor. */
    val cursorVisible: Boolean = true,
    /**
     * Audit #6 TB-001: monotonic counter, bumped on every
     * [VtParser.feed]. Snapshot lines alias the parser's live rows,
     * so two snapshots of a repaint-only change (a progress bar
     * redrawn at the same cursor position) would otherwise compare
     * EQUAL as data-class values and StateFlow would conflate the
     * update away — the frame would never reach the UI. The
     * revision makes every fed frame distinct.
     */
    val revision: Long = 0,
    /**
     * Audit #6 TB-002: total number of scrollback lines the parser
     * has ever trimmed at the cap. A consumer diffing snapshots by
     * line count alone saturates at (cap + rows) and stalls;
     * [scrollbackTrimmed] + a line's index in [lines] is the line's
     * absolute, ever-growing position in the session's output.
     */
    val scrollbackTrimmed: Long = 0
)

private enum class ParserState {
    GROUND,
    ESCAPE,
    CSI,
    OSC
}

/**
 * A scoped, own VT parser (ADR-04). Supported, and tested:
 * printable chars, CR, LF, backspace, tab, bell (ignored),
 * CSI cursor moves (A/B/C/D/H/f/d/G), erase in display (J) and
 * line (K), SGR colours/bold/reset (m), save/restore cursor (s/u,
 * ESC 7/8), DECSCUSR cursor style (CSI Ps SP q) and cursor
 * visibility (CSI ?25h/l), and the alternate screen (CSI ?1049h / ?1049l,
 * prompt 14 — this is what makes full-screen apps such as vim,
 * less, and htop usable: they draw on a separate screen, and
 * exiting restores the shell's screen exactly). Everything else
 * — including OSC sequences, other private modes (CSI ? ...),
 * and 256/true-colour SGR — is consumed and ignored, never
 * rendered as garbage text.
 * See docs/TERMINAL_LIMITS.md for the full list.
 *
 * Alternate-screen semantics, kept deliberately close to a real
 * terminal: entering saves the main screen grid, the cursor, and
 * the saved-cursor slot, and switches to a blank screen;
 * scrolling while on the alternate screen discards lines at the
 * top instead of adding them to scrollback (an app's transient
 * output is not shell history); exiting restores the main screen
 * exactly. Entering while already on the alternate screen, and
 * exiting while not on it, are both no-ops — a second enter must
 * never clobber the saved main screen.
 *
 * Scrollback is capped: [maxScrollback] lines, oldest dropped
 * first. Total memory is therefore bounded by
 * (maxScrollback + rows) lines, never by the amount of output.
 *
 * Resize does NOT reflow: lines are truncated/padded to the new
 * width, the screen is rebuilt at the new height, scrollback is
 * kept (truncated to the new width). Reflow is a documented
 * limitation, not a silent bug.
 */
class VtParser(
    initialCols: Int,
    initialRows: Int,
    private val maxScrollback: Int = 1000
) {
    private companion object {
        /** Longest CSI parameter string kept (review #2 F-07 hardening). */
        const val MAX_CSI_PARAM_CHARS = 256
    }

    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    private var screen: MutableList<MutableList<TerminalCell>> = newScreen()
    private val scrollback: MutableList<List<TerminalCell>> = mutableListOf()

    // Audit #6 TB-001 / TB-002: see TerminalSnapshot's fields.
    private var feedRevision = 0L
    private var scrollbackTrimmedTotal = 0L
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0

    // Prompt 14 alternate screen: null = on the main screen.
    // Non-null = on the alternate screen, holding the saved main
    // screen (grid + cursor + saved-cursor slot) for restore.
    private var savedMainScreen: MutableList<MutableList<TerminalCell>>? = null
    private var savedMainCursorRow = 0
    private var savedMainCursorCol = 0
    private var savedMainSavedRow = 0
    private var savedMainSavedCol = 0

    /** True while full-screen apps draw on the alternate screen. */
    val onAlternateScreen: Boolean get() = savedMainScreen != null

    private var fg = -1
    private var bg = -1
    private var bold = false

    private var state = ParserState.GROUND
    private var oscSawEsc = false
    private var bellCount = 0
    private val csiParams = StringBuilder()
    private var csiPrivate = false
    private var csiIntermediate: Char? = null
    private var cursorShape = CursorShape.BLOCK
    private var cursorBlinking = false
    private var cursorStyleSetByApp = false
    private var cursorVisible = true

    /**
     * Local clear (item T-19 shake action): blanks the visible
     * screen and homes the cursor. On the main screen the
     * scrollback is dropped too; on the alternate screen only
     * the visible grid is blanked — the saved main screen and
     * its scrollback are the shell's history, not the
     * full-screen app's, and exiting the alternate screen
     * restores them as usual. Any half-consumed escape sequence
     * is abandoned (state back to GROUND), so a clear pressed
     * mid-sequence cannot garble the next output.
     */
    fun clearScreenAndScrollback() {
        if (savedMainScreen == null) {
            scrollback.clear()
        }
        screen = newScreen()
        cursorRow = 0
        cursorCol = 0
        state = ParserState.GROUND
        csiParams.clear()
        csiPrivate = false
        csiIntermediate = null
    }

    private fun newScreen(): MutableList<MutableList<TerminalCell>> {
        return MutableList(rows) { MutableList(cols) { TerminalCell() } }
    }

    private fun blankLine(): MutableList<TerminalCell> =
        MutableList(cols) { TerminalCell() }

    fun feed(text: String) {
        feedRevision++
        for (ch in text) {
            when (state) {
                ParserState.GROUND -> ground(ch)
                ParserState.ESCAPE -> escape(ch)
                ParserState.CSI -> csi(ch)
                ParserState.OSC -> osc(ch)
            }
        }
    }

    /**
     * Review #2 F-08: an OSC string ends with BEL **or** ST (`ESC \\`,
     * or the C1 form 0x9C). CAN/SUB abort it. A bare ESC followed by
     * anything other than `\\` starts a new escape sequence. Without
     * this, a program that terminates its OSC with ST (very common)
     * left the parser swallowing all later output until some BEL.
     */
    private fun osc(ch: Char) {
        if (oscSawEsc) {
            oscSawEsc = false
            if (ch == '\\') {
                state = ParserState.GROUND
            } else {
                state = ParserState.ESCAPE
                escape(ch)
            }
            return
        }
        when (ch) {
            '\u0007', '\u009C', '\u0018', '\u001A' -> state = ParserState.GROUND
            '\u001B' -> oscSawEsc = true
            else -> Unit
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001B' -> state = ParserState.ESCAPE
            '\r' -> cursorCol = 0
            '\n' -> lineFeed()
            '\b' -> if (cursorCol > 0) cursorCol--
            // Review #2: the old expression applied coerceAtMost to the
            // literal 8 (operator precedence), so a tab near the right
            // edge moved the cursor past the last column and wrapped.
            '\t' -> cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1)
            '\u0007' -> bellCount += 1
            '\u0000' -> Unit // NUL: ignored.
            else -> {
                if (ch.code >= 0x20 && ch.code != 0x7F) {
                    putChar(ch)
                }
            }
        }
    }

    private fun escape(ch: Char) {
        when (ch) {
            '[' -> {
                state = ParserState.CSI
                csiParams.clear()
                csiPrivate = false
                csiIntermediate = null
            }
            ']' -> {
                oscSawEsc = false
                state = ParserState.OSC
            }
            '7' -> {
                savedRow = cursorRow
                savedCol = cursorCol
                state = ParserState.GROUND
            }
            '8' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                state = ParserState.GROUND
            }
            'c' -> {
                reset()
                state = ParserState.GROUND
            }
            'M' -> {
                // Reverse index: move up, scrolling down at the top.
                if (cursorRow == 0) {
                    screen.add(0, blankLine())
                    // Bottom line scrolls off and is discarded — it
                    // is not scrollback (scrollback is top history).
                    screen.removeAt(screen.size - 1)
                } else {
                    cursorRow--
                }
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    private fun csi(ch: Char) {
        when {
            ch == '?' && csiParams.isEmpty() -> csiPrivate = true
            ch.code in 0x30..0x3F -> if (csiParams.length < MAX_CSI_PARAM_CHARS) csiParams.append(ch) // Params; bounded so endless digits cannot grow it.
            ch.code in 0x20..0x2F -> {
                // Intermediate bytes (e.g. the space in DECSCUSR
                // "CSI 2 SP q"). Before this branch existed, the
                // space aborted the sequence and the final byte
                // was printed as text.
                if (csiIntermediate == null) csiIntermediate = ch
            }
            ch.code in 0x40..0x7E -> {
                if (!csiPrivate) {
                    executeCsi(ch, parseParams(), csiIntermediate)
                } else {
                    executePrivate(ch, parseParams())
                }
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    /**
     * Private-mode finals. ?25 (cursor visibility) and ?1049
     * (alternate screen) are acted on; every other private mode
     * is consumed and ignored. The parameter list may carry
     * several modes at once (e.g. ?1049;25h).
     */
    private fun executePrivate(final: Char, params: List<Int>) {
        if (25 in params) {
            when (final) {
                'h' -> cursorVisible = true
                'l' -> cursorVisible = false
            }
        }
        if (1049 !in params) return
        when (final) {
            'h' -> {
                if (savedMainScreen != null) return // Already there: no-op.
                savedMainScreen = screen
                savedMainCursorRow = cursorRow
                savedMainCursorCol = cursorCol
                savedMainSavedRow = savedRow
                savedMainSavedCol = savedCol
                screen = newScreen()
                cursorRow = 0
                cursorCol = 0
                savedRow = 0
                savedCol = 0
            }
            'l' -> {
                val main = savedMainScreen ?: return // Not there: no-op.
                screen = main
                cursorRow = savedMainCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedMainCursorCol.coerceIn(0, cols - 1)
                savedRow = savedMainSavedRow
                savedCol = savedMainSavedCol
                savedMainScreen = null
            }
            else -> Unit
        }
    }

    private fun parseParams(): List<Int> {
        if (csiParams.isEmpty()) return emptyList()
        return csiParams.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun param(params: List<Int>, index: Int, default: Int): Int {
        val value = params.getOrNull(index) ?: 0
        return if (value == 0) default else value
    }

    private fun executeCsi(final: Char, params: List<Int>, intermediate: Char? = null) {
        when (final) {
            // Review #2 F-07: Long arithmetic. `ESC[2147483647B` used to
            // overflow Int to a negative row, the next screen[row]
            // access threw, and the session was reported as lost.
            'A' -> cursorRow = (cursorRow.toLong() - param(params, 0, 1)).coerceIn(0L, (rows - 1).toLong()).toInt()
            'B' -> cursorRow = (cursorRow.toLong() + param(params, 0, 1)).coerceIn(0L, (rows - 1).toLong()).toInt()
            'C' -> cursorCol = (cursorCol.toLong() + param(params, 0, 1)).coerceIn(0L, (cols - 1).toLong()).toInt()
            'D' -> cursorCol = (cursorCol.toLong() - param(params, 0, 1)).coerceIn(0L, (cols - 1).toLong()).toInt()
            'H', 'f' -> {
                cursorRow = (param(params, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (param(params, 1, 1) - 1).coerceIn(0, cols - 1)
            }
            'd' -> cursorRow = (param(params, 0, 1) - 1).coerceIn(0, rows - 1)
            'G' -> cursorCol = (param(params, 0, 1) - 1).coerceIn(0, cols - 1)
            'J' -> when (param(params, 0, 0)) {
                0 -> {
                    eraseLineFrom(cursorRow, cursorCol)
                    for (r in cursorRow + 1 until rows) screen[r] = blankLine()
                }
                1 -> {
                    for (r in 0 until cursorRow) screen[r] = blankLine()
                    eraseLineTo(cursorRow, cursorCol)
                }
                2 -> screen = newScreen()
                3 -> {
                    screen = newScreen()
                    scrollback.clear()
                }
            }
            'K' -> when (param(params, 0, 0)) {
                0 -> eraseLineFrom(cursorRow, cursorCol)
                1 -> eraseLineTo(cursorRow, cursorCol)
                2 -> screen[cursorRow] = blankLine()
            }
            'm' -> applySgr(if (params.isEmpty()) listOf(0) else params)
            // DECSCUSR (item T-12): "CSI Ps SP q" — the space
            // intermediate is what makes it this sequence; a
            // bare 'q' final is not DECSCUSR and stays ignored.
            'q' -> if (intermediate == ' ') {
                when (param(params, 0, 1)) {
                    0, 1 -> {
                        cursorShape = CursorShape.BLOCK
                        cursorBlinking = true
                        cursorStyleSetByApp = true
                    }
                    2 -> {
                        cursorShape = CursorShape.BLOCK
                        cursorBlinking = false
                        cursorStyleSetByApp = true
                    }
                    3 -> {
                        cursorShape = CursorShape.UNDERLINE
                        cursorBlinking = true
                        cursorStyleSetByApp = true
                    }
                    4 -> {
                        cursorShape = CursorShape.UNDERLINE
                        cursorBlinking = false
                        cursorStyleSetByApp = true
                    }
                    5 -> {
                        cursorShape = CursorShape.BAR
                        cursorBlinking = true
                        cursorStyleSetByApp = true
                    }
                    6 -> {
                        cursorShape = CursorShape.BAR
                        cursorBlinking = false
                        cursorStyleSetByApp = true
                    }
                    else -> Unit // Unknown style number: keep the current one.
                }
            }
            's' -> {
                savedRow = cursorRow
                savedCol = cursorCol
            }
            'u' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
            }
            // All other CSI finals are ignored, not rendered.
            else -> Unit
        }
    }

    private fun eraseLineFrom(row: Int, col: Int) {
        for (c in col until cols) screen[row][c] = TerminalCell()
    }

    private fun eraseLineTo(row: Int, col: Int) {
        for (c in 0..col.coerceAtMost(cols - 1)) screen[row][c] = TerminalCell()
    }

    private fun applySgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> {
                    fg = -1
                    bg = -1
                    bold = false
                }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> fg = p - 30
                39 -> fg = -1
                in 40..47 -> bg = p - 40
                49 -> bg = -1
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
                // 38/48 extended colours: consume their arguments
                // (38;5;n or 38;2;r;g;b) and ignore the colour.
                38, 48 -> {
                    val mode = params.getOrNull(i + 1) ?: 0
                    i += if (mode == 2) 4 else 2
                }
                else -> Unit
            }
            i++
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        screen[cursorRow][cursorCol] = TerminalCell(ch, fg, bg, bold)
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == rows - 1) {
            val top = screen.removeAt(0)
            if (savedMainScreen == null) {
                // Main screen: the scrolled line becomes history.
                // Alternate screen: it is discarded — an app's
                // transient output is not shell history.
                scrollback.add(top)
                trimScrollback()
            }
            screen.add(blankLine())
        } else {
            cursorRow++
        }
    }

    private fun trimScrollback() {
        while (scrollback.size > maxScrollback) {
            scrollback.removeAt(0)
            scrollbackTrimmedTotal++
        }
    }

    private fun reset() {
        screen = newScreen()
        // A full reset leaves the alternate screen too: the saved
        // main screen is discarded, not restored from stale state.
        savedMainScreen = null
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        fg = -1
        bg = -1
        bold = false
        cursorShape = CursorShape.BLOCK
        cursorBlinking = false
        cursorStyleSetByApp = false
        cursorVisible = true
    }

    /** Truncate/pad one grid to the new size, without reflow. */
    private fun resizeGrid(
        grid: List<List<TerminalCell>>,
        newCols: Int,
        newRows: Int
    ): MutableList<MutableList<TerminalCell>> {
        return MutableList(newRows) { r ->
            val old = grid.getOrNull(r)
            if (old == null) {
                MutableList(newCols) { TerminalCell() }
            } else {
                MutableList(newCols) { c -> old.getOrNull(c) ?: TerminalCell() }
            }
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        val oldScreen = screen
        val oldScrollback = scrollback.toList()
        val oldMain = savedMainScreen
        cols = newCols
        rows = newRows
        screen = resizeGrid(oldScreen, newCols, newRows)
        if (oldMain != null) {
            // Resize the saved main screen too, so exiting the
            // alternate screen after a rotation does not restore
            // a grid of the old size.
            savedMainScreen = resizeGrid(oldMain, newCols, newRows)
            savedMainCursorRow = savedMainCursorRow.coerceIn(0, rows - 1)
            savedMainCursorCol = savedMainCursorCol.coerceIn(0, cols - 1)
        }
        scrollback.clear()
        scrollback.addAll(oldScrollback.map { line ->
            List(cols) { c -> line.getOrNull(c) ?: TerminalCell() }
        })
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    fun snapshot(): TerminalSnapshot {
        val lines = ArrayList<List<TerminalCell>>(scrollback.size + screen.size)
        lines.addAll(scrollback)
        lines.addAll(screen)
        return TerminalSnapshot(
            lines = lines,
            cursorRow = scrollback.size + cursorRow,
            cursorCol = cursorCol,
            scrollbackCount = scrollback.size,
            bellCount = bellCount,
            cursorShape = cursorShape,
            cursorBlinking = cursorBlinking,
            cursorStyleSetByApp = cursorStyleSetByApp,
            cursorVisible = cursorVisible,
            revision = feedRevision,
            scrollbackTrimmed = scrollbackTrimmedTotal
        )
    }
}

/** Render a snapshot line as plain text (for tests and diagnostics). */
fun List<TerminalCell>.asText(): String =
    buildString { for (cell in this@asText) append(cell.char) }.trimEnd()
