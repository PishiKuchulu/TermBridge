package com.pishi.termbridge

import com.pishi.termbridge.domain.TranscriptLineTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Audit #6 TB-002 regression suite. The pre-fix ViewModel diffed
 * raw snapshot line counts; once the scrollback cap saturated, the
 * count stopped growing and transcript capture stalled for the
 * rest of the session (the audit's executed model captured 25 of
 * 200 lines). The tracker diffs ABSOLUTE positions instead.
 */
class TranscriptLineTrackerTest {

    @Test
    fun capturesGrowthBelowTheCap() {
        val tracker = TranscriptLineTracker()
        assertEquals(0..9, tracker.advance(0, 10))
        assertNull(tracker.advance(0, 10))
        assertEquals(10..11, tracker.advance(0, 12))
    }

    @Test
    fun keepsCapturingAfterScrollbackSaturates() {
        // Window pins at cap(20) + rows(5) = 25 lines while the
        // shell keeps producing: every step trims one line at the
        // front and appends one at the back.
        val tracker = TranscriptLineTracker()
        var captured = 0
        tracker.advance(0, 25)?.let { captured += it.count() }
        var trimmed = 0L
        repeat(175) {
            trimmed++
            tracker.advance(trimmed, 25)?.let { captured += it.count() }
        }
        // 200 lines produced, 200 captured — the pre-fix logic
        // captured 25 and then stalled permanently.
        assertEquals(200, captured)
    }

    @Test
    fun rebasesWhenScrollbackIsCleared() {
        val tracker = TranscriptLineTracker()
        assertEquals(0..29, tracker.advance(0, 30))
        // CSI 3J / reset: the window shrinks; nothing new yet.
        assertNull(tracker.advance(0, 5))
        assertEquals(5L, tracker.seen)
        // Capture resumes from the re-based position.
        assertEquals(5..6, tracker.advance(0, 7))
    }

    @Test
    fun resetRecapturesFromTheStart() {
        val tracker = TranscriptLineTracker()
        tracker.advance(0, 10)
        tracker.reset()
        assertEquals(0L, tracker.seen)
        assertEquals(0..9, tracker.advance(0, 10))
    }
}
