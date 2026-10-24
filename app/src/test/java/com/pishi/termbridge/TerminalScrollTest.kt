package com.pishi.termbridge

import com.pishi.termbridge.terminal.isAtBottom
import com.pishi.termbridge.terminal.newLinesSince
import com.pishi.termbridge.terminal.shouldAutoScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 14: the terminal sticks to the bottom only when the
 * user is already at the bottom. These are pure-number
 * decisions, tested without Compose.
 */
class TerminalScrollTest {
    @Test
    fun emptyListCountsAsAtBottom() {
        assertTrue(isAtBottom(null, 0))
        assertTrue(isAtBottom(0, 0))
    }

    @Test
    fun lastVisibleIsBottom() {
        assertTrue(isAtBottom(9, 10))
        assertTrue(isAtBottom(0, 1))
    }

    @Test
    fun scrolledUpIsNotAtBottom() {
        assertFalse(isAtBottom(8, 10))
        assertFalse(isAtBottom(0, 10))
    }

    @Test
    fun unknownPositionIsNotAtBottom() {
        // Never yank the view on a guess.
        assertFalse(isAtBottom(null, 10))
    }

    @Test
    fun autoScrollOnlyWhenPinnedAndNonEmpty() {
        assertTrue(shouldAutoScroll(true, 10))
        assertFalse(shouldAutoScroll(false, 10))
        assertFalse(shouldAutoScroll(true, 0))
    }

    @Test
    fun newLinesCountWhileUnpinned() {
        assertEquals(3, newLinesSince(10, 13))
        assertEquals(0, newLinesSince(13, 13))
    }

    @Test
    fun shrunkListReportsZeroNotNegative() {
        // A resize or clear shrinking the list is not "-5 new".
        assertEquals(0, newLinesSince(13, 8))
    }
}
