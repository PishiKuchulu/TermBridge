package com.pishi.termbridge

import com.pishi.termbridge.domain.TerminalGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Item Q-02: grid sizing from measured cell geometry. */
class TerminalGeometryTest {

    @Test
    fun exactDivisionFillsArea() {
        val size = TerminalGeometry.gridSize(800f, 600f, 10f, 20f)
        assertEquals(80, size.cols)
        assertEquals(30, size.rows)
    }

    @Test
    fun partialCellsAreDropped() {
        // 805 / 10 = 80.5 → 80 full cells; the PTY must never
        // claim a column that cannot be drawn whole.
        val size = TerminalGeometry.gridSize(805f, 619f, 10f, 20f)
        assertEquals(80, size.cols)
        assertEquals(30, size.rows)
    }

    @Test
    fun minimumsApplyToTinyAreas() {
        val size = TerminalGeometry.gridSize(50f, 40f, 10f, 20f)
        assertEquals(TerminalGeometry.MIN_COLS, size.cols)
        assertEquals(TerminalGeometry.MIN_ROWS, size.rows)
    }

    @Test
    fun unmeasuredCellFallsBackToMinimums() {
        val size = TerminalGeometry.gridSize(800f, 600f, 0f, 0f)
        assertEquals(TerminalGeometry.MIN_COLS, size.cols)
        assertEquals(TerminalGeometry.MIN_ROWS, size.rows)
    }

    @Test
    fun zeroAreaFallsBackToMinimums() {
        val size = TerminalGeometry.gridSize(0f, 0f, 10f, 20f)
        assertEquals(TerminalGeometry.MIN_COLS, size.cols)
        assertEquals(TerminalGeometry.MIN_ROWS, size.rows)
    }
}
