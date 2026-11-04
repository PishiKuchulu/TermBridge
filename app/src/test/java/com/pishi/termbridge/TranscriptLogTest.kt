package com.pishi.termbridge

import com.pishi.termbridge.domain.TranscriptLog
import org.junit.Assert.assertEquals
import org.junit.Test

/** Item T-03: transcript size policy. */
class TranscriptLogTest {

    @Test
    fun appendsWhileUnderTheCap() {
        assertEquals(
            TranscriptLog.Plan.APPEND,
            TranscriptLog.plan(existingBytes = 100, incomingBytes = 50, maxBytes = 200)
        )
        assertEquals(
            TranscriptLog.Plan.APPEND,
            TranscriptLog.plan(existingBytes = 0, incomingBytes = 0, maxBytes = 200)
        )
    }

    @Test
    fun rotatesWhenTheAppendWouldPassTheCap() {
        assertEquals(
            TranscriptLog.Plan.ROTATE_THEN_APPEND,
            TranscriptLog.plan(existingBytes = 190, incomingBytes = 20, maxBytes = 200)
        )
    }

    @Test
    fun tailKeepsTheNewestWholeLines() {
        val text = "aaaa\nbbbb\ncccc\ndddd"
        val kept = TranscriptLog.tail(text, maxBytes = 10)
        // Last 10 bytes are "\ncccc\ndddd" cut mid-way; the
        // partial first line is dropped.
        assertEquals("cccc\ndddd", kept)
    }

    @Test
    fun tailUnderTheCapIsUntouched() {
        assertEquals("short", TranscriptLog.tail("short", maxBytes = 100))
    }
}
