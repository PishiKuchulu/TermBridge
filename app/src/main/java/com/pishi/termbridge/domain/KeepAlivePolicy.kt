package com.pishi.termbridge.domain

/**
 * Keep-alive failure policy (item T-14).
 *
 * sshj 0.40's own keep-alive has an interval but no
 * max-unanswered count, so the app runs its own probe loop in
 * the transport: every interval it sends a
 * `keepalive@openssh.com` global request and waits for any
 * reply. This class is the pure decision part — how many
 * consecutive misses end the connection — so the policy is
 * unit-tested without a socket.
 */
class KeepAlivePolicy(val maxMisses: Int) {
    init {
        require(maxMisses >= 1) { "maxMisses must be at least 1" }
    }

    var consecutiveMisses: Int = 0
        private set

    /** A reply arrived: the failure streak resets. */
    fun onReply() {
        consecutiveMisses = 0
    }

    /**
     * A probe went unanswered (timeout or error).
     * Returns true when the connection should be dropped.
     */
    fun onMiss(): Boolean {
        consecutiveMisses++
        return consecutiveMisses >= maxMisses
    }
}
