package com.pishi.termbridge.data

import com.pishi.termbridge.domain.EnvVarInfo
import com.pishi.termbridge.domain.EnvVarValue
import com.pishi.termbridge.domain.EnvironmentValidator
import com.pishi.termbridge.domain.HostEnvironmentSettings
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.ValidationIssue
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.security.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A save/validation failure. Messages never contain an env value. */
class HostEnvironmentException(message: String) : Exception(message)

/** Decrypted vars for one connection, plus names that could not be
 *  decrypted (Keystore loss, tampering). Undecryptable vars are
 *  reported as rejected at session start — env is advisory, so one
 *  bad var must not block the whole session. */
data class DecryptedEnvVars(
    val values: List<EnvVarValue>,
    val undecryptableNames: List<String>
)

/**
 * Prompt 09 environment repository. Rules enforced here, not in
 * the UI:
 * - Settings and env names are validated before any write; a
 *   directory is stored in canonical form, never raw user text.
 * - Env VALUES are encrypted with the same Keystore crypto as
 *   host credentials before reaching the database, and plaintext
 *   buffers are wiped in finally blocks. There is no plaintext
 *   value column anywhere in this phase, and no API in this class
 *   returns a stored value except [loadEnvVarsForConnection],
 *   which exists only for shell startup.
 * - Env vars are write-only in the UI: editing a var always
 *   requires re-entering its value, because no stored value is
 *   ever read back into a form.
 * - Duplicating a host copies its SETTINGS (non-secret) but never
 *   its env vars (secret-bearing) — see HostRepository.
 */
class HostEnvironmentRepository(
    private val settingsDao: HostSettingsDao,
    private val envVarDao: HostEnvVarDao,
    private val secretCrypto: SecretCrypto
) {
    fun getSettings(hostId: Long): Flow<HostEnvironmentSettings?> =
        settingsDao.getForHost(hostId).map { it?.toSettings() }

    suspend fun getSettingsOnce(hostId: Long): HostEnvironmentSettings? =
        settingsDao.getForHostOnce(hostId)?.toSettings()

    suspend fun saveSettings(settings: HostEnvironmentSettings) {
        val issues = mutableListOf<ValidationIssue>()
        EnvironmentValidator.validateRemoteDirectory(
            settings.remoteWorkingDirectory,
            settings.remoteShell
        )?.let { issues += it }
        EnvironmentValidator.validateLocalDirectoryUri(settings.localDirectoryUri)?.let {
            issues += it
        }
        if (issues.isNotEmpty()) {
            throw HostEnvironmentException(issues.joinToString("; ") { it.message })
        }
        settingsDao.upsert(
            HostSettingsEntity(
                hostId = settings.hostId,
                remoteWorkingDirectory = EnvironmentValidator.canonicalDirectory(
                    settings.remoteWorkingDirectory,
                    settings.remoteShell
                ) ?: "",
                localDirectoryUri = settings.localDirectoryUri?.ifBlank { null },
                remoteShell = settings.remoteShell.name,
                shellConfirmed = settings.shellConfirmed,
                envExportFallback = settings.envExportFallback,
                sessionLogEnabled = settings.sessionLogEnabled,
                sessionLogInBackup = settings.sessionLogInBackup
            )
        )
    }

    suspend fun deleteSettingsForHost(hostId: Long) {
        settingsDao.deleteForHost(hostId)
    }

    /** Names only — the list UI never sees a value or a blob. */
    fun getEnvVarNames(hostId: Long): Flow<List<EnvVarInfo>> =
        envVarDao.getForHost(hostId).map { rows ->
            rows.map { EnvVarInfo(it.id, it.hostId, it.name) }
        }

    /**
     * Save a new var (id == 0) or update an existing one. [value]
     * is always required — there is no "keep existing value" path,
     * because values are never read back into a form.
     */
    suspend fun saveEnvVar(hostId: Long, id: Long, name: String, value: CharArray) {
        val trimmedName = name.trim()
        // One transient String copy (unwipeable, like all Compose
        // text-field strings — documented residual, not hidden).
        val valueText = String(value)
        val issues = mutableListOf<ValidationIssue>()
        EnvironmentValidator.validateEnvName(trimmedName)?.let { issues += it }
        EnvironmentValidator.validateEnvValue(valueText)?.let { issues += it }
        if (issues.isNotEmpty()) {
            throw HostEnvironmentException(issues.joinToString("; ") { it.message })
        }
        val isNew = id == 0L
        val existing = if (isNew) null else envVarDao.getById(id)
        if (!isNew && existing == null) {
            throw HostEnvironmentException("Environment variable not found.")
        }
        if (existing != null && existing.hostId != hostId) {
            throw HostEnvironmentException("A variable cannot be moved to another host.")
        }
        val clash = envVarDao.getByName(hostId, trimmedName)
        if (clash != null && clash.id != id) {
            throw HostEnvironmentException("A variable with this name already exists for this host.")
        }
        val valueBytes = valueText.toByteArray(Charsets.UTF_8)
        try {
            // Item R-02: the value blob is bound to this host
            // and variable name via the AAD context.
            val blob = secretCrypto.encrypt(
                valueBytes,
                com.pishi.termbridge.security.BlobContexts.envValue(hostId, trimmedName)
            )
            if (isNew) {
                val siblings = envVarDao.getForHostOnce(hostId)
                val sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1
                envVarDao.insert(
                    HostEnvVarEntity(
                        hostId = hostId,
                        name = trimmedName,
                        valueBlob = blob,
                        sortOrder = sortOrder
                    )
                )
            } else {
                envVarDao.update(existing!!.copy(name = trimmedName, valueBlob = blob))
            }
        } finally {
            valueBytes.wipe()
            value.wipe()
        }
    }

    suspend fun deleteEnvVar(id: Long, hostId: Long) {
        val existing = envVarDao.getById(id) ?: return
        if (existing.hostId != hostId) {
            throw HostEnvironmentException("A variable cannot be deleted from another host.")
        }
        envVarDao.deleteById(id)
    }

    suspend fun deleteForHost(hostId: Long) {
        envVarDao.deleteForHost(hostId)
        settingsDao.deleteForHost(hostId)
    }

    /** Copy non-secret settings to a new host. Env vars are never
     *  copied (they may hold secrets; the duplicate starts with
     *  none, exactly like credentials in prompt 04). */
    suspend fun copySettingsForDuplicate(sourceHostId: Long, newHostId: Long) {
        val source = settingsDao.getForHostOnce(sourceHostId) ?: return
        settingsDao.upsert(source.copy(hostId = newHostId))
    }

    /**
     * Decrypt every var for one shell startup. Callers must wipe
     * every returned [EnvVarValue.value] after use. A var that
     * fails to decrypt is listed by NAME in [DecryptedEnvVars.undecryptableNames]
     * — its value is never exposed, and the session still starts.
     */
    suspend fun loadEnvVarsForConnection(hostId: Long): DecryptedEnvVars {
        val values = mutableListOf<EnvVarValue>()
        val undecryptable = mutableListOf<String>()
        for (row in envVarDao.getForHostOnce(hostId)) {
            val context =
                com.pishi.termbridge.security.BlobContexts.envValue(hostId, row.name)
            try {
                val plain = secretCrypto.decrypt(row.valueBlob, context)
                migrateLegacyBlob(secretCrypto, row.valueBlob, plain, context) { upgraded ->
                    envVarDao.update(row.copy(valueBlob = upgraded))
                }
                values += EnvVarValue(row.name, plain)
            } catch (_: Exception) {
                undecryptable += row.name
            }
        }
        return DecryptedEnvVars(values, undecryptable)
    }

    private fun HostSettingsEntity.toSettings(): HostEnvironmentSettings =
        HostEnvironmentSettings(
            hostId = hostId,
            remoteWorkingDirectory = remoteWorkingDirectory,
            localDirectoryUri = localDirectoryUri,
            remoteShell = RemoteShell.valueOf(remoteShell),
            shellConfirmed = shellConfirmed,
            envExportFallback = envExportFallback,
            sessionLogEnabled = sessionLogEnabled,
            sessionLogInBackup = sessionLogInBackup
        )
}
