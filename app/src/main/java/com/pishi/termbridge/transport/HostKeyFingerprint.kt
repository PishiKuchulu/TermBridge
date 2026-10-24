package com.pishi.termbridge.transport

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * OpenSSH-style SHA-256 host-key fingerprint: "SHA256:" followed by
 * the Base64 (no padding) of the SHA-256 digest of the key's
 * encoded form. This is the string shown to the user in the trust
 * dialog and stored in the trust table — the same format OpenSSH
 * prints, so a user can compare it against a server-side
 * `ssh-keygen -lf` output.
 *
 * The SSH key-type label is display-only; the fingerprint is the
 * authoritative identity. Algorithm mapping: RSA -> ssh-rsa,
 * EC -> ssh-ecdsa, EdDSA -> ssh-ed25519, DSA -> ssh-dss.
 */
object HostKeyFingerprint {
    fun compute(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val base64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$base64"
    }

    fun sshKeyType(publicKey: PublicKey): String = when (publicKey.algorithm) {
        "RSA" -> "ssh-rsa"
        "EC" -> "ssh-ecdsa"
        "EdDSA" -> "ssh-ed25519"
        "DSA" -> "ssh-dss"
        else -> publicKey.algorithm
    }
}
