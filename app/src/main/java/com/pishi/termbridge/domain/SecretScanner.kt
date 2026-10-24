package com.pishi.termbridge.domain

/**
 * Prompt 08 obvious-secret heuristic for saved snippets.
 *
 * Snippets must not become a secret store. On save, a match BLOCKS
 * the save (the UI shows the matched pattern label, never the
 * secret itself). This is a heuristic, not perfect detection:
 * it cannot see secrets hidden behind variables, encoding, or
 * indirection, and it may flag a benign-looking string. The UI
 * and the phase report say so; no claim of perfect detection is
 * made anywhere.
 *
 * Labels are pattern names only — a scan result must never be
 * used to echo the matched secret into a log or error message.
 */
object SecretScanner {
    private val patterns: List<Pair<String, Regex>> = listOf(
        "private key material" to Regex(
            "-----BEGIN[ A-Z]*PRIVATE KEY-----",
            RegexOption.IGNORE_CASE
        ),
        "password or secret assignment" to Regex(
            """(?i)\b(passwd|password|secret|token|api[-_]?key)\s*=\s*\S+"""
        ),
        "bearer token" to Regex(
            """(?i)\bBearer\s+[A-Za-z0-9._\-]+"""
        ),
        "GitHub token" to Regex(
            """\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"""
        ),
        "AWS access key" to Regex(
            """\bAKIA[0-9A-Z]{16}"""
        ),
        "generic credential flag" to Regex(
            """(?i)--(password|token|api[-_]?key)\s+\S+"""
        )
    )

    /** Pattern labels found in [text], empty when nothing matches. */
    fun scan(text: String): List<String> =
        patterns.filter { (_, regex) -> regex.containsMatchIn(text) }.map { it.first }

    fun scanButton(button: CommandButton): List<String> {
        val found = linkedSetOf<String>()
        found += scan(button.template)
        for (step in button.steps) found += scan(step)
        // Defaults are stored with the button, so they are scanned
        // too — a "default password" is exactly the mistake this
        // check exists to catch.
        for (variable in button.variables) found += scan(variable.defaultValue)
        return found.toList()
    }
}
