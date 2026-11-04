package com.pishi.termbridge.data

import com.pishi.termbridge.domain.BackupDocument
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.ExportButton
import com.pishi.termbridge.domain.ExportCredential
import com.pishi.termbridge.domain.ExportEnvVar
import com.pishi.termbridge.domain.ExportHost
import com.pishi.termbridge.domain.ExportProxy
import com.pishi.termbridge.domain.ExportSettings
import com.pishi.termbridge.domain.ExportTunnel
import com.pishi.termbridge.domain.ExportVariable
import com.pishi.termbridge.domain.ExportKeychainEntry
import com.pishi.termbridge.domain.HostEnvironmentSettings
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.ImportConflict
import com.pishi.termbridge.domain.ImportPreview
import com.pishi.termbridge.domain.ImportResult
import com.pishi.termbridge.domain.JumpChain
import com.pishi.termbridge.domain.KeychainEntry
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.SecretRedactor
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.wipe
import kotlinx.coroutines.flow.first

/**
 * Prompt 11 import/export orchestration. Rules enforced here, not
 * in the UI:
 *
 * - Export collects from the existing repositories only — no new
 *   credential path is invented. In non-secret mode, credentials
 *   and env values are never even loaded (not loaded-and-stripped:
 *   never loaded), so a non-secret export file cannot contain them
 *   by construction, and the codec rejects a hand-crafted file
 *   that tries anyway.
 * - In encrypted mode, credentials and env values ARE loaded
 *   (Keystore-decrypted, the only way to make them portable —
 *   Keystore blobs themselves are device-bound and are never
 *   exported, because exporting one would silently ship an
 *   unusable ciphertext), placed in the inner payload, and the
 *   whole payload is GCM-encrypted under the passphrase. The
 *   plaintext inner JSON is never written to a file, never
 *   logged, and its byte buffers are wiped after encryption.
 * - Import never writes a trusted host key: there is no trust
 *   code path in this file at all. Trust is re-established on
 *   first connection after import, exactly as for a new host.
 * - Import never executes a command: buttons are stored as data.
 * - Conflict rule (explicit, default non-destructive): an imported
 *   host collides with an existing host when hostname (case-
 *   insensitive), port, and username all match. By default the
 *   imported host is SKIPPED and the existing host is untouched.
 *   Only when the user explicitly enables "replace existing" AND
 *   confirms does the existing host's profile and child config
 *   get replaced — and even then, its credential is replaced
 *   ONLY when the import itself carries one (encrypted mode);
 *   a non-secret replace keeps the existing credential. A
 *   credential is therefore never silently overwritten.
 * - Conflict matching snapshots the existing hosts once at apply
 *   time: hosts imported earlier in the same run do not collide
 *   with later ones from the same file (the app does not enforce
 *   endpoint uniqueness, and a file with two same-endpoint hosts
 *   imports as two hosts, like two hand-created ones).
 * - Jump chains apply in a second pass, after every host has a
 *   new id. A chain whose hop host was skipped (conflict, no
 *   replace) is skipped whole with a warning — a partial chain
 *   is a different route, not a safe subset.
 * - A child record that fails to save during apply (e.g. a
 *   button the repository now rejects) becomes a warning, not a
 *   silent drop and not a whole-import abort: the host itself
 *   still imports, and the report says what is missing.
 */
class BackupService(
    private val hostRepository: HostRepository,
    private val commandRepository: CommandRepository,
    private val environmentRepository: HostEnvironmentRepository,
    private val advancedRepository: AdvancedRepository,
    // Security fix 16 (M3): when present, the replace path for
    // each host runs inside this transaction. Defaults to direct
    // execution so existing callers/tests keep working;
    // production passes the real database transaction (see
    // TermBridgeApplication). Fresh imports keep the old
    // warning-per-child behaviour — they are additive, so a
    // failed child is a warning, not a half-replaced host.
    private val transactionRunner: TransactionRunner = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    },
    // Item T-01: optional so existing callers/tests keep
    // working; production passes the real repository (see
    // TermBridgeApplication). Without it, no keychain section
    // is exported and a linked host's material is inlined as
    // its own credential instead — still a complete backup.
    private val keychainRepository: KeychainRepository? = null,
    // Item T-02: same pattern for the global snippets. Without
    // it, no snippets section is exported and imported
    // snippets are reported as warnings instead of saved.
    private val snippetRepository: SnippetRepository? = null,
    // Item T-03: same pattern for session transcripts. Without
    // it, no transcripts are exported or restored.
    private val transcriptStore: com.pishi.termbridge.settings.TranscriptStorage? = null
) {
    suspend fun buildExport(includeSecrets: Boolean, passphrase: CharArray?): String {
        val hosts = hostRepository.getHosts().first()
        // Jump chains reference hosts by database id; the file
        // must reference them by exportId instead, so the map is
        // built before any host is collected. Every host is
        // exported, so every hop resolves — mapNotNull below is
        // defensive only, never the normal path.
        val exportIdByHostId = hosts.mapIndexed { index, host -> host.id to index }.toMap()
        // Item T-01: keychain entries are collected first so a
        // linked host can reference its entry by label and its
        // material rides in the keychain section only.
        val keychainEntries = keychainRepository?.observeEntries()?.first()
            ?: emptyList()
        val labelByEntryId = keychainEntries.associate { it.id to it.label }
        val exportHosts = hosts.mapIndexed { index, host ->
            buildExportHost(host, index, includeSecrets, exportIdByHostId, labelByEntryId)
        }
        val exportKeychain = keychainEntries.map { entry ->
            buildExportKeychainEntry(entry, includeSecrets)
        }
        // Item T-02: global snippets ride in their own section;
        // they carry no secrets (the validator forbids them),
        // so they are exported in both modes.
        val exportSnippets = snippetRepository?.getSnippetsOnce()
            ?.map { it.toExport() } ?: emptyList()
        // Item T-03: transcripts ride ONLY in secret-carrying
        // (encrypted) exports and ONLY for hosts that opted in
        // twice. A plain export never contains one — the codec
        // rejects it on decode as well.
        val exportTranscripts = if (includeSecrets && transcriptStore != null) {
            hosts.mapNotNull { host ->
                val s = environmentRepository.getSettingsOnce(host.id)
                if (s?.sessionLogEnabled == true && s.sessionLogInBackup) {
                    val text = transcriptStore.read(host.id)
                    if (text.isNotEmpty()) {
                        com.pishi.termbridge.domain.ExportTranscript(host.name, text)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } else {
            emptyList()
        }
        val document = com.pishi.termbridge.domain.BackupDocument(
            exportHosts, exportKeychain, exportSnippets, exportTranscripts
        )
        return BackupCodec.encodeEnvelope(document, includeSecrets, passphrase)
    }

    private suspend fun buildExportKeychainEntry(
        entry: KeychainEntry,
        includeSecrets: Boolean
    ): ExportKeychainEntry {
        val material: ExportCredential? = if (includeSecrets) {
            val bytes = try {
                keychainRepository!!.loadMaterial(entry.id)
            } catch (e: KeychainRepositoryException) {
                // Like an undecryptable env var: fail the whole
                // export rather than silently dropping an entry
                // the user believes is backed up.
                throw BackupException(
                    "Keychain entry \"${entry.label}\" could not be decrypted; " +
                        "the export was not written."
                )
            } ?: throw BackupException(
                "Keychain entry \"${entry.label}\" could not be decrypted; " +
                    "the export was not written."
            )
            try {
                ExportCredential(entry.kind, String(bytes, Charsets.UTF_8))
            } finally {
                bytes.wipe()
            }
        } else {
            null
        }
        return ExportKeychainEntry(
            label = entry.label,
            kind = entry.kind,
            material = material,
            hasMaterial = true,
            publicKeyText = entry.publicKeyText,
            fingerprint = entry.fingerprint
        )
    }

    private suspend fun buildExportHost(
        host: HostProfile,
        exportId: Int,
        includeSecrets: Boolean,
        exportIdByHostId: Map<Long, Int>,
        labelByEntryId: Map<Long, String> = emptyMap()
    ): ExportHost {
        val buttons = commandRepository.getButtons(host.id).first().mapNotNull { summary ->
            commandRepository.getButton(summary.id)
        }.map { it.toExport() }
        val settings = environmentRepository.getSettingsOnce(host.id)?.toExport()
        val envVars: List<ExportEnvVar> = if (includeSecrets) {
            val decrypted = environmentRepository.loadEnvVarsForConnection(host.id)
            try {
                // An undecryptable var must FAIL the export, not be
                // silently omitted: a backup that quietly drops a
                // variable is worse than no backup, because the
                // user believes it is complete. Names only in the
                // message — never values.
                if (decrypted.undecryptableNames.isNotEmpty()) {
                    throw BackupException(
                        "Environment variables that could not be decrypted " +
                            "for host \"${host.name}\": " +
                            decrypted.undecryptableNames.joinToString(", ")
                    )
                }
                decrypted.values.map { value ->
                    ExportEnvVar(value.name, String(value.value, Charsets.UTF_8))
                }
            } finally {
                decrypted.values.forEach { it.value.wipe() }
            }
        } else {
            emptyList()
        }
        // Item T-01: a host linked to a keychain entry exports
        // the LABEL only — its material rides in the keychain
        // section, never duplicated here. An unlinked host (or
        // a dangling reference) keeps the inline path.
        val keychainLabel = host.keychainEntryId?.let { labelByEntryId[it] }
        val credential: ExportCredential? = if (includeSecrets && keychainLabel == null) {
            val bytes = hostRepository.loadCredential(host.id)
            if (bytes == null) {
                null
            } else {
                try {
                    ExportCredential(host.authType, String(bytes, Charsets.UTF_8))
                } finally {
                    bytes.wipe()
                }
            }
        } else {
            null
        }
        val tunnels = advancedRepository.getTunnelsOnce(host.id).map { it.toExport() }
        val proxyRow = advancedRepository.getProxyOnce(host.id)
        val proxy = if (proxyRow.kind == com.pishi.termbridge.domain.ProxyKind.NONE) {
            null
        } else {
            ExportProxy(proxyRow.kind, proxyRow.proxyHost, proxyRow.proxyPort)
        }
        val chain = advancedRepository.getJumpChainOnce(host.id)
        val hopExportIds = chain.jumpHostIds.mapNotNull { exportIdByHostId[it] }
        return ExportHost(
            exportId = exportId,
            name = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            authType = host.authType,
            notes = host.notes,
            tags = host.tags,
            credential = credential,
            hasCredential = hostRepository.hasCredential(host.id),
            buttons = buttons,
            settings = settings,
            envVars = envVars,
            tunnels = tunnels,
            proxy = proxy,
            jumpHopExportIds = hopExportIds,
            keychainLabel = keychainLabel,
            favorite = host.favorite,
            groupName = host.groupName
        )
    }

    suspend fun parseForPreview(text: String, passphrase: CharArray?): ImportPreview {
        val parsed = BackupCodec.decodeEnvelope(text, passphrase)
        val existing = hostRepository.getHosts().first()
        val conflicts = parsed.document.hosts.mapNotNull { imported ->
            val match = existing.firstOrNull {
                it.hostname.lowercase() == imported.hostname.lowercase() &&
                    it.port == imported.port &&
                    it.username == imported.username
            }
            if (match != null) {
                ImportConflict(
                    importedExportId = imported.exportId,
                    importedName = imported.name,
                    existingHostId = match.id,
                    existingName = match.name
                )
            } else {
                null
            }
        }
        return ImportPreview(
            document = parsed.document,
            encrypted = parsed.encrypted,
            hasCredentials = parsed.hasCredentials,
            conflicts = conflicts,
            warnings = parsed.warnings,
            advisories = securityWarnings(parsed.document)
        )
    }

    /**
     * Review #2 F-15: the preview used to show only names and counts,
     * so an imported file could silently bring in settings that change
     * where traffic goes or what is typed into a shell. These lines
     * name the host and the setting only — never a value that could
     * be a secret.
     */
    private fun securityWarnings(document: BackupDocument): List<String> {
        val out = mutableListOf<String>()
        if (document.keychain.isNotEmpty()) {
            out += "This backup contains ${document.keychain.size} keychain " +
                "entries — hosts linked to them resolve their credential " +
                "through the entry after import."
        }
        for (host in document.hosts) {
            val exposed = host.tunnels.count { it.allowNonLoopback }
            if (exposed > 0) {
                out += "\"${host.name}\": $exposed port forward(s) may listen on non-local addresses."
            }
            if (host.proxy != null) {
                out += "\"${host.name}\": connects through a proxy."
            }
            if (host.jumpHopExportIds.isNotEmpty()) {
                out += "\"${host.name}\": connects through ${host.jumpHopExportIds.size} jump host(s)."
            }
            if (!host.settings?.remoteWorkingDirectory.isNullOrBlank()) {
                out += "\"${host.name}\": a directory change is typed into the shell on every connect."
            }
            if (host.buttons.isNotEmpty()) {
                out += "\"${host.name}\": ${host.buttons.size} command button(s) — review them before use."
            }
        }
        return out
    }

    suspend fun applyImport(preview: ImportPreview, replaceExisting: Boolean): ImportResult {
        val existingSnapshot = hostRepository.getHosts().first()
        val conflictByExportId = preview.conflicts.associateBy { it.importedExportId }
        val importedNames = mutableListOf<String>()
        val replacedNames = mutableListOf<String>()
        val skippedNames = mutableListOf<String>()
        val warnings = preview.warnings.toMutableList()
        // Pass 0 (item T-01): keychain entries, before any host
        // that links to them. An entry whose material is not in
        // the file (non-secret export) is NOT recreated — an
        // empty entry would look usable and fail at connect —
        // and hosts linking to it import unlinked, with a
        // warning each. A label that is already taken is
        // imported under a suffixed label rather than
        // overwriting the existing entry.
        val keychainIdByLabel = mutableMapOf<String, Long>()
        for (entry in preview.document.keychain) {
            if (entry.material == null) {
                if (entry.hasMaterial) {
                    warnings +=
                        "Keychain entry \"${entry.label}\" was not restored: " +
                            "its secret is not in this backup file."
                }
                continue
            }
            val repo = keychainRepository
            if (repo == null) {
                warnings +=
                    "Keychain entry \"${entry.label}\" could not be imported."
                continue
            }
            var candidate = entry.label
            var attempt = 1
            while (repo.getByLabel(candidate) != null) {
                attempt++
                candidate = if (attempt == 2) {
                    "${entry.label} (imported)"
                } else {
                    "${entry.label} (imported ${attempt - 1})"
                }
            }
            if (candidate != entry.label) {
                warnings +=
                    "Keychain entry \"${entry.label}\" was imported as " +
                        "\"$candidate\" because the label is already in use."
            }
            val input = when (entry.material.kind) {
                AuthType.PASSWORD ->
                    CredentialInput.Password(entry.material.value.toCharArray())
                AuthType.PRIVATE_KEY ->
                    CredentialInput.PrivateKey(entry.material.value, null)
            }
            try {
                val saved = repo.saveEntry(
                    KeychainEntry(label = candidate, kind = entry.kind),
                    input,
                    publicKeyText = entry.publicKeyText,
                    fingerprint = entry.fingerprint
                )
                keychainIdByLabel[entry.label] = saved.id
            } catch (e: Exception) {
                warnings +=
                    "Keychain entry \"${entry.label}\" could not be imported: " +
                        safeMessage(e)
            }
        }
        // Pass 1: hosts + children except jump chains. newIdByExportId
        // is the remap table for pass 2.
        val newIdByExportId = mutableMapOf<Int, Long>()
        for (exported in preview.document.hosts) {
            val conflict = conflictByExportId[exported.exportId]
            val existingMatch = conflict?.let { match ->
                existingSnapshot.firstOrNull { it.id == match.existingHostId }
            }
            if (existingMatch != null && !replaceExisting) {
                skippedNames += exported.name
                continue
            }
            val targetId = if (existingMatch != null) {
                existingMatch.id
            } else {
                0L
            }
            val rawCredentialInput: CredentialInput? = when (exported.credential) {
                null -> null
                else -> when (exported.credential.kind) {
                    AuthType.PASSWORD ->
                        CredentialInput.Password(exported.credential.value.toCharArray())
                    AuthType.PRIVATE_KEY ->
                        CredentialInput.PrivateKey(exported.credential.value, null)
                }
            }
            // Item T-01 link resolution. A restored entry wins
            // over an inline credential (our own exports never
            // carry both; a crafted file might). When the file
            // carries no secret for this host at all — no inline
            // credential and no restored entry — a REPLACE keeps
            // the existing credential state whole (link
            // included), exactly like a non-secret replace keeps
            // an inline credential: a file with no secret in
            // hand has nothing to replace a secret with. A FRESH
            // host in that state cannot be created (a credential
            // is required for a new host) and is reported below
            // by the save failure, like any credential-less host
            // from a non-secret backup.
            val linkedEntryId = exported.keychainLabel?.let { keychainIdByLabel[it] }
            if (linkedEntryId != null && rawCredentialInput != null) {
                warnings +=
                    "Host \"${exported.name}\" carried both a credential and a " +
                        "keychain link; the keychain link was used."
            }
            if (exported.keychainLabel != null && linkedEntryId == null &&
                rawCredentialInput == null
            ) {
                warnings += if (existingMatch != null) {
                    "Host \"${exported.name}\" kept its existing credential " +
                        "because its keychain entry was not restored from " +
                        "this backup."
                } else {
                    "Host \"${exported.name}\" has no credential in this " +
                        "backup: its keychain entry was not restored."
                }
            }
            val credentialInput = if (linkedEntryId != null) null else rawCredentialInput
            val profileKeychainId = when {
                linkedEntryId != null -> linkedEntryId
                existingMatch != null && credentialInput == null ->
                    existingMatch.keychainEntryId
                else -> null
            }
            val profile = HostProfile(
                id = targetId,
                name = exported.name,
                hostname = exported.hostname,
                port = exported.port,
                username = exported.username,
                authType = exported.authType,
                notes = exported.notes,
                tags = exported.tags,
                keychainEntryId = profileKeychainId,
                favorite = exported.favorite,
                groupName = exported.groupName
            )
            if (existingMatch != null) {
                // Replace (security fix 16, M3): the whole unit —
                // profile save, old-child delete, new-child apply —
                // runs inside ONE transaction, and a child failure
                // THROWS instead of warning. A crash or a rejected
                // child therefore rolls the host back to its old
                // configuration instead of leaving a new profile
                // with partial children and the old config
                // already deleted. The host is recorded as
                // replaced only when the transaction commits.
                try {
                    val saved = transactionRunner.run {
                        val s = hostRepository.saveHost(profile, credentialInput)
                        commandRepository.deleteForHost(s.id)
                        environmentRepository.deleteForHost(s.id)
                        advancedRepository.deleteForHost(s.id)
                        applyChildren(exported, s.id) { throw BackupException(it) }
                        s
                    }
                    replacedNames += exported.name
                    newIdByExportId[exported.exportId] = saved.id
                } catch (e: Exception) {
                    warnings +=
                        "Host \"${exported.name}\" could not be replaced: " +
                            safeMessage(e)
                    continue
                }
            } else {
                val saved = try {
                    hostRepository.saveHost(profile, credentialInput)
                } catch (e: Exception) {
                    warnings += "Host \"${exported.name}\" could not be imported: ${safeMessage(e)}"
                    continue
                }
                importedNames += exported.name
                newIdByExportId[exported.exportId] = saved.id
                applyChildren(exported, saved.id) { warnings += it }
            }
        }
        // Pass 2: jump chains, only when every hop was imported.
        for (exported in preview.document.hosts) {
            val targetId = newIdByExportId[exported.exportId] ?: continue
            if (exported.jumpHopExportIds.isEmpty()) continue
            val hopIds = exported.jumpHopExportIds.map { newIdByExportId[it] }
            if (hopIds.any { it == null }) {
                warnings +=
                    "Jump chain for host \"${exported.name}\" was skipped: " +
                        "one of its jump hosts was not imported."
                continue
            }
            try {
                advancedRepository.saveJumpChain(
                    JumpChain(targetId, hopIds.filterNotNull())
                )
            } catch (e: Exception) {
                warnings +=
                    "Jump chain for host \"${exported.name}\" could not be saved: " +
                        safeMessage(e)
            }
        }
        // Pass 3 (item T-02): global snippets. Additive only —
        // importing never deletes or replaces local snippets,
        // and a snippet that fails validation is a warning,
        // like a failed child on a fresh host import.
        for (snippet in preview.document.snippets) {
            val repo = snippetRepository
            if (repo == null) {
                warnings +=
                    "Snippet \"${snippet.label}\" could not be imported."
                continue
            }
            try {
                repo.saveSnippet(snippet.toDomain(GLOBAL_HOST_ID))
            } catch (e: Exception) {
                warnings +=
                    "Snippet \"${snippet.label}\" could not be imported: " +
                        safeMessage(e)
            }
        }
        // Pass 4 (item T-03): session transcripts. Matched to
        // imported hosts by display name (the export key);
        // restoring one re-enables that host's two opt-ins, so
        // the restored device keeps recording into the file.
        val store = transcriptStore
        if (store != null) {
            for (transcript in preview.document.transcripts) {
                val exported = preview.document.hosts
                    .firstOrNull { it.name == transcript.hostLabel }
                val newId = exported?.let { newIdByExportId[it.exportId] }
                if (newId == null) {
                    warnings +=
                        "Transcript for \"${transcript.hostLabel}\" was not " +
                            "restored: the host was not imported."
                    continue
                }
                try {
                    store.write(newId, transcript.text)
                    val existing = environmentRepository.getSettingsOnce(newId)
                    environmentRepository.saveSettings(
                        (existing ?: com.pishi.termbridge.domain.HostEnvironmentSettings(
                            hostId = newId
                        )).copy(
                            sessionLogEnabled = true,
                            sessionLogInBackup = true
                        )
                    )
                } catch (e: Exception) {
                    warnings +=
                        "Transcript for \"${transcript.hostLabel}\" could not " +
                            "be restored: ${safeMessage(e)}"
                }
            }
        } else if (preview.document.transcripts.isNotEmpty()) {
            warnings += "Session transcripts were not restored (no transcript store)."
        }
        return ImportResult(
            importedNames = importedNames,
            replacedNames = replacedNames,
            skippedNames = skippedNames,
            warnings = warnings
        )
    }

    /**
     * Apply one host's children. [onFailure] decides the
     * failure policy: fresh imports pass a warning collector
     * (a failed child is a warning, the host still imports);
     * the replace path passes a thrower (security fix 16, M3),
     * so a failed child aborts the enclosing transaction and
     * the old configuration survives.
     */
    private suspend fun applyChildren(
        exported: ExportHost,
        hostId: Long,
        onFailure: (String) -> Unit
    ) {
        for (button in exported.buttons) {
            try {
                commandRepository.saveButton(button.toDomain(hostId))
            } catch (e: Exception) {
                onFailure(
                    "Button \"${button.label}\" for host \"${exported.name}\" " +
                        "could not be imported: ${safeMessage(e)}"
                )
            }
        }
        if (exported.settings != null) {
            try {
                environmentRepository.saveSettings(
                    HostEnvironmentSettings(
                        hostId = hostId,
                        remoteWorkingDirectory = exported.settings.remoteWorkingDirectory,
                        localDirectoryUri = null,
                        remoteShell = exported.settings.remoteShell
                    )
                )
            } catch (e: Exception) {
                onFailure(
                    "Working-directory settings for host \"${exported.name}\" " +
                        "could not be imported: ${safeMessage(e)}"
                )
            }
        }
        for (variable in exported.envVars) {
            try {
                environmentRepository.saveEnvVar(
                    hostId, 0L, variable.name, variable.value.toCharArray()
                )
            } catch (e: Exception) {
                onFailure(
                    "Environment variable \"${variable.name}\" for host " +
                        "\"${exported.name}\" could not be imported: ${safeMessage(e)}"
                )
            }
        }
        for (tunnel in exported.tunnels) {
            try {
                advancedRepository.saveTunnel(
                    TunnelConfig(
                        hostId = hostId, kind = tunnel.kind,
                        bindAddress = tunnel.bindAddress, bindPort = tunnel.bindPort,
                        targetHost = tunnel.targetHost, targetPort = tunnel.targetPort,
                        allowNonLoopback = tunnel.allowNonLoopback,
                        enabled = tunnel.enabled, label = tunnel.label
                    )
                )
            } catch (e: Exception) {
                onFailure(
                    "Tunnel \"${tunnel.label}\" for host \"${exported.name}\" " +
                        "could not be imported: ${safeMessage(e)}"
                )
            }
        }
        if (exported.proxy != null) {
            try {
                advancedRepository.saveProxy(
                    ProxyConfig(
                        hostId = hostId, kind = exported.proxy.kind,
                        proxyHost = exported.proxy.proxyHost,
                        proxyPort = exported.proxy.proxyPort
                    )
                )
            } catch (e: Exception) {
                onFailure(
                    "Proxy for host \"${exported.name}\" could not be imported: " +
                        safeMessage(e)
                )
            }
        }
    }

    /** Repository messages are already secret-free by construction;
     *  this is the second net for the import report, in case a
     *  message ever echoes a value. It never logs anything. */
    private fun safeMessage(e: Exception): String {
        // Remediation phase 2: truncation alone is not a second
        // net — a dependency's exception text could carry a
        // secret. Pattern-scrub first, then truncate.
        val message = SecretRedactor.scrubFreeText(e.message ?: "Unknown error.")
        return if (message.length > 200) message.substring(0, 200) else message
    }

    private fun CommandButton.toExport(): ExportButton = ExportButton(
        label = label,
        category = category,
        sortOrder = sortOrder,
        mode = mode,
        template = template,
        steps = steps,
        variables = variables.map { ExportVariable(it.name, it.defaultValue, it.required) }
    )

    private fun ExportButton.toDomain(hostId: Long): CommandButton = CommandButton(
        hostId = hostId,
        label = label,
        category = category,
        sortOrder = sortOrder,
        mode = mode,
        template = template,
        steps = steps,
        variables = variables.map { CommandVariable(it.name, it.defaultValue, it.required) }
    )

    private fun HostEnvironmentSettings.toExport(): ExportSettings = ExportSettings(
        remoteWorkingDirectory = remoteWorkingDirectory,
        remoteShell = remoteShell
    )

    private fun TunnelConfig.toExport(): ExportTunnel = ExportTunnel(
        kind = kind,
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
        allowNonLoopback = allowNonLoopback,
        enabled = enabled,
        label = label
    )
}
