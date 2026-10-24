package com.pishi.termbridge.domain

/**
 * Secret redaction for logs, diagnostics, and error messages.
 * Every error surfaced from the repository passes through here in
 * spirit: repository error messages are constructed without ever
 * interpolating a credential, and this helper is the second net for
 * any free-text field (e.g. a note or hostname echoed in an error).
 *
 * The helper never logs anything itself — it only returns a
 * redacted string. Callers must still never log the original.
 */
object SecretRedactor {
    fun redact(text: String, secrets: List<String>): String {
        var result = text
        for (secret in secrets) {
            if (secret.isNotEmpty()) {
                result = result.replace(secret, "***")
            }
        }
        return result
    }

    fun redactCharArray(text: String, secret: CharArray?): String {
        if (secret == null || secret.isEmpty()) return text
        return redact(text, listOf(String(secret)))
    }
}

/** Wipe a secret buffer in place. Call in a finally block. */
fun CharArray.wipe() {
    fill('\u0000')
}

/** Wipe a secret byte buffer in place. Call in a finally block. */
fun ByteArray.wipe() {
    fill(0)
}
