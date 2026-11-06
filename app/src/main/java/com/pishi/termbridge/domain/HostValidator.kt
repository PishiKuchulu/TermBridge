package com.pishi.termbridge.domain

/**
 * Pure host validation — no Android dependency, so it is fully
 * unit-testable on the JVM. This validates shape only (blank fields,
 * port range, hostname characters); it never validates that a host
 * is reachable, and it never touches a credential value.
 */
object HostValidator {
    private val hostnameChars = Regex("^[A-Za-z0-9._\\-\\[\\]:]+$")

    /**
     * Audit #3 (A-15): the ASCII (punycode) form of a hostname
     * the user typed. Each dot-separated label goes through
     * [java.net.IDN.toASCII]; already-ASCII labels pass through
     * unchanged, and IP literals (anything containing ':' or
     * brackets) are returned as-is — IDN conversion is for
     * names, not addresses. A label IDN cannot convert is kept
     * verbatim, so validation below rejects it with the usual
     * message instead of this function throwing. Validation,
     * storage, and dialling all use this converted form, so a
     * Persian-script domain is accepted and stored as its
     * `xn--` name.
     */
    fun asciiHostname(hostname: String): String {
        if (hostname.contains(':') || hostname.contains('[') || hostname.contains(']')) {
            return hostname
        }
        return hostname.split('.').joinToString(".") { label ->
            if (label.isEmpty() || label.all { it.code < 128 }) {
                label
            } else {
                try {
                    java.net.IDN.toASCII(label)
                } catch (_: Exception) {
                    label
                }
            }
        }
    }

    fun validate(profile: HostProfile): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (profile.name.isBlank()) {
            issues += ValidationIssue("name", "Host name must not be blank.")
        }
        if (profile.hostname.isBlank()) {
            issues += ValidationIssue("hostname", "Hostname must not be blank.")
        } else {
            // Audit #3 (A-15): the shape and length rules apply
            // to the converted (punycode) form — that is what
            // gets stored and dialled.
            val ascii = asciiHostname(profile.hostname)
            if (!hostnameChars.matches(ascii) || ascii.length > 253) {
                issues += ValidationIssue("hostname", "Hostname contains invalid characters.")
            }
        }
        if (profile.port !in 1..65535) {
            issues += ValidationIssue("port", "Port must be between 1 and 65535.")
        }
        if (profile.username.isBlank()) {
            issues += ValidationIssue("username", "Username must not be blank.")
        } else if (profile.username.any { it.isWhitespace() }) {
            issues += ValidationIssue("username", "Username must not contain spaces.")
        }
        return issues
    }

    /**
     * Credential-shape validation for a save. [existingCredential]
     * is true when editing a host that already has a stored secret
     * and the user left the credential fields blank — in that case
     * the existing secret is kept and no new credential is required.
     * A private-key passphrase is never validated here as a stored
     * value: it is not persisted (see docs/SECURITY_KEY_LIFECYCLE.md).
     */
    fun validateCredential(
        authType: AuthType,
        credential: CredentialInput?,
        existingCredential: Boolean
    ): List<ValidationIssue> {
        if (credential == null) {
            return if (existingCredential) {
                emptyList()
            } else {
                listOf(ValidationIssue("credential", "A credential is required for a new host."))
            }
        }
        return when (credential) {
            is CredentialInput.Password -> {
                if (credential.password.isEmpty()) {
                    listOf(ValidationIssue("credential", "Password must not be blank."))
                } else {
                    emptyList()
                }
            }
            is CredentialInput.PrivateKey -> {
                val pem = credential.keyPem.trim()
                if (!pem.startsWith("-----BEGIN") || !pem.contains("PRIVATE KEY")) {
                    listOf(
                        ValidationIssue(
                            "credential",
                            "Private key must be a PEM private key."
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }
    }
}
