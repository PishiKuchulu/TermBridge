package com.pishi.termbridge.domain

/** Which credential the app lock uses (item T-18). Exactly one
 *  method is active at a time: setting a pattern replaces the
 *  PIN and vice versa. */
enum class LockMethod { PIN, PATTERN }

/**
 * Pattern rules for the app lock (item T-18). A pattern is a
 * sequence of dots on a 3x3 grid, index = row * 3 + col:
 *
 *   0 1 2
 *   3 4 5
 *   6 7 8
 *
 * Like Android's own pattern lock, a stroke that jumps over an
 * unvisited dot in a straight line visits it implicitly
 * (0 -> 2 passes through 1, 0 -> 8 through 4); [normalize]
 * applies that rule, and both setup and verification use the
 * normalized form, so a fast stroke and a deliberate one are
 * the same pattern. The canonical string of the normalized
 * pattern is what gets hashed — through the same salted
 * PBKDF2 path as the PIN (see [PinLock]) — so a pattern is
 * never stored, and the store's throttling applies unchanged.
 * A pattern is a convenience lock with the same honest limit
 * as the PIN: it guards casual access to the app, not the
 * Keystore-encrypted credentials.
 */
object PatternLock {
    const val GRID = 3
    const val DOT_COUNT = GRID * GRID
    const val MIN_DOTS = 4

    /**
     * The pattern as drawn, with jumped midpoints inserted and
     * already-visited dots never repeated. Out-of-range points
     * are dropped (the grid UI cannot produce them; a crafted
     * caller gets a shorter pattern, which then fails the
     * length rule or the hash comparison).
     */
    fun normalize(points: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        val visited = mutableSetOf<Int>()
        for (point in points) {
            if (point !in 0 until DOT_COUNT) continue
            val last = out.lastOrNull()
            if (last != null && point !in visited) {
                val mid = midpoint(last, point)
                if (mid != null && mid !in visited) {
                    out += mid
                    visited += mid
                }
            }
            if (point !in visited) {
                out += point
                visited += point
            }
        }
        return out
    }

    /** The dot exactly between [from] and [to] when the move is
     *  a straight or diagonal jump of length 2, else null
     *  (adjacent dots and knight moves have no midpoint). */
    private fun midpoint(from: Int, to: Int): Int? {
        val dr = row(to) - row(from)
        val dc = col(to) - col(from)
        if (dr % 2 != 0 || dc % 2 != 0) return null
        if (dr == 0 && dc == 0) return null
        val mr = row(from) + dr / 2
        val mc = col(from) + dc / 2
        if (mr == row(from) && mc == col(from)) return null
        return mr * GRID + mc
    }

    private fun row(dot: Int) = dot / GRID
    private fun col(dot: Int) = dot % GRID

    /** A settable pattern: every point on the grid and, after
     *  normalization, at least [MIN_DOTS] dots. */
    fun isValid(points: List<Int>): Boolean =
        points.all { it in 0 until DOT_COUNT } &&
            normalize(points).size >= MIN_DOTS

    /** The string that is hashed: the normalized points joined
     *  unambiguously ("0-1-2-5-8"). */
    fun canonical(points: List<Int>): String =
        normalize(points).joinToString("-")
}
