package com.pishi.termbridge.transport

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.ProxyConfig

/** Trust store as seen by the transport. Implemented by HostRepository. */
interface HostKeyTrustStore {
    suspend fun getTrustedFingerprint(endpoint: String): String?

    /** Audit #6 TB-004: the stored host key's algorithm name
     *  (e.g. "ssh-ed25519"), used to prefer the already-trusted
     *  algorithm during key exchange. Default null keeps test
     *  doubles source-compatible. */
    suspend fun getTrustedKeyType(endpoint: String): String? = null
    suspend fun trustHostKey(endpoint: String, fingerprint: String, keyType: String)
    suspend fun clearTrustedKey(endpoint: String)
}

/**
 * A trust decision the UI must make. Called from the transport's
 * connecting thread (via runBlocking in the sshj verifier) and
 * suspended until the user answers — the connecting thread blocks,
 * the main thread stays free, so no deadlock. A cancelled or
 * dismissed decision must answer false.
 */
interface HostKeyDecisionHandler {
    suspend fun decideUnknown(pending: PendingTrust): Boolean
    suspend fun decideChanged(pending: PendingTrust): Boolean
}

/** Everything a connect attempt needs, in one place. */
data class SshConnectionRequest(
    val profile: HostProfile,
    /** Password bytes (password auth) or PEM bytes (key auth). The
     *  caller wipes these after connect returns. */
    val credential: ByteArray,
    /** Passphrase for an encrypted private key, if any. The caller
     *  wipes it after connect returns. Never stored. */
    val passphrase: CharArray? = null,
    val connectTimeoutMs: Int = 15_000,
    val socketTimeoutMs: Int = 30_000,
    /** SSH keep-alive interval in seconds; 0 = off (a user setting). */
    val keepAliveSeconds: Int = 0,
    /** Unanswered keep-alive probes in a row before the app
     *  drops the connection itself (item T-14). */
    val keepAliveMaxMisses: Int = 3,
    /** Prompt 10: SOCKS proxy for the initial TCP connection, or
     *  NONE. A proxy is never applied per jump hop — hops ride the
     *  established tunnel. */
    val proxy: ProxyConfig = ProxyConfig()
)

/** One jump hop for a chain: same shape as a target request, so a
 *  hop's credential follows exactly the same load/wipe rules as a
 *  target's (see SshTransport.connectChain). */
data class JumpHopRequest(
    val profile: HostProfile,
    val credential: ByteArray,
    val passphrase: CharArray? = null,
    val connectTimeoutMs: Int = 15_000,
    val socketTimeoutMs: Int = 30_000
)

/**
 * An established SSH transport connection. This is transport-only:
 * prompt 05 opens NO shell channel and NO SFTP subsystem — those
 * arrive in prompts 06/07. The connection stays open until
 * disconnect() and must be closed deterministically (finally block
 * or ViewModel cleanup), because an SSHClient holds a socket and
 * threads.
 *
 * The underlying sshj client is intentionally NOT exposed to UI
 * code: future transport features (terminal, SFTP) live in this
 * package and use [SshjConnection.sshClient] through internal
 * visibility. UI code must go through the ViewModel, never the client.
 */
interface SshConnection {
    val profile: HostProfile
    val isConnected: Boolean
    fun disconnect()
}
