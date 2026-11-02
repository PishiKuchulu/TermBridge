package com.pishi.termbridge.domain

/** What a quick-connect line resolved to. */
data class QuickConnectTarget(
    val host: String,
    val port: Int,
    val username: String?
)

/**
 * Parses the one-line forms people type or paste:
 * `host`, `host:2222`, `user@host`, `user@host:2222`,
 * `ssh user@host`, `ssh user@host -p 2222`, `ssh -p 2222 user@host`
 * and `ssh -l user host`. Anything else returns null (never a
 * guess). Pure and unit-tested; the result only PRE-FILLS the add-host
 * form, so nothing is dialled and no credential is involved here.
 */
object QuickConnect {
    private val hostPattern = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")
    private val userPattern = Regex("^[A-Za-z0-9._][A-Za-z0-9._-]{0,63}$")

    fun parse(input: String): QuickConnectTarget? {
        var tokens = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.first().equals("ssh", ignoreCase = true)) tokens = tokens.drop(1)
        if (tokens.isEmpty()) return null

        var port: Int? = null
        var user: String? = null
        var target: String? = null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "-p" -> {
                    port = tokens.getOrNull(i + 1)?.toIntOrNull() ?: return null
                    i += 2
                }
                token.startsWith("-p") && token.length > 2 -> {
                    port = token.substring(2).toIntOrNull() ?: return null
                    i += 1
                }
                token == "-l" -> {
                    user = tokens.getOrNull(i + 1) ?: return null
                    i += 2
                }
                token.startsWith("-") -> return null
                target == null -> {
                    target = token
                    i += 1
                }
                else -> return null
            }
        }
        var hostPart = target ?: return null
        val at = hostPart.lastIndexOf('@')
        if (at >= 0) {
            user = hostPart.substring(0, at)
            hostPart = hostPart.substring(at + 1)
        }
        val colon = hostPart.lastIndexOf(':')
        if (colon >= 0) {
            if (port != null) return null
            port = hostPart.substring(colon + 1).toIntOrNull() ?: return null
            hostPart = hostPart.substring(0, colon)
        }
        if (!hostPattern.matches(hostPart)) return null
        if (user != null && !userPattern.matches(user)) return null
        val finalPort = port ?: 22
        if (finalPort !in 1..65535) return null
        return QuickConnectTarget(hostPart, finalPort, user)
    }
}
