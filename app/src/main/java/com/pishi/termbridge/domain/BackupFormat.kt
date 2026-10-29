package com.pishi.termbridge.domain

/**
 * Prompt 11 backup format models. Pure Kotlin, no Android
 * dependency, JVM-testable. These models are the decoded form of
 * the file — they are NOT the file itself (see BackupCodec for
 * the JSON layout) and they never reach a log or a preview with
 * a secret value in them: [ExportHost.credential] and
 * [ExportEnvVar.value] exist only inside an encrypted payload's
 * decoded form, and the preview layer reads names/counts only.
 *
 * Format decisions (full record in docs/IMPORT_EXPORT.md):
 * - Only format supported: TermBridge backup JSON v1. No PuTTY,
 *   no other import format, no archive format (so no zip-slip or
 *   decompression-bomb surface exists at all).
 * - Trusted host keys are NEVER in a backup: trust must be
 *   re-established on first connection after import.
 * - A host's local SAF folder URI is NEVER exported: SAF tree
 *   URIs are device-specific and would be dead on another device.
 * - Env var NAMES without values are not exported either: a name
 *   with no value imports as nothing useful, and in encrypted
 *   mode the names ride inside the encrypted payload anyway.
 */
const val BACKUP_FORMAT = "termbridge-backup"
const val BACKUP_VERSION = 1

/** Hard file-size cap: anything larger is rejected, not truncated. */
const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

/** Per-document caps: above these, the whole file is rejected. */
const val MAX_HOSTS_IN_BACKUP = 500
const val MAX_BUTTONS_PER_HOST = 100
const val MAX_ENV_VARS_PER_HOST = 100
const val MAX_TUNNELS_PER_HOST = 100

/** Minimum passphrase for an encrypted backup, enforced in the UI
 *  and again in the codec (a short passphrase must fail even if
 *  the UI is bypassed). */
const val MIN_EXPORT_PASSPHRASE_LENGTH = 12

/** Import accepts the older, shorter minimum so a backup exported
 *  before the export minimum was raised (review #2 F-19) can still be
 *  opened; only NEW exports are held to the higher bar. */
const val MIN_IMPORT_PASSPHRASE_LENGTH = 8

/** A credential carried inside an ENCRYPTED payload only. [value]
 *  is the password text, or the PEM text for a private key. A key
 *  passphrase is never in a backup: it was never stored (prompt
 *  04), so there is nothing to export — the user enters it again
 *  at connect time after import, exactly as before. */
data class ExportCredential(
    val kind: AuthType,
    val value: String
)

data class ExportEnvVar(
    val name: String,
    /** Plaintext value, present only inside an encrypted payload. */
    val value: String
)

data class ExportVariable(
    val name: String,
    val defaultValue: String,
    val required: Boolean
)

data class ExportButton(
    val label: String,
    val category: String,
    val sortOrder: Int,
    val mode: CommandMode,
    val template: String,
    val steps: List<String>,
    val variables: List<ExportVariable>
)

/** Non-secret environment settings. The local SAF folder is
 *  deliberately absent (device-specific — see the file KDoc). */
data class ExportSettings(
    val remoteWorkingDirectory: String,
    val remoteShell: RemoteShell
)

data class ExportTunnel(
    val kind: TunnelKind,
    val bindAddress: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
    val allowNonLoopback: Boolean,
    val enabled: Boolean,
    val label: String
)

data class ExportProxy(
    val kind: ProxyKind,
    val proxyHost: String,
    val proxyPort: Int
)

/** One exported host. [exportId] is the file-local identity used
 *  by jump chains (which reference other hosts); it is NOT a
 *  database id and is never imported as one — import assigns
 *  fresh ids and remaps chains through this number. */
data class ExportHost(
    val exportId: Int,
    val name: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val notes: String,
    val tags: String,
    /** Null in a non-secret export, or when the host has no
     *  credential. Never present in a non-encrypted file — the
     *  codec rejects such a file outright. */
    val credential: ExportCredential?,
    /** True when the source host had a stored credential, even
     *  when this export does not carry it (non-secret mode). The
     *  preview uses this to say "credential not included". */
    val hasCredential: Boolean,
    val buttons: List<ExportButton>,
    val settings: ExportSettings?,
    val envVars: List<ExportEnvVar>,
    val tunnels: List<ExportTunnel>,
    /** Null when the source host has no proxy (NONE). */
    val proxy: ExportProxy?,
    /** Jump hops, as exportIds of other hosts in this file. */
    val jumpHopExportIds: List<Int>
)

/** A decoded backup document: the payload of one file, after
 *  envelope validation and (for encrypted files) decryption. */
data class BackupDocument(
    val hosts: List<ExportHost>
)

/** One existing host that an imported host would collide with.
 *  Identity is (hostname, port, username) — the server + account
 *  — not the display name, which is freely renamable. */
data class ImportConflict(
    val importedExportId: Int,
    val importedName: String,
    val existingHostId: Long,
    val existingName: String
)

/** Everything the preview screen may show. Names and counts only:
 *  no credential, no env value, no key material, ever. */
data class ImportPreview(
    val document: BackupDocument,
    val encrypted: Boolean,
    val hasCredentials: Boolean,
    val conflicts: List<ImportConflict>,
    val warnings: List<String>
) {
    val hostCount: Int get() = document.hosts.size
    val buttonCount: Int get() = document.hosts.sumOf { it.buttons.size }
    val tunnelCount: Int get() = document.hosts.sumOf { it.tunnels.size }
    val envVarCount: Int get() = document.hosts.sumOf { it.envVars.size }
    /**
     * Hosts that will actually be created or replaced: every host
     * except a conflicting one when replace is off. Conflicts are
     * matched by position in [document.hosts], not by name — two
     * hosts may share a display name, so name matching would be
     * ambiguous. [ImportConflict] carries the imported host's
     * exportId for this reason (see BackupService).
     */
    fun importableHostIds(replaceExisting: Boolean): List<Int> {
        if (replaceExisting) return document.hosts.map { it.exportId }
        val conflicted = conflicts.map { it.importedExportId }.toSet()
        return document.hosts.map { it.exportId }.filter { it !in conflicted }
    }
}

/** Result of applying an import. Names only, no secret values. */
data class ImportResult(
    val importedNames: List<String>,
    val replacedNames: List<String>,
    val skippedNames: List<String>,
    val warnings: List<String>
)
