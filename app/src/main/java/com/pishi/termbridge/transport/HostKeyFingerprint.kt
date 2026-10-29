package com.pishi.termbridge.transport

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType

/**
 * OpenSSH-style SHA-256 host-key fingerprint: "SHA256:" followed by
 * the Base64 (no padding) of the SHA-256 digest of the key's SSH
 * WIRE-FORMAT blob (RFC 4253 section 6.6, e.g. `string "ssh-ed25519"`
 * + `string key`). This is exactly what `ssh-keygen -lf` prints, so a
 * user can compare the value in the trust dialog against the server.
 *
 * Review #2 F-02: an earlier version hashed `PublicKey.encoded`, which
 * is the X.509 SubjectPublicKeyInfo DER. That is a different byte
 * string, so the fingerprint never matched OpenSSH's and the trust
 * dialog could not be verified out of band.
 *
 * The SSH key-type label is display-only; the fingerprint is the
 * authoritative identity. Labels are sshj's real wire names
 * (ssh-rsa, ecdsa-sha2-nistp256, ssh-ed25519, ...).
 */
object HostKeyFingerprint {
    fun compute(publicKey: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val base64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$base64"
    }

    fun sshKeyType(publicKey: PublicKey): String {
        val type = KeyType.fromKey(publicKey)
        return if (type == KeyType.UNKNOWN) publicKey.algorithm else type.toString()
    }
}
