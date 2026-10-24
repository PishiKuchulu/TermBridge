package com.pishi.termbridge

import com.pishi.termbridge.security.ExportCrypto
import com.pishi.termbridge.security.ExportCryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExportCryptoTest {
    @Test
    fun roundTrip() {
        val blob = ExportCrypto.encrypt(
            "backup payload".toByteArray(),
            "correct-horse-battery".toCharArray()
        )
        assertArrayEquals(
            "backup payload".toByteArray(),
            ExportCrypto.decrypt(blob, "correct-horse-battery".toCharArray())
        )
    }

    @Test
    fun wrongPassphraseFails() {
        val blob = ExportCrypto.encrypt(
            "backup payload".toByteArray(),
            "correct-horse-battery".toCharArray()
        )
        try {
            ExportCrypto.decrypt(blob, "wrong-passphrase-here".toCharArray())
            fail("Expected ExportCryptoException")
        } catch (e: ExportCryptoException) {
            assertEquals("Wrong passphrase or corrupt backup file.", e.message)
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val blob = ExportCrypto.encrypt(
            "backup payload".toByteArray(),
            "correct-horse-battery".toCharArray()
        )
        val tampered = blob.copy(
            ciphertext = blob.ciphertext.copyOf().also {
                it[0] = (it[0].toInt() xor 0xFF).toByte()
            }
        )
        try {
            ExportCrypto.decrypt(tampered, "correct-horse-battery".toCharArray())
            fail("Expected ExportCryptoException")
        } catch (e: ExportCryptoException) {
            // Expected: GCM authentication failure.
        }
    }

    @Test
    fun saltAndIvDifferAcrossEncryptions() {
        val first = ExportCrypto.encrypt(
            "same payload".toByteArray(),
            "correct-horse-battery".toCharArray()
        )
        val second = ExportCrypto.encrypt(
            "same payload".toByteArray(),
            "correct-horse-battery".toCharArray()
        )
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun passphraseIsWipedAfterUse() {
        val passphrase = "correct-horse-battery".toCharArray()
        ExportCrypto.encrypt("payload".toByteArray(), passphrase)
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun parametersAreDocumentedValues() {
        assertEquals("PBKDF2WithHmacSHA256", ExportCrypto.KDF_ALGORITHM)
        assertEquals(600_000, ExportCrypto.ITERATIONS)
        assertEquals(256, ExportCrypto.KEY_BITS)
        assertEquals(16, ExportCrypto.SALT_BYTES)
        assertEquals(12, ExportCrypto.IV_BYTES)
    }
}
