package com.pishi.termbridge

import com.pishi.termbridge.domain.KeepAlivePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-14: consecutive-miss counting for the probe loop. */
class KeepAlivePolicyTest {

    @Test
    fun dropsExactlyAtMaxMisses() {
        val policy = KeepAlivePolicy(maxMisses = 3)
        assertFalse(policy.onMiss())
        assertFalse(policy.onMiss())
        assertTrue(policy.onMiss())
    }

    @Test
    fun replyResetsTheStreak() {
        val policy = KeepAlivePolicy(maxMisses = 2)
        assertFalse(policy.onMiss())
        policy.onReply()
        assertFalse(policy.onMiss())
        assertTrue(policy.onMiss())
    }

    @Test
    fun maxMissesOfOneDropsOnFirstMiss() {
        assertTrue(KeepAlivePolicy(maxMisses = 1).onMiss())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroMaxMissesIsRejected() {
        KeepAlivePolicy(maxMisses = 0)
    }
}
