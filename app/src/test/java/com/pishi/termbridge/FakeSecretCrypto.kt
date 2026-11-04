package com.pishi.termbridge

import com.pishi.termbridge.security.SecretCrypto
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * TEST-ONLY SecretCrypto double. It uses real AES-256-GCM, but the
 * key lives only in memory for the duration of the test process.
 * It exists so repository logic can be tested on the JVM without
 * Android Keystore — it is never shipped, never used in production
 * code, and must never be mistaken for the Keystore implementation.
 *
 * It mirrors the production blob FORMAT exactly (item R-02):
 * v2 = version byte || IV || ciphertext with the context as
 * AAD; the legacy v1 layout (IV || ciphertext, no AAD) is
 * still readable. [legacyEncrypt] exists so migration tests
 * can seed pre-R-02 rows.
 */
class FakeSecretCrypto : SecretCrypto {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray, context: String): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(context.toByteArray(Charsets.UTF_8))
        }
        return byteArrayOf(SecretCrypto.BLOB_VERSION_2) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(blob: ByteArray, context: String): ByteArray {
        return if (isLegacyBlob(blob)) {
            require(blob.size > 12) { "Blob too short." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, blob.copyOfRange(0, 12))
                )
            }
            cipher.doFinal(blob.copyOfRange(12, blob.size))
        } else {
            require(blob.size > 13) { "Blob too short." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, blob.copyOfRange(1, 13))
                )
                updateAAD(context.toByteArray(Charsets.UTF_8))
            }
            cipher.doFinal(blob.copyOfRange(13, blob.size))
        }
    }

    override fun deleteKey() {
        // In-memory key only; nothing persistent to delete in tests.
    }

    /** Encrypt in the pre-R-02 v1 layout (no version byte,
     *  no AAD), under THIS instance's key, so migration tests
     *  can seed legacy rows the owning repository can read. */
    fun legacyEncrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }
}
