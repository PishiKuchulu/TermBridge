package com.pishi.termbridge.security

/**
 * AAD context strings binding an encrypted blob to the row
 * that owns it (item R-02). The context is never stored
 * inside the blob; it is derived from the row's identity at
 * encrypt/decrypt time, so a blob copied into another row —
 * by a bug, a tampered backup, or a database edit — fails
 * GCM authentication instead of decrypting.
 */
object BlobContexts {
    fun hostCredential(hostId: Long): String = "termbridge/v2/host-credential/$hostId"

    fun keychainEntry(entryId: Long): String = "termbridge/v2/keychain/$entryId"

    fun envValue(hostId: Long, name: String): String = "termbridge/v2/env/$hostId/$name"
}

/**
 * Encrypt/decrypt boundary for stored secrets. Implementations
 * never return a key, never log a value, and never store a
 * plaintext copy.
 *
 * Blob format v2 (item R-02): one version byte (0x02), then
 * IV || ciphertext, with the GCM tag computed over the
 * caller's context as additional authenticated data (see
 * [BlobContexts]). Blobs written before R-02 have no version
 * byte and no AAD; [decrypt] still reads them (the legacy
 * path ignores the context) so repository load paths can
 * migrate them opportunistically via [isLegacyBlob]. One
 * corner (audit #6 TB-009): a legacy blob whose random IV
 * starts with 0x02 is indistinguishable from v2 by the header
 * byte, so [isLegacyBlob] misses it and it never migrates —
 * but [decrypt] falls back to the legacy layout when the v2
 * authentication fails, so it stays readable.
 *
 * Production implementation: [AndroidKeystoreSecretCrypto].
 * JVM tests use an in-memory test double (test sources only,
 * never shipped) so repository logic can be tested without
 * Android.
 */
interface SecretCrypto {
    /** Encrypt [plaintext] bound to [context]; returns an
     *  opaque v2 blob. */
    fun encrypt(plaintext: ByteArray, context: String): ByteArray

    /** Decrypt a blob produced by [encrypt], or a legacy
     *  pre-v2 blob. Throws on tampering, on a context
     *  mismatch, or on a lost/invalidated key — callers must
     *  surface that as "stored credential cannot be decrypted,
     *  please re-enter it", never as a silent re-encryption. */
    fun decrypt(blob: ByteArray, context: String): ByteArray

    /** True when [blob] predates the v2 format and should be
     *  re-encrypted after a successful decrypt. */
    fun isLegacyBlob(blob: ByteArray): Boolean =
        blob.isEmpty() || blob[0] != BLOB_VERSION_2

    /** Delete the underlying key, if the implementation supports it.
     *  Existing blobs become undecryptable after this — use only
     *  when the user re-saves a credential (prompt 04 lifecycle doc). */
    fun deleteKey()

    companion object {
        const val BLOB_VERSION_2: Byte = 2
    }
}
