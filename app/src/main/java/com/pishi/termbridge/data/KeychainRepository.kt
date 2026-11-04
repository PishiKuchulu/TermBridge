package com.pishi.termbridge.data

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.KeychainEntry
import com.pishi.termbridge.domain.KeychainValidator
import com.pishi.termbridge.security.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A keychain save/delete failure. Messages never contain
 *  credential material. */
class KeychainRepositoryException(message: String) : Exception(message)

/**
 * Keychain repository (item T-01). Rules enforced here, not in
 * the UI:
 *
 * - Labels are validated (KeychainValidator) and unique —
 *   checked before write, with the table's unique index as the
 *   second net.
 * - Material is encrypted before it reaches the database via
 *   the shared credential-encryption path, and plaintext
 *   buffers are wiped (see CredentialEncryption.kt). A key's
 *   passphrase is never persisted.
 * - The entry kind and the credential type must agree.
 * - Editing without a new credential keeps the existing blob.
 * - An entry still referenced by hosts cannot be deleted
 *   unless the caller explicitly forces it, which first
 *   detaches those hosts (they fall back to having no
 *   credential until the user configures one) — a dangling
 *   reference is never left behind.
 */
class KeychainRepository(
    private val keychainDao: KeychainDao,
    private val hostDao: HostDao,
    private val secretCrypto: SecretCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun observeEntries(): Flow<List<KeychainEntry>> =
        keychainDao.getAll().map { rows -> rows.map { it.toEntry() } }

    suspend fun getEntry(id: Long): KeychainEntry? =
        keychainDao.getById(id)?.toEntry()

    suspend fun getByLabel(label: String): KeychainEntry? =
        keychainDao.getByLabel(label.trim())?.toEntry()

    /**
     * Save a new entry (id == 0) or update an existing one.
     * [publicKeyText] / [fingerprint] are stored as given —
     * deriving them from key material is the import layer's job
     * (sshj load + HostKeyFingerprint), never guessed here.
     */
    suspend fun saveEntry(
        entry: KeychainEntry,
        credential: CredentialInput?,
        publicKeyText: String? = null,
        fingerprint: String? = null
    ): KeychainEntry {
        val isNew = entry.id == 0L
        val existing = if (isNew) null else keychainDao.getById(entry.id)
        if (!isNew && existing == null) {
            throw KeychainRepositoryException("Keychain entry not found.")
        }
        val label = entry.label.trim()
        val labelOwner = keychainDao.getByLabel(label)
        val uniquenessIssue = if (labelOwner != null && labelOwner.id != entry.id) {
            listOf(
                com.pishi.termbridge.domain.ValidationIssue(
                    "label",
                    "Another entry already uses this label."
                )
            )
        } else {
            emptyList()
        }
        val issues = KeychainValidator.validateLabel(label) +
            KeychainValidator.validateCredential(
                entry.kind,
                credential,
                hasUsableMaterial = existing != null
            ) + uniquenessIssue
        if (issues.isNotEmpty()) {
            throw KeychainRepositoryException(
                issues.joinToString("; ") { it.message }
            )
        }
        // Item R-02: an edit encrypts directly under the
        // entry's AAD context. A NEW entry has no id yet, so
        // its material is first encrypted under a placeholder
        // context and re-bound to the real one right after the
        // insert (phase 2 below) — the placeholder blob never
        // leaves this function in a readable state.
        val blob: ByteArray = when {
            credential != null && !isNew ->
                encryptCredentialToBlob(
                    secretCrypto, credential,
                    com.pishi.termbridge.security.BlobContexts.keychainEntry(entry.id)
                )
            credential != null ->
                encryptCredentialToBlob(
                    secretCrypto, credential,
                    com.pishi.termbridge.security.BlobContexts.keychainEntry(0L)
                )
            else -> existing!!.materialBlob
        }
        val now = clock()
        val entity = KeychainEntryEntity(
            id = if (isNew) 0L else entry.id,
            label = label,
            kind = entry.kind.name,
            materialBlob = blob,
            publicKeyText = publicKeyText ?: existing?.publicKeyText,
            fingerprint = fingerprint ?: existing?.fingerprint,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val savedId = if (isNew) keychainDao.insert(entity) else {
            keychainDao.update(entity)
            entry.id
        }
        if (isNew && credential != null) {
            // Phase 2 (item R-02): re-bind the material to the
            // real entry context now that the id exists.
            val rebound = reencryptBlob(
                secretCrypto, blob,
                com.pishi.termbridge.security.BlobContexts.keychainEntry(0L),
                com.pishi.termbridge.security.BlobContexts.keychainEntry(savedId)
            )
            keychainDao.update(entity.copy(id = savedId, materialBlob = rebound))
        }
        return entry.copy(
            id = savedId,
            label = label,
            publicKeyText = entity.publicKeyText,
            fingerprint = entity.fingerprint,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    /** Decrypt an entry's material. Returns null when the entry
     *  does not exist. Callers must wipe the returned bytes
     *  after use. A decryption failure surfaces as a generic
     *  message — the user must re-enter the credential. */
    suspend fun loadMaterial(id: Long): ByteArray? {
        val entry = keychainDao.getById(id) ?: return null
        val blob = entry.materialBlob
        val context = com.pishi.termbridge.security.BlobContexts.keychainEntry(id)
        val plain = try {
            secretCrypto.decrypt(blob, context)
        } catch (e: Exception) {
            throw KeychainRepositoryException(
                "Stored credential cannot be decrypted. Please re-enter it."
            )
        }
        migrateLegacyBlob(secretCrypto, blob, plain, context) { upgraded ->
            keychainDao.update(entry.copy(materialBlob = upgraded))
        }
        return plain
    }

    /** How many hosts currently reference this entry. */
    suspend fun referencingHostCount(id: Long): Int =
        hostDao.countByKeychainEntryId(id)

    /**
     * Delete an entry. Refused while hosts reference it, unless
     * [force] — which detaches those hosts first (their
     * keychainEntryId is cleared; they keep no credential until
     * reconfigured).
     */
    suspend fun deleteEntry(id: Long, force: Boolean = false) {
        val count = hostDao.countByKeychainEntryId(id)
        if (count > 0 && !force) {
            throw KeychainRepositoryException(
                "This entry is used by $count host(s). " +
                    "Detach it from them first, or confirm forced deletion."
            )
        }
        if (count > 0) {
            hostDao.clearKeychainEntryId(id)
        }
        keychainDao.deleteById(id)
    }

    private fun KeychainEntryEntity.toEntry(): KeychainEntry = KeychainEntry(
        id = id,
        label = label,
        kind = AuthType.valueOf(kind),
        publicKeyText = publicKeyText,
        fingerprint = fingerprint,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
