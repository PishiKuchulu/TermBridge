package com.pishi.termbridge.domain

/**
 * Audit #6 TB-002: tracks which terminal output lines have already
 * been captured into a session transcript, in ABSOLUTE line
 * positions.
 *
 * The old diffing compared only the snapshot's line count. A
 * snapshot holds (scrollback + screen); scrollback is capped, so
 * once the cap is reached the total stops growing and capture
 * stalled permanently — a long session's transcript silently
 * contained only its first (cap + rows) lines.
 *
 * A line's absolute position is `scrollbackTrimmed + index`, where
 * `scrollbackTrimmed` is the parser's count of lines ever dropped
 * at the cap (see TerminalSnapshot.scrollbackTrimmed). That sum
 * grows for as long as the shell produces output, capped or not.
 *
 * Pure and JVM-testable; the ViewModel feeds it one snapshot at a
 * time.
 */
class TranscriptLineTracker {

    /** Absolute position just past the last captured line. */
    var seen: Long = 0L
        private set

    fun reset() {
        seen = 0L
    }

    /**
     * Returns the index range of snapshot lines that have not been
     * captured yet, or null when there is nothing new. Advances
     * [seen] past the returned lines.
     *
     * When the absolute end moves BACKWARD (scrollback cleared by
     * CSI 3J / full reset, or a smaller parser), the tracker
     * re-bases to the new end and returns null: pre-clear content
     * is gone, and post-clear lines are indexed from the same
     * monotonic base, so capture resumes correctly.
     */
    fun advance(scrollbackTrimmed: Long, lineCount: Int): IntRange? {
        val end = scrollbackTrimmed + lineCount
        if (end <= seen) {
            if (end < seen) seen = end
            return null
        }
        val from = (seen - scrollbackTrimmed).toInt().coerceIn(0, lineCount)
        seen = end
        if (from >= lineCount) return null
        return from until lineCount
    }
}
