package com.pishi.termbridge.domain

import com.pishi.termbridge.transport.RemotePath

/**
 * Prompt 09 validation, pure Kotlin, JVM-tested. Validation lives
 * here (and is re-enforced in the repository), never only in the
 * UI, so no screen can save an invalid entry by skipping a check.
 *
 * Env values are treated as sensitive: they are never validated
 * by content (no secret scan, no logging of a match) — only by
 * shape (length, NUL). A value that looks like a secret is still
 * a legitimate env value (that is exactly what env vars are for);
 * the protection is encryption at rest, not content judgement.
 */
object EnvironmentValidator {
    private val envNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Loader-injection names a permissive server must never be
     *  handed by this app, even if the user types them. This is a
     *  safety blocklist, not a judgement on other names: PATH and
     *  similar are allowed because overriding them is a legitimate
     *  user choice on their own server. */
    val blockedEnvNames: Set<String> = setOf(
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "LD_AUDIT",
        "LD_DEBUG",
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH",
        "DYLD_FALLBACK_LIBRARY_PATH"
    )

    const val MAX_ENV_NAME_LENGTH = 128
    const val MAX_ENV_VALUE_LENGTH = 4096

    fun isValidEnvName(name: String): Boolean =
        name.length in 1..MAX_ENV_NAME_LENGTH &&
            envNameRegex.matches(name) &&
            name !in blockedEnvNames

    fun validateEnvName(name: String): ValidationIssue? = when {
        name.isBlank() -> ValidationIssue("name", "Variable name must not be blank.")
        name.length > MAX_ENV_NAME_LENGTH ->
            ValidationIssue("name", "Variable name is too long.")
        !envNameRegex.matches(name) ->
            ValidationIssue(
                "name",
                "Variable names must start with a letter or underscore, " +
                    "and contain only letters, digits, and underscores."
            )
        name in blockedEnvNames ->
            ValidationIssue("name", "This variable name is blocked for safety.")
        else -> null
    }

    fun validateEnvValue(value: String): ValidationIssue? = when {
        '\u0000' in value ->
            ValidationIssue("value", "Variable value must not contain NUL characters.")
        value.length > MAX_ENV_VALUE_LENGTH ->
            ValidationIssue("value", "Variable value is too long.")
        else -> null
    }

    /**
     * Validate a remote working directory for the given shell.
     * "" means unset and is always valid. A set directory must be
     * absolute in its own dialect and must normalise; the stored
     * form is the normalised form, not the raw text.
     */
    fun validateRemoteDirectory(directory: String, shell: RemoteShell): ValidationIssue? {
        if (directory.isBlank()) return null
        val normalised = canonicalDirectory(directory, shell)
        return if (normalised == null) {
            ValidationIssue(
                "remoteWorkingDirectory",
                when (shell) {
                    RemoteShell.POSIX ->
                        "Working directory must be an absolute POSIX path, e.g. /home/user."
                    else ->
                        "Working directory must be an absolute Windows path, " +
                            "e.g. C:\\Users\\name or \\\\server\\share\\dir."
                }
            )
        } else {
            null
        }
    }

    /** Canonical form of a directory for storage, or null if invalid. */
    fun canonicalDirectory(directory: String, shell: RemoteShell): String? {
        if (directory.isBlank()) return ""
        // Review #2 F-03 / F-22: this text is TYPED into the shell on
        // every connect, so control characters are refused here (the
        // POSIX normaliser is shared with SFTP and must stay
        // permissive about odd remote file names), and `%` is refused
        // for cmd.exe, which expands %VAR% even inside double quotes.
        if (ControlChars.containsControl(directory)) return null
        if (shell == RemoteShell.CMD && '%' in directory) return null
        return when (shell) {
            RemoteShell.POSIX -> RemotePath.normalize(directory)
            RemoteShell.POWERSHELL, RemoteShell.CMD -> WindowsPath.normalize(directory)
        }
    }

    /**
     * Local directories are SAF tree URIs only. A filesystem path
     * (e.g. /storage/...) is rejected: using it would need broad
     * storage access, which this app deliberately does not have.
     * Null/blank means unset and is always valid.
     */
    fun validateLocalDirectoryUri(uri: String?): ValidationIssue? = when {
        uri.isNullOrBlank() -> null
        '\u0000' in uri ->
            ValidationIssue("localDirectoryUri", "Local folder must not contain NUL characters.")
        !uri.startsWith("content://") ->
            ValidationIssue(
                "localDirectoryUri",
                "Local folders are picked through the system file picker, " +
                    "not typed as filesystem paths."
            )
        else -> null
    }
}
