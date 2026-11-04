package com.pishi.termbridge.data

import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.security.SecretCrypto

/**
 * The one credential-to-blob path, shared by HostRepository
 * (inline host credentials) and KeychainRepository (keychain
 * entries) so the two can never drift apart:
 *
 * - A password is encrypted from a UTF-8 byte copy that is
 *   wiped in a finally block; the caller's CharArray is wiped
 *   too (the CredentialInput contract).
 * - A private key's PEM (a String, unwipable — see
 *   CredentialInput) is encrypted as-is; its passphrase is
 *   NEVER persisted anywhere — it is wiped here and requested
 *   again at connection time.
 */
internal fun encryptCredentialToBlob(
    secretCrypto: SecretCrypto,
    credential: CredentialInput,
    context: String
): ByteArray {
    return when (credential) {
        is CredentialInput.Password -> {
            try {
                val bytes = String(credential.password).toByteArray(Charsets.UTF_8)
                try {
                    secretCrypto.encrypt(bytes, context)
                } finally {
                    bytes.wipe()
                }
            } finally {
                credential.password.wipe()
            }
        }
        is CredentialInput.PrivateKey -> {
            try {
                secretCrypto.encrypt(credential.keyPem.toByteArray(Charsets.UTF_8), context)
            } finally {
                credential.passphrase?.wipe()
            }
        }
    }
}

/**
 * Re-bind an already-decrypted blob to a new context (item
 * R-02): decrypt under [fromContext], re-encrypt under
 * [toContext]. Used by the two-phase save of brand-new rows
 * (whose id — and therefore context — exists only after the
 * insert) and by the opportunistic legacy-blob migration on
 * load paths. The intermediate plaintext is wiped here; the
 * caller receives a fresh blob and nothing else.
 */
internal fun reencryptBlob(
    secretCrypto: SecretCrypto,
    blob: ByteArray,
    fromContext: String,
    toContext: String
): ByteArray {
    val plain = secretCrypto.decrypt(blob, fromContext)
    try {
        return secretCrypto.encrypt(plain, toContext)
    } finally {
        plain.wipe()
    }
}

/**
 * Item R-02 opportunistic migration, shared by every load
 * path: when [blob] is a legacy (pre-v2) blob and its
 * plaintext was just decrypted successfully, re-store it in
 * the bound v2 format via [store]. Best-effort by design —
 * any failure is swallowed, because a failed re-store must
 * never fail the load that just succeeded; the next load
 * simply tries again. Must run BEFORE the caller wipes or
 * otherwise consumes [plain].
 */
internal suspend fun migrateLegacyBlob(
    secretCrypto: SecretCrypto,
    blob: ByteArray,
    plain: ByteArray,
    context: String,
    store: suspend (ByteArray) -> Unit
) {
    if (!secretCrypto.isLegacyBlob(blob)) return
    try {
        store(secretCrypto.encrypt(plain, context))
    } catch (_: Exception) {
        // Deliberately ignored — see KDoc.
    }
}
