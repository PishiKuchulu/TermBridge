package com.pishi.termbridge.security

import com.pishi.termbridge.domain.wipe
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A passphrase/KDF failure. The message deliberately does not
 *  distinguish "wrong passphrase" from "corrupt file": GCM
 *  authentication fails identically for both, and distinguishing
 *  them would only help an attacker probing the file. */
class ExportCryptoException(message: String) : Exception(message)

/** One encrypted export payload: salt + IV + GCM ciphertext. The
 *  ciphertext includes the 128-bit GCM tag (appended by the
 *  cipher), so tampering is detected on decrypt, not silently
 *  accepted. Nothing here is Keystore-bound: the key is derived
 *  from the user's passphrase, which is the whole point — an
 *  export must decrypt on a different device (see
 *  docs/IMPORT_EXPORT.md). */
data class ExportCiphertext(
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray
)

/**
 * Passphrase-based export encryption. Reviewed construction, no
 * custom cryptography:
 *
 * - KDF: PBKDF2WithHmacSHA256, 600,000 iterations, 256-bit key.
 *   600k is the OWASP PBKDF2-HMAC-SHA256 recommendation; it costs
 *   roughly a second on a phone, which is acceptable for an
 *   explicit, infrequent export/import action and is not a
 *   per-connection cost.
 * - Cipher: AES-256-GCM, fresh 16-byte salt and 12-byte IV per
 *   export, 128-bit tag. Salt/IV reuse across exports cannot
 *   happen because both are random per call.
 * - The passphrase CharArray passed in is CONSUMED: it is wiped
 *   in a finally block by every method here. Callers must not
 *   reuse it afterwards.
 *
 * PBKDF2WithHmacSHA256 is available on Android API 26+ (the
 * app's minSdk) and on the JVM, so this class is unit-testable
 * without Android — unlike the Keystore implementation, which
 * stays a separate, device-bound path for on-device secrets.
 */
object ExportCrypto {
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 600_000
    const val KEY_BITS = 256
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ExportCiphertext {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        return encryptWith(plaintext, passphrase, salt, iv)
    }

    /** Deterministic-salt/IV variant, for tests only in practice:
     *  production callers use [encrypt], which randomises both. */
    fun encryptWith(
        plaintext: ByteArray,
        passphrase: CharArray,
        salt: ByteArray,
        iv: ByteArray
    ): ExportCiphertext {
        require(salt.size == SALT_BYTES) { "Salt must be $SALT_BYTES bytes." }
        require(iv.size == IV_BYTES) { "IV must be $IV_BYTES bytes." }
        val key = deriveKey(passphrase, salt)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            return ExportCiphertext(salt.copyOf(), iv.copyOf(), cipher.doFinal(plaintext))
        } finally {
            key.encoded?.wipe()
        }
    }

    fun decrypt(blob: ExportCiphertext, passphrase: CharArray): ByteArray {
        require(blob.salt.size == SALT_BYTES) { "Salt must be $SALT_BYTES bytes." }
        require(blob.iv.size == IV_BYTES) { "IV must be $IV_BYTES bytes." }
        val key = deriveKey(passphrase, blob.salt)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
            }
            return try {
                cipher.doFinal(blob.ciphertext)
            } catch (e: Exception) {
                throw ExportCryptoException(
                    "Wrong passphrase or corrupt backup file."
                )
            }
        } finally {
            key.encoded?.wipe()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES").also { keyBytes?.wipe() }
        } finally {
            spec.clearPassword()
            passphrase.wipe()
        }
    }
}
