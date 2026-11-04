package com.pishi.termbridge

import com.pishi.termbridge.domain.PatternLock
import com.pishi.termbridge.domain.PinLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-18: pattern-lock rules (pure domain). */
class PatternLockTest {

    @Test
    fun normalizeInsertsStraightMidpoints() {
        assertEquals(listOf(0, 1, 2), PatternLock.normalize(listOf(0, 2)))
        assertEquals(listOf(0, 3, 6), PatternLock.normalize(listOf(0, 6)))
        assertEquals(listOf(8, 5, 2), PatternLock.normalize(listOf(8, 2)))
    }

    @Test
    fun normalizeInsertsDiagonalMidpoint() {
        assertEquals(listOf(0, 4, 8), PatternLock.normalize(listOf(0, 8)))
        assertEquals(listOf(2, 4, 6), PatternLock.normalize(listOf(2, 6)))
    }

    @Test
    fun normalizeDoesNotDuplicateVisitedMidpointOrDot() {
        // 1 is already visited when 0 -> 2 jumps over it.
        assertEquals(listOf(1, 0, 2), PatternLock.normalize(listOf(1, 0, 2)))
        // Re-touching a visited dot adds nothing.
        assertEquals(listOf(0, 1), PatternLock.normalize(listOf(0, 1, 0)))
    }

    @Test
    fun knightMovesAndAdjacentStepsHaveNoMidpoint() {
        // 0 -> 7 is a knight move (dr 2, dc 1): no dot between.
        assertEquals(listOf(0, 7), PatternLock.normalize(listOf(0, 7)))
        assertEquals(listOf(0, 1, 5), PatternLock.normalize(listOf(0, 1, 5)))
    }

    @Test
    fun chainedJumpsNormalizeFully() {
        // 0 -> 8 through the centre, then 8 -> 2 through 5.
        assertEquals(
            listOf(0, 4, 8, 5, 2),
            PatternLock.normalize(listOf(0, 8, 2))
        )
        assertEquals("0-1-2-5-8", PatternLock.canonical(listOf(0, 2, 8)))
    }

    @Test
    fun validityNeedsFourNormalizedDots() {
        assertTrue(PatternLock.isValid(listOf(0, 1, 2, 5)))
        // A jump counts its midpoint: 0 -> 2 -> 8 is 5 dots.
        assertTrue(PatternLock.isValid(listOf(0, 2, 8)))
        assertFalse(PatternLock.isValid(listOf(0, 1, 2)))
        assertFalse(PatternLock.isValid(listOf(0, 2)))
        assertFalse(PatternLock.isValid(listOf(0, 1, 2, 9)))
        assertFalse(PatternLock.isValid(emptyList()))
    }

    @Test
    fun canonicalFormHashesAndVerifiesLikeAPin() {
        // The store hashes the canonical string through the
        // existing salted PBKDF2 path; a differently-drawn but
        // equivalent stroke (midpoints explicit) must verify,
        // a different pattern must not.
        val salt = PinLock.newSalt()
        val stored = PinLock.hash(PatternLock.canonical(listOf(0, 2, 8)), salt)
        assertTrue(
            PinLock.verify(PatternLock.canonical(listOf(0, 1, 2, 5, 8)), salt, stored)
        )
        assertFalse(
            PinLock.verify(PatternLock.canonical(listOf(0, 1, 2, 3)), salt, stored)
        )
    }
}
