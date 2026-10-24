package com.pishi.termbridge.transport

import com.pishi.termbridge.domain.HostKeyTrustVerifier
import com.pishi.termbridge.domain.HostKeyVerification

/** A trust decision that is waiting on, or has just received, a user answer. */
data class PendingTrust(
    val endpoint: String,
    val fingerprint: String,
    val keyType: String,
    val verification: HostKeyVerification,
    val storedFingerprint: String? = null
)

sealed class VerificationOutcome {
    /** Accept, with a trust record to persist afterwards (null when
     *  the fingerprint already matched a stored record). */
    data class Accept(val pendingTrust: PendingTrust?) : VerificationOutcome()

    object Reject : VerificationOutcome()
}

/**
 * Pure host-key policy — no sshj, no Android, no I/O. The sshj
 * verifier delegates to this class, so the policy itself is fully
 * unit-testable. Rules:
 * - stored == presented -> accept, nothing to persist;
 * - no stored record -> ask the user (UNKNOWN); accept persists;
 * - stored != presented -> ask the user (CHANGED); accept overwrites
 *   the stored record only after a successful connection (the caller
 *   persists pendingTrust after connect, never before, so a failed
 *   connection cannot silently replace a trusted fingerprint).
 * There is no "accept all" path: both unknown and changed cases
 * require an explicit user decision, and a missing decision is a
 * rejection.
 */
object HostKeyVerificationPolicy {
    fun verify(
        storedFingerprint: String?,
        presentedFingerprint: String,
        keyType: String,
        endpoint: String,
        decideUnknown: (PendingTrust) -> Boolean,
        decideChanged: (PendingTrust) -> Boolean
    ): VerificationOutcome {
        return when (HostKeyTrustVerifier.verify(storedFingerprint, presentedFingerprint)) {
            HostKeyVerification.MATCH -> VerificationOutcome.Accept(null)
            HostKeyVerification.UNKNOWN -> {
                val pending = PendingTrust(
                    endpoint, presentedFingerprint, keyType, HostKeyVerification.UNKNOWN
                )
                if (decideUnknown(pending)) {
                    VerificationOutcome.Accept(pending)
                } else {
                    VerificationOutcome.Reject
                }
            }
            HostKeyVerification.CHANGED -> {
                val pending = PendingTrust(
                    endpoint,
                    presentedFingerprint,
                    keyType,
                    HostKeyVerification.CHANGED,
                    storedFingerprint
                )
                if (decideChanged(pending)) {
                    VerificationOutcome.Accept(pending)
                } else {
                    VerificationOutcome.Reject
                }
            }
        }
    }
}
