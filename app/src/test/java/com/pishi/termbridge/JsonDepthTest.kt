package com.pishi.termbridge

import com.pishi.termbridge.domain.JsonDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit #7 AUD-001: the pre-parse depth guard's scanner. */
class JsonDepthTest {

    @Test
    fun flatObjectHasDepthOne() {
        assertEquals(1, JsonDepth.maxDepth("""{"a":1}"""))
    }

    @Test
    fun nestedCountsDeepest() {
        assertEquals(3, JsonDepth.maxDepth("""{"a":[{"b":1}]}"""))
    }

    @Test
    fun noBracketsIsZero() {
        assertEquals(0, JsonDepth.maxDepth("42"))
    }

    @Test
    fun bracketsInsideStringsDoNotCount() {
        assertEquals(1, JsonDepth.maxDepth("""{"a":"[[[{"}"""))
    }

    @Test
    fun escapedQuotesDoNotEndStrings() {
        assertEquals(1, JsonDepth.maxDepth("""{"a":"\"[[["}"""))
    }

    @Test
    fun exceedsDetectsHostileNesting() {
        assertTrue(JsonDepth.exceeds("[".repeat(100), 64))
        assertFalse(JsonDepth.exceeds("[".repeat(10), 64))
    }
}
