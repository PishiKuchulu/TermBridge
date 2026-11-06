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
 * - Item R-02: new blobs are v2 — a 0x02 version byte, then
 *   IV || ciphertext, with the caller's context string bound
 *   as GCM additional authenticated data (see BlobContexts).
 *   Decryption of a blob whose first byte is 0x02 uses the
 *   AAD path; anything else is read as a legacy v1 blob
 *   (IV || ciphertext, no AAD) so old rows keep working and
 *   can be migrated. Known caveat: a legacy blob whose random
 *   IV happens to start with 0x02 (1 in 256) is misread as v2
 *   and fails authentication — the documented recovery is
 *   re-entering that one credential; no other row is affected.
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

        /**
         * Process-wide lock for key creation/deletion (review #2
         * F-04). Two instances exist (host + environment
         * repositories); without one shared lock two first-use
         * encryptions could both see "no key", both generate under
         * the same alias, and the later key would orphan blobs the
         * earlier one had just written.
         */
        private val keyLock = Any()
    }

    /**
     * Look the key up WITHOUT ever creating it. Decryption must
     * never mint a replacement key (review #2 F-04): a transient or
     * lost-key condition on a read would otherwise silently destroy
     * every stored credential by installing a fresh key under the
     * same alias. A missing key is a hard failure the caller
     * surfaces as "re-enter the credential".
     */
    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw java.security.UnrecoverableKeyException(
                "The stored-credential key is not available."
            )
    }

    private fun getOrCreateKey(): SecretKey {
        synchronized(keyLock) {
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
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(spec)
            return generator.generateKey()
        }
    }

    override fun encrypt(plaintext: ByteArray, context: String): ByteArray {
        // AndroidKeyStore with setRandomizedEncryptionRequired(true)
        // rejects caller-provided IVs for encryption
        // ("Caller-provided IV not permitted", CI run #11): the IV
        // must come from the Keystore itself.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(context.toByteArray(Charsets.UTF_8))
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(SecretCrypto.BLOB_VERSION_2) + iv + ciphertext
    }

    override fun decrypt(blob: ByteArray, context: String): ByteArray {
        return if (isLegacyBlob(blob)) {
            decryptLegacy(blob)
        } else {
            require(blob.size > 1 + IV_BYTES) { "Encrypted blob is too short." }
            val iv = blob.copyOfRange(1, 1 + IV_BYTES)
            val ciphertext = blob.copyOfRange(1 + IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(context.toByteArray(Charsets.UTF_8))
            }
            try {
                cipher.doFinal(ciphertext)
            } catch (e: javax.crypto.AEADBadTagException) {
                // Audit #6 TB-009: a legacy (v1) blob whose random
                // IV happens to start with 0x02 is routed to the v2
                // path by the first-byte check and could never
                // authenticate there — the credential was silently
                // lost (1 in 256 legacy rows). Fall back to the
                // legacy layout: it is AES-GCM too, with a
                // different IV/AAD layout, so a genuine v2 blob
                // still fails both paths and nothing is weakened.
                decryptLegacy(blob)
            }
        }
    }

    /** Pre-R-02 layout: IV || ciphertext, no AAD. Read-only path;
     *  repositories re-encrypt to v2 after a successful read. */
    private fun decryptLegacy(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "Encrypted blob is too short." }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        synchronized(keyLock) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }
}
