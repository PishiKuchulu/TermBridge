package com.pishi.termbridge.domain

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC Authentication (security type 2) — prompt 13.
 *
 * Wire facts (RFB protocol document, VNC Authentication section):
 * - The server sends a random 16-byte challenge.
 * - The client encrypts it with DES, using the password as the
 *   key. A password longer than the 64 bits DES needs is simply
 *   truncated; a shorter one is padded with zero bytes.
 * - Each 8 bytes of the challenge is encrypted independently
 *   (ECB mode); the 16-byte response is sent back.
 * - Bit order: "The lowest bit of each byte is considered the
 *   first bit and the highest discarded as parity. This is the
 *   reverse order of most implementations of DES so the key may
 *   require adjustment." That adjustment is what
 *   [reverseBits] does: every password byte has its bits
 *   reversed before it is used as the DES key. DES itself ignores
 *   the parity bits, so the reversal is the whole adjustment.
 *
 * Security status, stated plainly because it decides where this
 * auth may be used: DES with an 8-character password is
 * cryptographically weak by design (RFC 6143 says so outright:
 * "not intended for use on untrusted networks"). VNC
 * Authentication must therefore only be used on a trusted
 * network or tunnelled (for example over this app's own SSH
 * local forwarding, prompt 10). It is implemented because it is
 * the authentication real VNC servers offer — not because it is
 * strong. No stronger standard auth (VeNCrypt/TLS/X.509) is
 * implemented in this phase; a server offering only those fails
 * cleanly instead of silently downgrading to None or VNC Auth.
 *
 * Password handling: the caller passes the password as a
 * CharArray; this object never stores it. [deriveKey] returns a
 * fresh ByteArray the caller must wipe after use, and
 * [RfbConnection] wipes both its key copy and the caller's
 * password once authentication is done. Nothing here logs.
 */
object RfbAuth {

    /** DES key size: exactly 8 bytes. */
    const val KEY_BYTES = 8

    /** Challenge and response size: exactly 16 bytes. */
    const val CHALLENGE_BYTES = 16

    /**
     * Reverse the bits of one byte. 0x01 -> 0x80, 0xAB -> 0xD5.
     * Pure function, covered by a unit test with hand-computed
     * values (not by the DES primitive, so a reversal bug cannot
     * hide behind a passing round-trip test).
     */
    fun reverseBits(byte: Byte): Byte {
        var value = byte.toInt() and 0xFF
        var reversed = 0
        repeat(8) {
            reversed = (reversed shl 1) or (value and 1)
            value = value ushr 1
        }
        return reversed.toByte()
    }

    /**
     * Derive the 8-byte DES key from password bytes: truncate to
     * 8 bytes, or zero-pad on the right, then reverse the bits
     * of every byte. The returned array is fresh and must be
     * wiped by the caller.
     */
    fun deriveKey(passwordBytes: ByteArray): ByteArray {
        val key = ByteArray(KEY_BYTES)
        val count = minOf(passwordBytes.size, KEY_BYTES)
        for (i in 0 until count) {
            key[i] = reverseBits(passwordBytes[i])
        }
        return key
    }

    /**
     * Encrypt one 16-byte challenge with the derived key using
     * DES/ECB/NoPadding (two independent 8-byte blocks). The
     * challenge must be exactly 16 bytes; anything else is a
     * protocol error, not a padded guess.
     */
    fun encryptChallenge(challenge: ByteArray, key: ByteArray): ByteArray {
        require(challenge.size == CHALLENGE_BYTES) {
            "Challenge must be 16 bytes."
        }
        require(key.size == KEY_BYTES) { "DES key must be 8 bytes." }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    /** DES decrypt, for tests only: proves encrypt/decrypt are
     *  inverses on the same key. Production code never decrypts
     *  a challenge (the server never sends one back). */
    fun decryptForTest(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(ciphertext.size == CHALLENGE_BYTES) {
            "Ciphertext must be 16 bytes."
        }
        require(key.size == KEY_BYTES) { "DES key must be 8 bytes." }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(ciphertext)
    }

    /** Overwrite an array in place. Call on every key/response
     *  copy as soon as it is no longer needed. */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    /** Overwrite a password in place. */
    fun wipe(password: CharArray?) {
        password?.fill('\u0000')
    }
}
