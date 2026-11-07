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

    @Test
    fun lockCostMatchesBackupExportCost() {
        // Audit #3 (A-23): parity with ExportCrypto's 600,000,
        // with the pre-raise cost kept as the verify-only legacy.
        assertEquals(600_000, PinLock.ITERATIONS)
        assertEquals(120_000, PinLock.LEGACY_ITERATIONS)
    }

    @Test
    fun hashVerifiesAtCurrentAndLegacyCosts() {
        // Audit #3 (A-23): hashes are stored without their
        // iteration count, so the current cost and the legacy
        // cost must each round-trip under their own count — and
        // must NOT verify under the other count (otherwise the
        // AppLockStore fallback could not tell them apart).
        val salt = PinLock.newSalt()
        val current = PinLock.hash("4321", salt)
        assertTrue(PinLock.verify("4321", salt, current))
        assertFalse(PinLock.verify("4321", salt, current, iterations = PinLock.LEGACY_ITERATIONS))

        val legacy = PinLock.hash("4321", salt, iterations = PinLock.LEGACY_ITERATIONS)
        assertTrue(PinLock.verify("4321", salt, legacy, iterations = PinLock.LEGACY_ITERATIONS))
        assertFalse(PinLock.verify("4321", salt, legacy))
    }

    @Test
    fun wrongPinRejectedAtBothCosts() {
        val salt = PinLock.newSalt()
        val current = PinLock.hash("4321", salt)
        assertFalse(PinLock.verify("4322", salt, current))
        assertFalse(PinLock.verify("4322", salt, current, iterations = PinLock.LEGACY_ITERATIONS))

        val legacy = PinLock.hash("4321", salt, iterations = PinLock.LEGACY_ITERATIONS)
        assertFalse(PinLock.verify("4322", salt, legacy, iterations = PinLock.LEGACY_ITERATIONS))
        assertFalse(PinLock.verify("4322", salt, legacy))
    }

    // ---- Audit #7 AUD-005: throttle clock math ----
    // Imposed at wall 1_000_000 / elapsed 500_000, wait 30 s,
    // wall deadline 1_030_000.

    private fun remaining(
        nowWall: Long,
        nowElapsed: Long,
        sameBoot: Boolean,
        untilWall: Long = 1_030_000L,
        setWall: Long = 1_000_000L,
        setElapsed: Long = 500_000L,
        waitMs: Long = 30_000L
    ): Long = PinLock.throttleRemainingMs(
        nowWall, nowElapsed, untilWall, setWall, setElapsed, waitMs, sameBoot
    )

    @Test
    fun throttleFollowsMonotonicClockWithinBoot() {
        assertEquals(20_000L, remaining(nowWall = 1_010_000L, nowElapsed = 510_000L, sameBoot = true))
    }

    @Test
    fun backwardWallJumpDoesNotStretchThrottle() {
        // The audit's scenario: wall clock moved back a day
        // after the throttle was imposed; only 10 s passed.
        assertEquals(
            20_000L,
            remaining(nowWall = 1_000_000L - 86_400_000L, nowElapsed = 510_000L, sameBoot = true)
        )
    }

    @Test
    fun forwardWallJumpDoesNotEraseThrottle() {
        assertEquals(
            20_000L,
            remaining(nowWall = 9_999_999_999L, nowElapsed = 510_000L, sameBoot = true)
        )
    }

    @Test
    fun throttleExpiresByMonotonicClock() {
        assertEquals(0L, remaining(nowWall = 1_010_000L, nowElapsed = 531_000L, sameBoot = true))
    }

    @Test
    fun afterRebootBackwardJumpIsClampedToImposedWait() {
        // Post-reboot the wall clock rules, but a backward jump
        // can never stretch the wait past the imposed 30 s.
        assertEquals(
            30_000L,
            remaining(nowWall = 1_000_000L - 86_400_000L, nowElapsed = 5_000L, sameBoot = false)
        )
    }

    @Test
    fun afterRebootWallDeadlineStillApplies() {
        assertEquals(20_000L, remaining(nowWall = 1_010_000L, nowElapsed = 5_000L, sameBoot = false))
    }

    @Test
    fun legacyStateWithoutAnchorsUsesWallClock() {
        assertEquals(
            20_000L,
            PinLock.throttleRemainingMs(
                nowWall = 1_010_000L, nowElapsed = 510_000L,
                untilWall = 1_030_000L, setWall = 0L, setElapsed = 0L,
                waitMs = 0L, sameBoot = true
            )
        )
    }

    @Test
    fun noThrottleIsZero() {
        assertEquals(
            0L,
            PinLock.throttleRemainingMs(1_000L, 1_000L, 0L, 0L, 0L, 0L, sameBoot = true)
        )
    }
}
