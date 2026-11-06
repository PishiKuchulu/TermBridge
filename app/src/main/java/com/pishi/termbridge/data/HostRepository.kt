package com.pishi.termbridge.data

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import com.pishi.termbridge.domain.ValidationIssue
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.security.SecretCrypto
import com.pishi.termbridge.settings.TranscriptStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A save/validation failure. Messages never contain a credential. */
class HostRepositoryException(message: String) : Exception(message)

/**
 * Host + credential + trust repository. Rules enforced here, not in
 * the UI, so no screen can accidentally bypass them:
 *
 * - Profile shape + credential shape are validated before any write.
 * - A credential is encrypted before it reaches the database, and
 *   the plaintext buffer is wiped in a finally block.
 * - A passphrase for an encrypted private key is NEVER persisted:
 *   the key PEM itself is what is encrypted and stored; the
 *   passphrase is requested again at connection time (prompt 05).
 * - Editing without a new credential keeps the existing blob.
 * - Duplicating copies non-secret fields only; the duplicate has
 *   no credential until the user enters one. A keychain
 *   REFERENCE is copied: it points at the shared entry (the
 *   keychain's whole purpose is sharing), it is not a copy of
 *   the secret.
 * - Deleting a profile deletes its credential blob AND, since
 *   security fix 16 (M2), its trust record: the prompt-04 rule
 *   ("trust records survive deletion") was reversed because a
 *   delete-and-re-add cycle silently re-trusted a possibly
 *   compromised host without ever showing a fingerprint again.
 *   A re-added host now re-verifies with a dialog, always.
 * - Deletion is transactional when a [TransactionRunner] is
 *   supplied (security fix 16, L4): all child deletes, the
 *   trust delete, and the host-row delete commit or roll back
 *   together, so a crash mid-delete cannot orphan rows.
 */
class HostRepository(
    private val hostDao: HostDao,
    private val trustedHostKeyDao: TrustedHostKeyDao,
    private val secretCrypto: SecretCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Prompt 08: optional so existing callers/tests keep compiling.
    // When present, deleting a host also deletes its command
    // buttons (the Room cascade is a second net, not the only one).
    private val commandButtonDao: CommandButtonDao? = null,
    // Prompt 09: same pattern for environment settings + env vars.
    private val hostSettingsDao: HostSettingsDao? = null,
    private val hostEnvVarDao: HostEnvVarDao? = null,
    // Prompt 10: same pattern for advanced config. When present,
    // duplicate copies it (non-secret) and delete removes it.
    private val advancedRepository: AdvancedRepository? = null,
    // Security fix 16 (L4): when present, deleteHost runs inside
    // this transaction. Defaults to direct execution so existing
    // callers/tests keep working; production passes the real
    // database transaction (see TermBridgeApplication).
    private val transactionRunner: TransactionRunner = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    },
    // Item T-01: optional so existing callers/tests keep
    // compiling. When present, hosts may reference a keychain
    // entry instead of an inline credential, and loadCredential
    // resolves the reference. Production passes it (see
    // TermBridgeApplication).
    private val keychainDao: KeychainDao? = null,
    // Audit #3 (A-05): optional for the same reason. When
    // present, deleting a host also purges its on-disk session
    // transcripts — before this, the transcript files (and
    // their scrubbed-but-plaintext session content) survived
    // deletion forever, with no delete path anywhere.
    // Production passes the app's TranscriptStore (see
    // TermBridgeApplication).
    private val transcriptStorage: TranscriptStorage? = null
) : com.pishi.termbridge.transport.HostKeyTrustStore {
    fun getHosts(): Flow<List<HostProfile>> =
        hostDao.getAll().map { rows -> rows.map { it.toProfile() } }

    suspend fun getHost(id: Long): HostProfile? = hostDao.getById(id)?.toProfile()

    suspend fun hasCredential(id: Long): Boolean =
        hostDao.getById(id)?.let {
            it.credentialBlob != null || it.keychainEntryId != null
        } ?: false

    /**
     * Save a new host (id == 0) or update an existing one.
     * Returns the saved profile. Throws [HostRepositoryException]
     * on validation failure — never with a credential in the message.
     */
    suspend fun saveHost(profile: HostProfile, credential: CredentialInput?): HostProfile {
        val isNew = profile.id == 0L
        val existing = if (isNew) null else hostDao.getById(profile.id)
        if (!isNew && existing == null) {
            throw HostRepositoryException("Host not found.")
        }
        // Changing auth type without a new credential would keep a
        // password blob under a key auth type (or vice versa) — a
        // silent type confusion. Require a fresh credential then.
        val authTypeChanged = existing != null && existing.authType != profile.authType.name
        // Item T-01: a keychain link is an alternative credential
        // source. It must exist, its kind must match the host's
        // auth type, and it excludes an inline credential in the
        // same save — one source of truth per host.
        val keychainIssues = mutableListOf<ValidationIssue>()
        var keychainLinked = false
        val linkedEntryId = profile.keychainEntryId
        if (linkedEntryId != null) {
            val dao = keychainDao
            val entry = dao?.getById(linkedEntryId)
            when {
                dao == null -> keychainIssues += ValidationIssue(
                    "credential",
                    "Keychain is unavailable."
                )
                entry == null -> keychainIssues += ValidationIssue(
                    "credential",
                    "The selected keychain entry no longer exists."
                )
                entry.kind != profile.authType.name -> keychainIssues += ValidationIssue(
                    "credential",
                    "The selected keychain entry does not match the authentication type."
                )
                credential != null -> keychainIssues += ValidationIssue(
                    "credential",
                    "Choose either an inline credential or a keychain entry, not both."
                )
                else -> keychainLinked = true
            }
        }
        val hasUsableCredential =
            (existing?.credentialBlob != null && !authTypeChanged) || keychainLinked
        val authTypeIssue = if (authTypeChanged && credential == null && !keychainLinked) {
            listOf(
                ValidationIssue(
                    "credential",
                    "Changing the authentication type requires a new credential."
                )
            )
        } else {
            emptyList()
        }
        val issues = HostValidator.validate(profile) +
            HostValidator.validateCredential(profile.authType, credential, hasUsableCredential) +
            authTypeIssue + keychainIssues
        if (issues.isNotEmpty()) {
            throw HostRepositoryException(issues.joinToString("; ") { it.message })
        }
        val blob: ByteArray? = when {
            keychainLinked -> null
            // Item R-02: an edit encrypts directly under the
            // host's AAD context (the id is known). A NEW host
            // has no id yet — its blob is written in phase 2,
            // right after the insert assigns one.
            credential != null && !isNew ->
                encryptCredential(credential, com.pishi.termbridge.security.BlobContexts.hostCredential(profile.id))
            credential != null -> null
            profile.keychainEntryId != null -> null
            else -> existing?.credentialBlob
        }
        val now = clock()
        val entity = HostEntity(
            id = if (isNew) 0L else profile.id,
            name = profile.name.trim(),
            // Audit #3 (A-15): the stored form is the punycode
            // (ASCII) conversion of what the user typed, so
            // dialling and endpoint matching never see the
            // internationalized spelling.
            hostname = HostValidator.asciiHostname(profile.hostname.trim()),
            port = profile.port,
            username = profile.username.trim(),
            authType = profile.authType.name,
            notes = profile.notes,
            tags = profile.tags,
            credentialBlob = blob,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            keychainEntryId = if (keychainLinked) linkedEntryId else profile.keychainEntryId,
            favorite = profile.favorite,
            groupName = profile.groupName.trim(),
            // Item T-22: connection history is bookkeeping, not
            // form data — an edit never rewrites it; the stored
            // value wins over whatever the profile carried.
            lastConnectedAt = existing?.lastConnectedAt ?: profile.lastConnectedAt
        )
        val savedId = if (isNew) hostDao.insert(entity) else {
            hostDao.update(entity)
            profile.id
        }
        if (isNew && credential != null) {
            // Phase 2 (item R-02): now that the id exists,
            // encrypt under the host's real AAD context and
            // store the blob. The credential is wiped by this
            // call, as on every other path.
            val realBlob = encryptCredential(
                credential,
                com.pishi.termbridge.security.BlobContexts.hostCredential(savedId)
            )
            hostDao.update(entity.copy(id = savedId, credentialBlob = realBlob))
        }
        return profile.copy(id = savedId)
    }

    /**
     * Duplicate a host: non-secret fields only, no credential.
     * Prompt 09: non-secret environment SETTINGS (working
     * directory, shell, local folder) are copied with the host —
     * they describe the same server. Env VARS are NOT copied:
     * they may hold secrets, so the duplicate starts with none,
     * exactly like credentials.
     */
    suspend fun duplicateHost(id: Long, newName: String): HostProfile {
        val source = hostDao.getById(id)
            ?: throw HostRepositoryException("Host not found.")
        val now = clock()
        val copy = source.copy(
            id = 0L,
            name = newName,
            credentialBlob = null,
            createdAt = now,
            updatedAt = now,
            // Item T-22: the duplicate has never been connected
            // to; favorite and groupName DO carry over (they are
            // the user's organization of the same server).
            lastConnectedAt = null
        )
        val newId = hostDao.insert(copy)
        val settings = hostSettingsDao?.getForHostOnce(id)
        if (settings != null) {
            // Prompt 18 (audit-17 R1): the shell VALUE is copied
            // (non-secret, like the rest of the settings), but
            // its CONFIRMATION is not — confirmation attaches to
            // the host row the user reviewed, and the duplicate
            // is a new row. Its buttons warn until its own
            // environment screen is saved.
            hostSettingsDao.upsert(
                settings.copy(hostId = newId, shellConfirmed = false)
            )
        }
        // Prompt 10: advanced config is non-secret, so the
        // duplicate inherits tunnels, jump chain, and proxy —
        // exactly like env settings, and unlike credentials.
        advancedRepository?.copyForDuplicate(id, newId)
        return copy.toProfile().copy(id = newId)
    }

    /**
     * Delete a profile, its credential blob, its scoped config
     * (buttons, env settings/vars, advanced config), and — since
     * security fix 16 (M2) — its endpoint's trust record. The
     * endpoint is derived from the stored row exactly as
     * HostProfile.endpoint derives it (hostname:port), so the
     * deleted record is the one the transport would have
     * matched. When the host row is already gone, the remaining
     * deletes still run (idempotent cleanup).
     */
    suspend fun deleteHost(id: Long) {
        transactionRunner.run {
            val endpoint = hostDao.getById(id)?.toProfile()?.endpoint
            commandButtonDao?.deleteForHost(id)
            hostEnvVarDao?.deleteForHost(id)
            hostSettingsDao?.deleteForHost(id)
            advancedRepository?.deleteForHost(id)
            if (endpoint != null) {
                trustedHostKeyDao.deleteByEndpoint(endpoint)
            }
            hostDao.deleteById(id)
        }
        // Audit #3 (A-05): purge the host's transcript files
        // once the database work has committed. Best-effort by
        // design — the files live outside the database
        // transaction, and a filesystem failure here must not
        // turn a completed deletion into an error.
        try {
            transcriptStorage?.clear(id)
        } catch (_: Exception) {
            // The host is gone; a stranded transcript file is
            // orphaned data, not a failed deletion.
        }
    }

    /** Load and decrypt a host's credential. Returns null when none
     *  is stored. Callers must wipe the returned bytes after use.
     *  A decryption failure surfaces as a generic message — the
     *  user must re-enter the credential (see lifecycle doc).
     *  Item T-01: a host linked to a keychain entry resolves its
     *  material from the entry — every existing caller (session,
     *  files, tunnels, backup) therefore supports keychain hosts
     *  without knowing the keychain exists. */
    suspend fun loadCredential(id: Long): ByteArray? {
        val row = hostDao.getById(id) ?: return null
        val entryId = row.keychainEntryId
        if (entryId != null) {
            val entry = keychainDao?.getById(entryId)
                ?: throw HostRepositoryException(
                    "The linked keychain entry is missing. Please choose a credential again."
                )
            val context = com.pishi.termbridge.security.BlobContexts.keychainEntry(entryId)
            val plain = try {
                secretCrypto.decrypt(entry.materialBlob, context)
            } catch (e: Exception) {
                throw HostRepositoryException(
                    "Stored credential cannot be decrypted. Please re-enter it."
                )
            }
            migrateLegacyBlob(secretCrypto, entry.materialBlob, plain, context) { upgraded ->
                keychainDao?.update(entry.copy(materialBlob = upgraded))
            }
            return plain
        }
        val blob = row.credentialBlob ?: return null
        val context = com.pishi.termbridge.security.BlobContexts.hostCredential(id)
        val plain = try {
            secretCrypto.decrypt(blob, context)
        } catch (e: Exception) {
            throw HostRepositoryException(
                "Stored credential cannot be decrypted. Please re-enter it."
            )
        }
        migrateLegacyBlob(secretCrypto, blob, plain, context) { upgraded ->
            hostDao.update(row.copy(credentialBlob = upgraded))
        }
        return plain
    }

    override suspend fun getTrustedFingerprint(endpoint: String): String? =
        trustedHostKeyDao.getByEndpoint(endpoint)?.fingerprint

    override suspend fun trustHostKey(endpoint: String, fingerprint: String, keyType: String) {
        val now = clock()
        val existing = trustedHostKeyDao.getByEndpoint(endpoint)
        trustedHostKeyDao.upsert(
            TrustedHostKeyEntity(
                endpoint = endpoint,
                fingerprint = fingerprint,
                keyType = keyType,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now
            )
        )
    }

    override suspend fun clearTrustedKey(endpoint: String) {
        trustedHostKeyDao.deleteByEndpoint(endpoint)
    }

    private fun encryptCredential(credential: CredentialInput, context: String): ByteArray =
        encryptCredentialToBlob(secretCrypto, credential, context)

    private fun HostEntity.toProfile(): HostProfile = HostProfile(
        id = id,
        name = name,
        hostname = hostname,
        port = port,
        username = username,
        authType = AuthType.valueOf(authType),
        notes = notes,
        tags = tags,
        keychainEntryId = keychainEntryId,
        favorite = favorite,
        groupName = groupName,
        lastConnectedAt = lastConnectedAt
    )

    /** Item T-22: stamp a successful connection. Bookkeeping
     *  only — callers treat failure as non-fatal. */
    suspend fun touchLastConnected(id: Long, atMs: Long = clock()) {
        hostDao.touchLastConnected(id, atMs)
    }

    /** Item T-22: favorite toggle (one-column DAO update). */
    suspend fun setFavorite(id: Long, favorite: Boolean) {
        hostDao.setFavorite(id, favorite)
    }

    /** All remembered host keys (the "Known hosts" screen). */
    fun observeTrustedKeys(): kotlinx.coroutines.flow.Flow<List<TrustedHostKeyEntity>> =
        trustedHostKeyDao.observeAll()

    /** Forget one host key; the next connection asks the user again. */
    suspend fun forgetTrustedKey(endpoint: String) {
        trustedHostKeyDao.deleteByEndpoint(endpoint)
    }
}
