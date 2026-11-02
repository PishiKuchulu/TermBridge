package com.pishi.termbridge.data

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import com.pishi.termbridge.domain.ValidationIssue
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.security.SecretCrypto
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
 *   no credential until the user enters one.
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
    }
) : com.pishi.termbridge.transport.HostKeyTrustStore {
    fun getHosts(): Flow<List<HostProfile>> =
        hostDao.getAll().map { rows -> rows.map { it.toProfile() } }

    suspend fun getHost(id: Long): HostProfile? = hostDao.getById(id)?.toProfile()

    suspend fun hasCredential(id: Long): Boolean =
        hostDao.getById(id)?.credentialBlob != null

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
        val hasUsableCredential = existing?.credentialBlob != null && !authTypeChanged
        val authTypeIssue = if (authTypeChanged && credential == null) {
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
            authTypeIssue
        if (issues.isNotEmpty()) {
            throw HostRepositoryException(issues.joinToString("; ") { it.message })
        }
        val blob: ByteArray? = when {
            credential != null -> encryptCredential(credential)
            else -> existing?.credentialBlob
        }
        val now = clock()
        val entity = HostEntity(
            id = if (isNew) 0L else profile.id,
            name = profile.name.trim(),
            hostname = profile.hostname.trim(),
            port = profile.port,
            username = profile.username.trim(),
            authType = profile.authType.name,
            notes = profile.notes,
            tags = profile.tags,
            credentialBlob = blob,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val savedId = if (isNew) hostDao.insert(entity) else {
            hostDao.update(entity)
            profile.id
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
            updatedAt = now
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
    }

    /** Load and decrypt a host's credential. Returns null when none
     *  is stored. Callers must wipe the returned bytes after use.
     *  A decryption failure surfaces as a generic message — the
     *  user must re-enter the credential (see lifecycle doc). */
    suspend fun loadCredential(id: Long): ByteArray? {
        val blob = hostDao.getById(id)?.credentialBlob ?: return null
        return try {
            secretCrypto.decrypt(blob)
        } catch (e: Exception) {
            throw HostRepositoryException(
                "Stored credential cannot be decrypted. Please re-enter it."
            )
        }
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

    private fun encryptCredential(credential: CredentialInput): ByteArray {
        return when (credential) {
            is CredentialInput.Password -> {
                try {
                    val bytes = String(credential.password).toByteArray(Charsets.UTF_8)
                    try {
                        secretCrypto.encrypt(bytes)
                    } finally {
                        bytes.wipe()
                    }
                } finally {
                    credential.password.wipe()
                }
            }
            is CredentialInput.PrivateKey -> {
                // The passphrase is deliberately NOT stored and NOT
                // encrypted here: only the key PEM is persisted
                // (itself encrypted by the Keystore layer).
                try {
                    secretCrypto.encrypt(credential.keyPem.toByteArray(Charsets.UTF_8))
                } finally {
                    credential.passphrase?.wipe()
                }
            }
        }
    }

    private fun HostEntity.toProfile(): HostProfile = HostProfile(
        id = id,
        name = name,
        hostname = hostname,
        port = port,
        username = username,
        authType = AuthType.valueOf(authType),
        notes = notes,
        tags = tags
    )

    /** All remembered host keys (the "Known hosts" screen). */
    fun observeTrustedKeys(): kotlinx.coroutines.flow.Flow<List<TrustedHostKeyEntity>> =
        trustedHostKeyDao.observeAll()

    /** Forget one host key; the next connection asks the user again. */
    suspend fun forgetTrustedKey(endpoint: String) {
        trustedHostKeyDao.deleteByEndpoint(endpoint)
    }
}
