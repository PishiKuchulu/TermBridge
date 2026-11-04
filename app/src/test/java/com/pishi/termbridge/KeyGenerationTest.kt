package com.pishi.termbridge

import com.pishi.termbridge.domain.KeyFileFormat
import com.pishi.termbridge.domain.KeyFileParse
import com.pishi.termbridge.domain.KeyFileParser
import com.pishi.termbridge.security.KeyGeneration
import com.pishi.termbridge.security.KeyGenerationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Items T-01 + F-01: generated keys must (a) classify
 * correctly through the file parser, (b) load through sshj —
 * describe() IS the load proof, it throws otherwise — and
 * (c) carry a public half and fingerprint derived from the
 * loaded key, not from the generator's own bookkeeping.
 */
class KeyGenerationTest {

    @Test
    fun generatedRsaLoadsAndClassifiesAsPkcs8() {
        val key = KeyGeneration.generateRsa(2048)
        val parsed = KeyFileParser.parse(key.pemText.toByteArray())
        val parsedKey = parsed as? KeyFileParse.PrivateKey
            ?: throw AssertionError("Generated RSA key did not classify: $parsed")
        assertEquals(KeyFileFormat.PKCS8, parsedKey.format)
        assertFalse(parsedKey.encrypted)
        assertTrue(key.publicKeyText.startsWith("ssh-rsa "))
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        // The stored description matches a fresh describe of
        // the same PEM (no generator-only state).
        val redescribed = KeyGeneration.describe(key.pemText, null)
        assertEquals(key.fingerprint, redescribed.fingerprint)
        assertEquals(key.publicKeyText, redescribed.publicKeyText)
    }

    @Test
    fun generatedEd25519LoadsAndClassifiesAsOpenSsh() {
        val key = KeyGeneration.generateEd25519()
        val parsed = KeyFileParser.parse(key.pemText.toByteArray())
        val parsedKey = parsed as? KeyFileParse.PrivateKey
            ?: throw AssertionError("Generated Ed25519 key did not classify: $parsed")
        assertEquals(KeyFileFormat.OPENSSH, parsedKey.format)
        assertFalse(parsedKey.encrypted)
        assertTrue(key.publicKeyText.startsWith("ssh-ed25519 "))
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        val redescribed = KeyGeneration.describe(key.pemText, null)
        assertEquals(key.fingerprint, redescribed.fingerprint)
    }

    @Test
    fun generatedKeysAreUnique() {
        val a = KeyGeneration.generateEd25519()
        val b = KeyGeneration.generateEd25519()
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.pemText, b.pemText)
    }

    @Test
    fun describeRejectsGarbageWithSafeMessage() {
        try {
            KeyGeneration.describe("this is not a key", null)
            fail("Expected a KeyGenerationException.")
        } catch (expected: KeyGenerationException) {
            assertFalse(expected.message!!.contains("not a key"))
        }
    }

    @Test
    fun describeWipesPassphraseOnFailure() {
        val passphrase = "wrong-passphrase".toCharArray()
        try {
            KeyGeneration.describe("garbage", passphrase)
            fail("Expected a KeyGenerationException.")
        } catch (expected: KeyGenerationException) {
            assertTrue(passphrase.all { it == '\u0000' })
        }
    }
}
