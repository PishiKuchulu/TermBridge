package com.pishi.termbridge.domain

/**
 * Prompt 10 tunnel configuration model. One model for both kinds,
 * with kind-specific meaning for the same fields — this is
 * deliberate, and the meaning is documented here, not left to
 * guesswork in the UI:
 *
 * - LOCAL: the app listens on [bindAddress]:[bindPort] on THIS
 *   device and forwards each accepted connection through the SSH
 *   connection to [targetHost]:[targetPort] as seen FROM THE
 *   SERVER (usually "localhost" or an internal host).
 * - REMOTE: the SERVER listens on [bindAddress]:[bindPort] and
 *   forwards each accepted connection back through the SSH
 *   connection to [targetHost]:[targetPort] on THIS device
 *   (usually "127.0.0.1" and a local service port).
 *
 * No credential, password, or key ever appears in this model.
 * Tunnels are per-host (hostId) and non-secret, so duplicating a
 * host copies its tunnels (see HostRepository) — unlike
 * credentials, which are never copied.
 */
enum class TunnelKind {
    LOCAL,
    REMOTE
}

data class TunnelConfig(
    val id: Long = 0L,
    val hostId: Long,
    val kind: TunnelKind,
    /** Listener bind address. Default is loopback — see validator. */
    val bindAddress: String = "127.0.0.1",
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
    /**
     * Explicit opt-in to bind beyond loopback (e.g. "0.0.0.0").
     * Without this flag, a non-loopback bind address is rejected
     * at save time — a tunnel must never silently expose a local
     * service to a network the user did not choose.
     */
    val allowNonLoopback: Boolean = false,
    /** Only enabled tunnels start when the tunnel session starts. */
    val enabled: Boolean = true,
    val label: String = ""
)

/** Pure tunnel validation — no Android dependency, JVM-testable. */
object TunnelValidator {
    private val loopbackAddresses = setOf(
        "127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1"
    )
    private val wildcardAddresses = setOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0")
    private val hostChars = Regex("^[A-Za-z0-9._\\-\\[\\]:]+$")

    fun isLoopback(address: String): Boolean =
        loopbackAddresses.contains(address.lowercase())

    fun isWildcard(address: String): Boolean =
        wildcardAddresses.contains(address.lowercase())

    fun validate(config: TunnelConfig): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val bind = config.bindAddress.trim()
        if (bind.isEmpty()) {
            issues += ValidationIssue("bindAddress", "Bind address must not be blank.")
        } else if (!isLoopback(bind) && !config.allowNonLoopback) {
            issues += ValidationIssue(
                "bindAddress",
                "A non-loopback bind address requires the explicit " +
                    "network-exposure option. Default is loopback only."
            )
        }
        if (config.bindPort !in 1..65535) {
            issues += ValidationIssue("bindPort", "Bind port must be between 1 and 65535.")
        }
        if (config.targetHost.isBlank()) {
            issues += ValidationIssue("targetHost", "Target host must not be blank.")
        } else if (!hostChars.matches(config.targetHost.trim())) {
            issues += ValidationIssue("targetHost", "Target host contains invalid characters.")
        }
        if (config.targetPort !in 1..65535) {
            issues += ValidationIssue("targetPort", "Target port must be between 1 and 65535.")
        }
        return issues
    }
}
