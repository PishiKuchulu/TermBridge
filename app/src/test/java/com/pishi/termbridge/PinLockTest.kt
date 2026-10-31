package com.pishi.termbridge

import com.pishi.termbridge.domain.PinLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    @Test
    fun pinRules() {
        assertTrue(PinLock.isValidPin("1234"))
        assertTrue(PinLock.isValidPin("123456789012"))
        for (bad in listOf("", "123", "1234567890123", "12a4", "12 4", "١٢٣٤")) {
            assertFalse("should reject '$bad'", PinLock.isValidPin(bad))
        }
    }

    @Test
    fun hashVerifiesOnlyTheRightPin() {
        val salt = PinLock.newSalt()
        val hash = PinLock.hash("4321", salt, iterations = 1_000)
        assertTrue(PinLock.verify("4321", salt, hash, iterations = 1_000))
        assertFalse(PinLock.verify("4322", salt, hash, iterations = 1_000))
    }

    @Test
    fun saltChangesTheHash() {
        assertNotEquals(
            PinLock.hash("4321", PinLock.newSalt(), iterations = 1_000),
            PinLock.hash("4321", PinLock.newSalt(), iterations = 1_000)
        )
    }

    @Test
    fun backoffGrowsAfterRepeatedFailures() {
        assertEquals(0L, PinLock.backoffMs(4))
        assertEquals(30_000L, PinLock.backoffMs(5))
        assertEquals(120_000L, PinLock.backoffMs(8))
        assertEquals(600_000L, PinLock.backoffMs(12))
    }
}
