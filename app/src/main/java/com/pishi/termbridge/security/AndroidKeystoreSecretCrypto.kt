package com.pishi.termbridge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Platform Keystore + AES-256-GCM secret protection.
 *
 * - Key alias: "termbridge_master_key", generated in AndroidKeyStore
 *   on first use, never exported, never written to disk by the app.
 * - No user-authentication binding (setUserAuthenticationRequired
 *   is false): a biometric/passcode change must not lock the user
 *   out of their own hosts. The device lock screen is still the
 *   boundary — a fully compromised device is out of scope (see
 *   docs/THREAT_MODEL.md).
 * - Fresh 12-byte IV per encryption; blob layout is IV || ciphertext
 *   (GCM tag included by the cipher). IV reuse with the same key
 *   would break GCM, so the IV is never reused and never fixed.
 * - The deprecated androidx.security wrapper is deliberately NOT
 *   used (see ADR-05); this is direct platform Keystore use.
 *
 * Key lifecycle, backup, and migration limits are documented in
 * docs/SECURITY_KEY_LIFECYCLE.md — read it before changing this file.
 */
class AndroidKeystoreSecretCrypto : SecretCrypto {
    companion object {
        const val KEY_ALIAS = "termbridge_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec)
        }.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        // AndroidKeyStore with setRandomizedEncryptionRequired(true)
        // rejects caller-provided IVs for encryption
        // ("Caller-provided IV not permitted", CI run #11): the IV
        // must come from the Keystore itself. Blob layout is
        // unchanged (IV || ciphertext), so decrypt is untouched.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "Encrypted blob is too short." }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }
}
