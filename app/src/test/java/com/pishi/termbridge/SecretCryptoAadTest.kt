package com.pishi.termbridge

import com.pishi.termbridge.security.BlobContexts
import com.pishi.termbridge.security.SecretCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Item R-02: Keystore blobs are bound to their owner row via
 * GCM additional authenticated data. A blob copied into
 * another row must not decrypt; pre-R-02 blobs (no version
 * byte, no AAD) must still decrypt exactly once through the
 * legacy path so repositories can migrate them.
 */
class SecretCryptoAadTest {

    private val crypto: SecretCrypto = FakeSecretCrypto()
    private val secret = "s3cret-value".toByteArray(Charsets.UTF_8)

    @Test
    fun roundTripWithMatchingContext() {
        val blob = crypto.encrypt(secret, BlobContexts.hostCredential(7))
        assertArrayEquals(secret, crypto.decrypt(blob, BlobContexts.hostCredential(7)))
    }

    @Test
    fun newBlobsCarryTheVersionByteAndAreNotLegacy() {
        val blob = crypto.encrypt(secret, BlobContexts.keychainEntry(3))
        assertEquals(2, blob[0].toInt())
        assertFalse(crypto.isLegacyBlob(blob))
    }

    @Test
    fun blobCopiedToAnotherRowDoesNotDecrypt() {
        val blob = crypto.encrypt(secret, BlobContexts.hostCredential(7))
        try {
            crypto.decrypt(blob, BlobContexts.hostCredential(8))
            fail("A transplanted blob must fail authentication.")
        } catch (expected: Exception) {
            // AEAD tag mismatch — the binding did its job.
        }
    }

    @Test
    fun blobFromAnotherPurposeDoesNotDecrypt() {
        val blob = crypto.encrypt(secret, BlobContexts.envValue(7, "TOKEN"))
        try {
            crypto.decrypt(blob, BlobContexts.hostCredential(7))
            fail("A blob from another purpose must fail authentication.")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun legacyBlobStillDecrypts() {
        val legacy = (crypto as FakeSecretCrypto).legacyEncrypt(secret)
        assertTrue(crypto.isLegacyBlob(legacy))
        // The legacy path ignores the context (there was none).
        assertArrayEquals(secret, crypto.decrypt(legacy, BlobContexts.hostCredential(7)))
    }
}
