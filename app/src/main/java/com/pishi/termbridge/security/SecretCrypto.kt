package com.pishi.termbridge.security

/**
 * Encrypt/decrypt boundary for stored secrets. Implementations
 * never return a key, never log a value, and never store a
 * plaintext copy. The encrypted blob format is implementation
 * detail — callers treat it as opaque bytes.
 *
 * Production implementation: [AndroidKeystoreSecretCrypto].
 * JVM tests use an in-memory test double (test sources only, never
 * shipped) so repository logic can be tested without Android.
 */
interface SecretCrypto {
    /** Encrypt [plaintext] and return an opaque blob. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Decrypt a blob produced by [encrypt]. Throws on tampering or
     *  on a lost/invalidated key — callers must surface that as
     *  "stored credential cannot be decrypted, please re-enter it",
     *  never as a silent re-encryption. */
    fun decrypt(blob: ByteArray): ByteArray

    /** Delete the underlying key, if the implementation supports it.
     *  Existing blobs become undecryptable after this — use only
     *  when the user re-saves a credential (prompt 04 lifecycle doc). */
    fun deleteKey()
}
