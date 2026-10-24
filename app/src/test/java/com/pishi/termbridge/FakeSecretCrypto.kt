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
 */
class FakeSecretCrypto : SecretCrypto {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 12) { "Blob too short." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, blob.copyOfRange(0, 12))
            )
        }
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    override fun deleteKey() {
        // In-memory key only; nothing persistent to delete in tests.
    }
}
