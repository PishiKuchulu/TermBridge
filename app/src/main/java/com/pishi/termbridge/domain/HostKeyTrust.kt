package com.pishi.termbridge.domain

/** Result of comparing a presented host key against the trust store. */
enum class HostKeyVerification {
    /** No fingerprint is stored for this endpoint yet. */
    UNKNOWN,

    /** The presented fingerprint matches the stored one. */
    MATCH,

    /** A different fingerprint is stored — a possible attack, a
     *  reinstalled server, or a changed key. Never auto-accept. */
    CHANGED
}

/**
 * Pure host-key comparison. Fingerprint computation itself belongs
 * to the SSH transport (prompt 05, via sshj); this class only
 * decides what a stored-vs-presented pair means, so the rule is
 * testable without any network or crypto library.
 *
 * Fingerprints are stored as OpenSSH-style strings, e.g.
 * "SHA256:..." — the exact string the transport presents, compared
 * exactly. No fuzzy matching, no prefix matching.
 */
object HostKeyTrustVerifier {
    fun verify(storedFingerprint: String?, presentedFingerprint: String): HostKeyVerification {
        return when {
            storedFingerprint == null -> HostKeyVerification.UNKNOWN
            storedFingerprint == presentedFingerprint -> HostKeyVerification.MATCH
            else -> HostKeyVerification.CHANGED
        }
    }
}
