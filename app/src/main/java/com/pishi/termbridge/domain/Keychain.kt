package com.pishi.termbridge.domain

/**
 * One named entry in the keychain (item T-01): a reusable
 * credential — a password or a private key — that any number of
 * hosts can reference instead of each storing its own copy.
 *
 * No secret value lives here: the material itself is stored
 * only as a Keystore-encrypted blob in the data layer (see
 * data/KeychainRepository). [fingerprint] is the OpenSSH-style
 * SHA256 fingerprint of the entry's public key (private-key
 * entries only); [publicKeyText] is the public key in OpenSSH
 * text form, safe to display — it is not secret material.
 */
data class KeychainEntry(
    val id: Long = 0L,
    val label: String,
    val kind: AuthType,
    val publicKeyText: String? = null,
    val fingerprint: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** Label + shape rules for keychain entries, enforced in the
 *  repository before any write (the UI shows the same issues
 *  next to the field). Labels are names the user picks from a
 *  list, so they stay short, single-line and control-free —
 *  a label is never typed into a shell, but it IS rendered in
 *  lists and dialogs where control characters would corrupt
 *  the display. */
object KeychainValidator {
    const val MAX_LABEL_LENGTH = 64

    fun validateLabel(label: String): List<ValidationIssue> {
        val trimmed = label.trim()
        return when {
            trimmed.isEmpty() ->
                listOf(ValidationIssue("label", "A label is required."))
            trimmed.length > MAX_LABEL_LENGTH ->
                listOf(
                    ValidationIssue(
                        "label",
                        "Label must be at most $MAX_LABEL_LENGTH characters."
                    )
                )
            trimmed.any { ControlChars.isControl(it) } ->
                listOf(
                    ValidationIssue(
                        "label",
                        "Label must not contain control characters."
                    )
                )
            else -> emptyList()
        }
    }

    /** The credential offered for an entry must match the
     *  entry's kind — a password blob under a key entry (or
     *  vice versa) is the same type confusion HostRepository
     *  refuses for hosts. A null credential is allowed only
     *  when [hasUsableMaterial] (an edit keeping the existing
     *  material). */
    fun validateCredential(
        kind: AuthType,
        credential: CredentialInput?,
        hasUsableMaterial: Boolean
    ): List<ValidationIssue> {
        if (credential == null) {
            return if (hasUsableMaterial) {
                emptyList()
            } else {
                listOf(
                    ValidationIssue(
                        "credential",
                        "A credential is required for a new keychain entry."
                    )
                )
            }
        }
        val matches = when (credential) {
            is CredentialInput.Password -> kind == AuthType.PASSWORD
            is CredentialInput.PrivateKey -> kind == AuthType.PRIVATE_KEY
        }
        return if (matches) {
            emptyList()
        } else {
            listOf(
                ValidationIssue(
                    "credential",
                    "The credential type does not match the entry type."
                )
            )
        }
    }
}
