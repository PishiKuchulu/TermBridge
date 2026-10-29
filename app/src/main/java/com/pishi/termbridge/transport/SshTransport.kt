package com.pishi.termbridge.transport

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.ProxyKind
import java.security.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * sshj-backed SSH transport. All blocking sshj calls run on
 * Dispatchers.IO — never the main thread. Resource cleanup is
 * deterministic: on any failure after client creation, the client
 * is disconnected before the error propagates, so a failed attempt
 * never leaks a socket.
 *
 * Host-key flow: the stored fingerprint is loaded BEFORE connect
 * (the sshj verifier is synchronous, so it cannot call a suspend
 * DAO). The verifier delegates to HostKeyVerificationPolicy; for
 * UNKNOWN/CHANGED it blocks its thread in runBlocking until the
 * UI decision handler answers. A trust record is persisted ONLY
 * after the whole connection (including authentication) succeeds —
 * a failed connection must never overwrite a trusted fingerprint.
 *
 * Strict key exchange / Terrapin (security fix 16, M4 —
 *   verified, no configuration needed): sshj 0.40.0 implements
 *   strict kex internally and enables it by default. Evidence
 *   from the published 0.40.0 jar itself (Maven Central,
 *   com.hierynomus:sshj:0.40.0, SHA-256 a6a5533f…74d1e2):
 *   KeyExchanger carries a strictKex state machine
 *   ("Enabling strict key exchange extension", KEXINIT-first
 *   enforcement), and Proposal's client constructor appends
 *   kex-strict-c-v00@openssh.com on the initial exchange
 *   without any app configuration. No code sets it because
 *   there is nothing to set. Caveat, stated honestly: strict
 *   kex protects a connection only when the SERVER also
 *   supports it — against an old server the mitigation does
 *   not engage, and that is a property of the protocol, not
 *   of this app. A dependency-vulnerability scan still has
 *   never run (no CI yet); that half of M4 remains a release
 *   gate, not a claim.
 *
 * Prompt 10 additions (same rules, extended):
 * - SOCKS proxy: applied to the initial TCP connection only, via
 *   SocksSocketFactory set before connect (sshj has no proxy API
 *   of its own in 0.40.0).
 * - Jump chains (connectChain): each hop is authenticated with
 *   its own host-key verification (its own stored fingerprint, its
 *   own decision dialog), reached through the previous hop's
 *   direct-tcpip channel (sshj connectVia, verified in the 0.40.0
 *   jar). Trust records for ALL hops are persisted only after the
 *   target authenticates — a failure anywhere in the chain
 *   persists nothing and disconnects every client opened so far,
 *   in reverse order.
 */
class SshTransport(private val trustStore: HostKeyTrustStore) {
    suspend fun connect(
        request: SshConnectionRequest,
        decisionHandler: HostKeyDecisionHandler
    ): SshConnection {
        val profile = request.profile
        val storedFingerprint = trustStore.getTrustedFingerprint(profile.endpoint)
        val client = SSHClient()
        try {
            return withContext(Dispatchers.IO) {
                if (request.proxy.kind == ProxyKind.SOCKS) {
                    client.setSocketFactory(
                        SocksSocketFactory(request.proxy.proxyHost, request.proxy.proxyPort)
                    )
                }
                val pendingTrust = authenticateClient(
                    client = client,
                    profile = profile,
                    credential = request.credential,
                    passphrase = request.passphrase,
                    connectTimeoutMs = request.connectTimeoutMs,
                    socketTimeoutMs = request.socketTimeoutMs,
                    storedFingerprint = storedFingerprint,
                    decisionHandler = decisionHandler,
                    connectAction = { client.connect(profile.hostname, profile.port) }
                )
                // Auth succeeded: only now persist an accepted trust record.
                pendingTrust?.let {
                    trustStore.trustHostKey(it.endpoint, it.fingerprint, it.keyType)
                }
                SshjConnection(profile, client)
            }
        } catch (e: Throwable) {
            // Deterministic cleanup on failure — including a rejected
            // host key, a failed auth, and a cancelled attempt.
            try {
                client.disconnect()
            } catch (_: Exception) {
                // Cleanup must not mask the original error.
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw SshTransportException(SshErrorMapper.map(e), SshErrorMapper.diagnose(e))
        }
    }

    /**
     * Connect through an ordered jump chain to a target. [hops]
     * are in connection order (first contacted first); the target
     * is reached through the last hop. The SOCKS proxy, if any, is
     * taken from [target].proxy and applied to the FIRST hop's TCP
     * connection only — later hops ride channels, not new TCP
     * connections, so a per-hop proxy would be meaningless.
     *
     * Credentials and passphrases follow the prompt-05 rules per
     * hop: the caller loads them (Keystore-decrypted, never
     * stored) and wipes them after this returns. This function
     * never logs or stores them.
     */
    suspend fun connectChain(
        hops: List<JumpHopRequest>,
        target: SshConnectionRequest,
        decisionHandler: HostKeyDecisionHandler
    ): SshConnection {
        require(hops.isNotEmpty()) { "A jump chain needs at least one hop." }
        val allProfiles = hops.map { it.profile } + target.profile
        val storedFingerprints = allProfiles.associateWith { profile ->
            trustStore.getTrustedFingerprint(profile.endpoint)
        }
        val clients = mutableListOf<SSHClient>()
        try {
            return withContext(Dispatchers.IO) {
                var direct: DirectConnection? = null
                val pendingTrusts = mutableListOf<PendingTrust>()
                for ((index, hop) in hops.withIndex()) {
                    val client = SSHClient()
                    clients += client
                    if (index == 0 && target.proxy.kind == ProxyKind.SOCKS) {
                        client.setSocketFactory(
                            SocksSocketFactory(target.proxy.proxyHost, target.proxy.proxyPort)
                        )
                    }
                    val currentDirect = direct
                    val pending = authenticateClient(
                        client = client,
                        profile = hop.profile,
                        credential = hop.credential,
                        passphrase = hop.passphrase,
                        connectTimeoutMs = hop.connectTimeoutMs,
                        socketTimeoutMs = hop.socketTimeoutMs,
                        storedFingerprint = storedFingerprints.getValue(hop.profile),
                        decisionHandler = decisionHandler,
                        connectAction = {
                            if (currentDirect == null) {
                                client.connect(hop.profile.hostname, hop.profile.port)
                            } else {
                                client.connectVia(currentDirect)
                            }
                        }
                    )
                    pending?.let { pendingTrusts += it }
                    // Next leg: through this hop to the next hop,
                    // or to the target after the last hop.
                    val nextProfile = if (index < hops.lastIndex) {
                        hops[index + 1].profile
                    } else {
                        target.profile
                    }
                    direct = client.newDirectConnection(nextProfile.hostname, nextProfile.port)
                }
                val targetClient = SSHClient()
                clients += targetClient
                val targetDirect = direct
                    ?: throw SshTransportException(
                        SshErrorMapper.map(
                            IllegalStateException("Jump chain produced no channel.")
                        )
                    )
                val targetPending = authenticateClient(
                    client = targetClient,
                    profile = target.profile,
                    credential = target.credential,
                    passphrase = target.passphrase,
                    connectTimeoutMs = target.connectTimeoutMs,
                    socketTimeoutMs = target.socketTimeoutMs,
                    storedFingerprint = storedFingerprints.getValue(target.profile),
                    decisionHandler = decisionHandler,
                    connectAction = { targetClient.connectVia(targetDirect) }
                )
                targetPending?.let { pendingTrusts += it }
                // Whole chain authenticated: only now persist trusts.
                for (pending in pendingTrusts) {
                    trustStore.trustHostKey(pending.endpoint, pending.fingerprint, pending.keyType)
                }
                ChainedSshConnection(target.profile, clients)
            }
        } catch (e: Throwable) {
            // Partial failure: disconnect every client opened so
            // far, in reverse order, so no hop is left connected.
            for (client in clients.asReversed()) {
                try {
                    client.disconnect()
                } catch (_: Exception) {
                }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is SshTransportException) throw e
            throw SshTransportException(SshErrorMapper.map(e), SshErrorMapper.diagnose(e))
        }
    }

    /**
     * One client's verify + connect + auth, on the caller's IO
     * dispatcher. Returns the pending trust record (if the user
     * accepted an unknown/changed key); the caller persists it
     * only after the whole connection/chain succeeds.
     */
    private fun authenticateClient(
        client: SSHClient,
        profile: HostProfile,
        credential: ByteArray,
        passphrase: CharArray?,
        connectTimeoutMs: Int,
        socketTimeoutMs: Int,
        storedFingerprint: String?,
        decisionHandler: HostKeyDecisionHandler,
        connectAction: () -> Unit
    ): PendingTrust? {
        val endpoint = profile.endpoint
        var pendingTrust: PendingTrust? = null
        client.setConnectTimeout(connectTimeoutMs)
        client.setTimeout(socketTimeoutMs)
        client.addHostKeyVerifier(
            object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val fingerprint = HostKeyFingerprint.compute(key)
                    val keyType = HostKeyFingerprint.sshKeyType(key)
                    val outcome = HostKeyVerificationPolicy.verify(
                        storedFingerprint = storedFingerprint,
                        presentedFingerprint = fingerprint,
                        keyType = keyType,
                        endpoint = endpoint,
                        decideUnknown = { pending ->
                            kotlinx.coroutines.runBlocking {
                                decisionHandler.decideUnknown(pending)
                            }
                        },
                        decideChanged = { pending ->
                            kotlinx.coroutines.runBlocking {
                                decisionHandler.decideChanged(pending)
                            }
                        }
                    )
                    return when (outcome) {
                        is VerificationOutcome.Accept -> {
                            pendingTrust = outcome.pendingTrust
                            true
                        }
                        VerificationOutcome.Reject -> false
                    }
                }

                // sshj 0.40.0 requires this second verifier
                // method; this verifier tracks fingerprints,
                // not algorithms, so there is nothing stored
                // to report here.
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                    emptyList()
            }
        )
        connectAction()
        when (profile.authType) {
            AuthType.PASSWORD -> {
                val password = String(credential, Charsets.UTF_8)
                client.authPassword(profile.username, password)
            }
            AuthType.PRIVATE_KEY -> {
                val pem = String(credential, Charsets.UTF_8)
                // Review #1 (C-01): the two-argument loadKeys
                // overload takes a FILE LOCATION, not PEM text
                // (and NPEs on a null passphrase). The
                // three-argument overload parses the PEM content
                // itself — verified against sshj 0.40.0 bytecode
                // (detectKeyFileFormat sniffs "-----BEGIN" in the
                // string). The finder holds the request's own
                // passphrase array: it is used exactly once, during
                // this auth, and the caller wipes it in `finally`
                // below, so no copy is made (a copy would linger
                // if sshj did not wipe it).
                val finder = passphrase?.let { PasswordUtils.createOneOff(it) }
                val keyProvider = client.loadKeys(pem, null, finder)
                client.authPublickey(profile.username, keyProvider)
            }
        }
        return pendingTrust
    }
}

/**
 * Wraps a transport failure with a fixed, secret-free message.
 * Security fix 16 (L7): this exception deliberately carries NO
 * cause — the old version kept the sshj cause chain, whose
 * message could in principle echo user input or a crafted
 * server message, and which any future crash reporter would
 * ship whole. ViewModels display only error.userMessage, and
 * removing the parameter (rather than defaulting it) makes
 * re-attaching a cause a compile error, not a review catch.
 * SftpException keeps its cause parameter (same latent class,
 * deferred — see SECURITY_FIXES_16.md); it is not touched here.
 */
class SshTransportException(
    val error: SshError,
    /** Class/enum names only — see [SshErrorMapper.diagnose]. */
    val diagnostic: SshDiagnostic? = null
) : Exception(
    error.userMessage + (diagnostic?.let { " [${it.code}]" } ?: "")
) {
    /** The text to show the user: fixed message plus the secret-free code. */
    fun userFacingMessage(): String = message ?: error.userMessage
}

/** Transport-internal connection holder. UI must not use sshClient. */
class SshjConnection(
    override val profile: HostProfile,
    internal val sshClient: SSHClient
) : SshConnection {
    override val isConnected: Boolean
        get() = try {
            sshClient.isConnected
        } catch (_: Exception) {
            false
        }

    override fun disconnect() {
        try {
            sshClient.disconnect()
        } catch (_: Exception) {
            // Disconnect is best-effort and idempotent.
        }
    }
}

/**
 * A jump-chain connection: the target client plus every hop client
 * that keeps the chain alive. Disconnect closes all of them, in
 * reverse order (target first, first hop last), so no hop stays
 * connected after the user disconnects. The hop clients are
 * internal — UI code must never use them.
 */
class ChainedSshConnection(
    override val profile: HostProfile,
    internal val clients: List<SSHClient>
) : SshConnection {
    override val isConnected: Boolean
        get() = try {
            clients.lastOrNull()?.isConnected == true
        } catch (_: Exception) {
            false
        }

    override fun disconnect() {
        for (client in clients.asReversed()) {
            try {
                client.disconnect()
            } catch (_: Exception) {
                // Disconnect is best-effort and idempotent.
            }
        }
    }
}
