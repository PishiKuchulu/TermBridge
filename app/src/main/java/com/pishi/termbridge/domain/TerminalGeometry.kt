package com.pishi.termbridge.domain

/**
 * Terminal grid arithmetic (item Q-02). The UI measures the
 * real monospace cell with a text measurer; this object turns
 * an available pixel area into a column/row count, purely, so
 * the arithmetic is unit-tested. The PTY is sized from these
 * numbers, so the remote side wraps exactly where the screen
 * does.
 */
object TerminalGeometry {
    const val MIN_COLS = 20
    const val MIN_ROWS = 10

    data class GridSize(val cols: Int, val rows: Int)

    fun gridSize(
        availableWidthPx: Float,
        availableHeightPx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float
    ): GridSize {
        val cols = if (cellWidthPx > 0f && availableWidthPx > 0f) {
            (availableWidthPx / cellWidthPx).toInt()
        } else {
            MIN_COLS
        }
        val rows = if (cellHeightPx > 0f && availableHeightPx > 0f) {
            (availableHeightPx / cellHeightPx).toInt()
        } else {
            MIN_ROWS
        }
        return GridSize(
            cols = cols.coerceAtLeast(MIN_COLS),
            rows = rows.coerceAtLeast(MIN_ROWS)
        )
    }
}
