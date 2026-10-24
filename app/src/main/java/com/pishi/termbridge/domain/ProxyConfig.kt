package com.pishi.termbridge.domain

/**
 * Prompt 10 proxy configuration. Only one proxy mode is offered:
 *
 * - NONE: direct TCP connection (the default).
 * - SOCKS: unauthenticated SOCKS via the JDK's Socket(Proxy)
 *   mechanism, applied through sshj's SocketFactory (see
 *   SshTransport). The proxy is used for the INITIAL TCP
 *   connection only; jump hops ride the established tunnel.
 *
 * Deliberately NOT offered, with reasons (see docs/ADVANCED_SSH.md):
 * - HTTP CONNECT proxy: no standard-library support; a hand-rolled
 *   CONNECT handshake is unproven protocol code, not shipped.
 * - ProxyCommand: sshj has no such API; bridging a local process's
 *   stdio to a socket is unproven here, and the app spawns no
 *   processes.
 * - Authenticated SOCKS: java.net.Authenticator is JVM-global —
 *   credentials set for one connection would leak into every other
 *   connection in the process. Not an acceptable credential path.
 */
enum class ProxyKind {
    NONE,
    SOCKS
}

data class ProxyConfig(
    val hostId: Long = 0L,
    val kind: ProxyKind = ProxyKind.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 0
)

/** Result of parsing a proxy specification string. */
data class ProxyParseResult(
    val config: ProxyConfig?,
    val error: String?
)

/**
 * Parses user-typed proxy specifications into [ProxyConfig].
 * Accepted forms: "socks://host:port", "socks5://host:port", and
 * bare "host:port" (documented default: SOCKS). Anything with an
 * http/https scheme is rejected with an explicit "not supported"
 * message — never silently reinterpreted as SOCKS.
 */
object ProxySpecParser {
    private val hostChars = Regex("^[A-Za-z0-9._\\-]+$")

    fun parse(spec: String, hostId: Long): ProxyParseResult {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            return ProxyParseResult(null, "Proxy specification must not be blank.")
        }
        val hostPort = when {
            trimmed.startsWith("socks://") -> trimmed.removePrefix("socks://")
            trimmed.startsWith("socks5://") -> trimmed.removePrefix("socks5://")
            trimmed.contains("://") -> {
                return ProxyParseResult(
                    null,
                    "Only SOCKS proxies are supported. HTTP proxies are not supported."
                )
            }
            else -> trimmed
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0 || colon == hostPort.length - 1) {
            return ProxyParseResult(null, "Proxy must be in host:port form.")
        }
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull()
        if (!hostChars.matches(host)) {
            return ProxyParseResult(null, "Proxy host contains invalid characters.")
        }
        if (port == null || port !in 1..65535) {
            return ProxyParseResult(null, "Proxy port must be between 1 and 65535.")
        }
        return ProxyParseResult(ProxyConfig(hostId, ProxyKind.SOCKS, host, port), null)
    }

    fun format(config: ProxyConfig): String = when (config.kind) {
        ProxyKind.NONE -> ""
        ProxyKind.SOCKS -> "socks://${config.proxyHost}:${config.proxyPort}"
    }

    fun validate(config: ProxyConfig): List<ValidationIssue> {
        if (config.kind == ProxyKind.NONE) return emptyList()
        val issues = mutableListOf<ValidationIssue>()
        if (config.proxyHost.isBlank()) {
            issues += ValidationIssue("proxyHost", "Proxy host must not be blank.")
        }
        if (config.proxyPort !in 1..65535) {
            issues += ValidationIssue("proxyPort", "Proxy port must be between 1 and 65535.")
        }
        return issues
    }
}
