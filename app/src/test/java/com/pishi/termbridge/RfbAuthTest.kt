package com.pishi.termbridge

import com.pishi.termbridge.domain.RfbAuth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VNC Authentication unit tests — prompt 13. NOT RUN (sandbox
 * Gradle limits, same as all other prompts).
 *
 * What each test pins, and what it does NOT prove:
 * - Bit reversal is tested with hand-computed values, so a
 *   reversal bug cannot hide behind a passing round-trip test.
 * - The DES primitive is tested with the published FIPS
 *   test vector (key 133457799BBCDFF1, plaintext
 *   0123456789ABCDEF -> 85E813540F0AB405), so the JCE DES
 *   usage itself is validated, not just self-consistent.
 * - Round-trip and determinism tests validate the assembly.
 * - NONE of these proves a real VNC server accepts the
 *   response: that is the fixture item (controlled server +
 *   device run), and the scripted-peer test in
 *   RfbConnectionTest only proves the handshake sequencing.
 */
class RfbAuthTest {

    @Test
    fun `bit reversal matches hand-computed values`() {
        assertEquals(0x80.toByte(), RfbAuth.reverseBits(0x01))
        assertEquals(0x01.toByte(), RfbAuth.reverseBits(0x80.toByte()))
        assertEquals(0xD5.toByte(), RfbAuth.reverseBits(0xAB.toByte()))
        assertEquals(0x00.toByte(), RfbAuth.reverseBits(0x00))
        assertEquals(0xFF.toByte(), RfbAuth.reverseBits(0xFF.toByte()))
        assertEquals(0x48.toByte(), RfbAuth.reverseBits(0x12))
    }

    @Test
    fun `key derivation truncates pads and reverses`() {
        // "password": p=0x70->0x0E, a=0x61->0x86, s=0x73->0xCE,
        // s->0xCE, w=0x77->0xEE, o=0x6F->0xF6, r=0x72->0x4E,
        // d=0x64->0x26. Hand-computed, not copied from code.
        val key = RfbAuth.deriveKey("password".toByteArray(Charsets.UTF_8))
        assertArrayEquals(
            byteArrayOf(
                0x0E, 0x86.toByte(), 0xCE.toByte(), 0xCE.toByte(),
                0xEE.toByte(), 0xF6.toByte(), 0x4E, 0x26
            ),
            key
        )
        // Short password: zero-padded on the right.
        val short = RfbAuth.deriveKey("ab".toByteArray(Charsets.UTF_8))
        assertArrayEquals(
            byteArrayOf(0x86.toByte(), 0x46, 0, 0, 0, 0, 0, 0),
            short
        )
        // Long password: truncated to the first 8 bytes.
        val long = RfbAuth.deriveKey("password123".toByteArray(Charsets.UTF_8))
        assertArrayEquals(key, long)
    }

    @Test
    fun `DES primitive matches the FIPS test vector`() {
        // FIPS 46-3 example: key 133457799BBCDFF1, plaintext
        // 0123456789ABCDEF -> ciphertext 85E813540F0AB405.
        // encryptChallenge needs 16 bytes (two DES blocks), so
        // the vector is applied twice and both halves checked.
        val key = byteArrayOf(
            0x13, 0x34, 0x57, 0x79,
            0x9B.toByte(), 0xBC.toByte(), 0xDF.toByte(), 0xF1.toByte()
        )
        val block = byteArrayOf(
            0x01, 0x23, 0x45, 0x67,
            0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()
        )
        val expected = byteArrayOf(
            0x85.toByte(), 0xE8.toByte(), 0x13, 0x54,
            0x0F, 0x0A, 0xB4.toByte(), 0x05
        )
        val challenge = block + block
        val ciphertext = RfbAuth.encryptChallenge(challenge, key)
        assertArrayEquals(expected + expected, ciphertext)
    }

    @Test
    fun `challenge encrypt decrypt round-trips`() {
        val key = RfbAuth.deriveKey("secret".toByteArray(Charsets.UTF_8))
        val challenge = ByteArray(16) { it.toByte() }
        val ciphertext = RfbAuth.encryptChallenge(challenge, key)
        assertArrayEquals(challenge, RfbAuth.decryptForTest(ciphertext, key))
    }

    @Test
    fun `same inputs give the same response`() {
        val key = RfbAuth.deriveKey("secret".toByteArray(Charsets.UTF_8))
        val challenge = ByteArray(16) { (it * 7).toByte() }
        assertArrayEquals(
            RfbAuth.encryptChallenge(challenge, key),
            RfbAuth.encryptChallenge(challenge, key)
        )
    }

    @Test
    fun `wipe zeroes the array`() {
        val bytes = byteArrayOf(1, 2, 3)
        RfbAuth.wipe(bytes)
        assertArrayEquals(byteArrayOf(0, 0, 0), bytes)
        val password = "secret".toCharArray()
        RfbAuth.wipe(password)
        assertArrayEquals(CharArray(6), password)
    }
}
