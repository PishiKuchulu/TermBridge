package com.pishi.termbridge.domain

/**
 * Prompt 09: per-host environment and working-directory settings.
 *
 * What these are NOT, stated first because the pack requires the
 * distinction: these are REMOTE shell settings. They never touch
 * the Android app's own environment (no System property, no
 * ProcessBuilder environment, no app-level env change exists in
 * this codebase). A remote shell's environment is whatever the
 * server and the user's login profile make of the requests in
 * [EnvApplyResult] — see docs/ENVIRONMENT_AND_PATHS.md.
 */

/** Remote shell dialect, chosen per host. It decides how a working
 *  directory is rendered as a `cd` command and how a remote path
 *  is validated. It is NOT auto-detected: guessing a remote OS
 *  from a hostname would be wrong often enough to be harmful, and
 *  a wrong guess sends a broken `cd` into a live shell. */
enum class RemoteShell {
    POSIX,
    POWERSHELL,
    CMD
}

/** Non-secret per-host settings. No env VALUE lives here. */
data class HostEnvironmentSettings(
    val hostId: Long,
    /** "" means unset. Stored in canonical form (see validators). */
    val remoteWorkingDirectory: String = "",
    /** SAF tree URI string, or null when unset. Never a filesystem path. */
    val localDirectoryUri: String? = null,
    val remoteShell: RemoteShell = RemoteShell.POSIX,
    /**
     * Prompt 18 (audit-17 R1): true only once the user has SAVED
     * the environment screen, where the shell picker is displayed
     * and editable. False means the shell is still the untouched
     * default: the stored POSIX value was never presented to the
     * user for this host, so parameterised command buttons must
     * warn instead of silently applying POSIX quoting (see
     * CommandTemplate.warnUnconfirmedShell). Rows created before
     * this flag existed migrate to false. Backup import also
     * yields false: confirmation is device-local, not carried in
     * the backup format, so an imported host warns until its
     * environment screen is saved on this device.
     */
    val shellConfirmed: Boolean = false
)

/** One stored env var, for list UI. Carries a NAME ONLY — never a
 *  value, never a blob. The UI shows names and a masked marker. */
data class EnvVarInfo(
    val id: Long,
    val hostId: Long,
    val name: String
)

/** A decrypted env var, for shell startup only. The caller must
 *  wipe [value] after use. This type never reaches the UI. */
data class EnvVarValue(
    val name: String,
    val value: ByteArray
)

/**
 * Outcome of requesting env vars from a server, for the session
 * UI. NAMES ONLY — a value must never appear here, in a log, or
 * in diagnostics, even when a request fails. "Applied" means the
 * server did not reject the request (sshj awaits the reply and a
 * rejection raises); it does not prove the shell's login profile
 * kept the value — the shell itself is the final truth, and the
 * UI says so.
 */
data class EnvApplyResult(
    val appliedNames: List<String> = emptyList(),
    val rejectedNames: List<String> = emptyList()
) {
    /** Names the user configured, in configured order. */
    val configuredNames: List<String>
        get() = appliedNames + rejectedNames
}
