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
    /**
     * Remediation phase 2: scrub free-form text that may carry
     * an unknown secret — e.g. a dependency's exception message
     * surfaced in an import warning. Unlike [redact], there is
     * no known secret to replace: if the text matches any
     * obvious-secret pattern, the WHOLE message is withheld
     * rather than partially redacted, because partial redaction
     * of an unknown-format secret can leak fragments. Benign
     * messages pass through unchanged.
     */
    fun scrubFreeText(text: String): String =
        if (SecretScanner.scan(text).isEmpty()) {
            text
        } else {
            "Details withheld: the error message may contain sensitive text."
        }

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
