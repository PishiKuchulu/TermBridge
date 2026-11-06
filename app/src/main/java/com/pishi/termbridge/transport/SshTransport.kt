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

    /**
     * Audit #3 (A-04): SSH clients whose connect attempt is
     * still in flight (created, handshake/auth not finished).
     * The blocking sshj calls ignore coroutine cancellation, so
     * without this registry a cancelled attempt's socket stayed
     * open until the handshake timeout. Membership is strictly
     * attempt-scoped: a client is added at creation and removed
     * when its attempt returns or fails, so an ESTABLISHED
     * connection is never in here (it is closed through the
     * normal SshConnection path instead).
     */
    private val inFlightClients =
        java.util.Collections.synchronizedSet(mutableSetOf<SSHClient>())

    /**
     * Audit #3 (A-04): tear down every in-flight attempt's
     * socket, best-effort. Disconnecting a client that is blocked
     * inside connect/auth makes that blocking call fail promptly,
     * which is what lets a user cancel during CONNECTING actually
     * free the socket. Safe to call at any time; catches
     * everything so a teardown failure can never mask the
     * caller's own state handling.
     */
    fun closeInFlight() {
        val snapshot = synchronized(inFlightClients) { inFlightClients.toList() }
        for (client in snapshot) {
            try {
                client.disconnect()
            } catch (_: Exception) {
                // Best-effort: teardown must not mask anything.
            }
        }
    }

    suspend fun connect(
        request: SshConnectionRequest,
        decisionHandler: HostKeyDecisionHandler
    ): SshConnection {
        val profile = request.profile
        val storedFingerprint = trustStore.getTrustedFingerprint(profile.endpoint)
        val client = SSHClient()
        inFlightClients += client
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
                val connection = SshjConnection(profile, client)
                if (request.keepAliveSeconds > 0) {
                    connection.startKeepAlive(
                        request.keepAliveSeconds, request.keepAliveMaxMisses
                    )
                }
                connection
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
        } finally {
            // Attempt finished (success or failure): the client
            // is either an established connection now or already
            // disconnected — either way it leaves the registry.
            inFlightClients -= client
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
                    inFlightClients += client
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
                inFlightClients += targetClient
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
                val chained = ChainedSshConnection(target.profile, clients)
                if (target.keepAliveSeconds > 0) {
                    chained.startKeepAlive(
                        target.keepAliveSeconds, target.keepAliveMaxMisses
                    )
                }
                chained
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
        } finally {
            // Attempt finished: every client of the chain leaves
            // the in-flight registry (see connect()).
            inFlightClients.removeAll(clients)
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
        // Item R-01 (review #2 F-13, PROVEN against the pinned
        // sshj jar): KeyExchanger.waitForDone() awaits the kex
        // "done" event with transport.getTimeoutMs(), and the
        // host-key verifier runs synchronously on the reader
        // thread inside that window — our verifier blocks there
        // until the user decides on the trust dialog. With the
        // regular 30 s transport timeout, a user who reads the
        // fingerprint for longer than that finds the connection
        // already dead when they tap Trust. So the handshake
        // phase runs with a human-scale timeout; the caller's
        // socketTimeoutMs is restored right after auth, before
        // this function returns, and governs the session from
        // then on.
        client.setTimeout(HANDSHAKE_TIMEOUT_MS)
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
        // Item R-01: handshake over — restore the session's
        // regular transport timeout (see the note at the top of
        // this function).
        client.setTimeout(socketTimeoutMs)
        return pendingTrust
    }

    companion object {
        /**
         * Transport timeout for the connect + kex + auth phase
         * only (item R-01). It exists so a person can read a
         * host-key fingerprint and decide without racing a
         * 30-second clock; it is still bounded, so an
         * unattended dialog cannot hold a half-open connection
         * forever.
         */
        const val HANDSHAKE_TIMEOUT_MS = 300_000
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

/**
 * The app's own keep-alive probe loop (item T-14): sshj's
 * built-in keep-alive has an interval but no max-unanswered
 * count, so this loop sends a `keepalive@openssh.com` global
 * request every interval and counts consecutive misses with
 * [com.pishi.termbridge.domain.KeepAlivePolicy]; at the limit
 * it disconnects. Any reply packet — success or failure —
 * counts as alive (some servers answer failure to the request
 * name but are plainly up). The thread is a daemon and stops
 * on disconnect.
 */
internal class KeepAliveRunner(
    private val client: SSHClient,
    private val intervalSeconds: Int,
    maxMisses: Int,
    private val onGiveUp: () -> Unit
) {
    private val policy = com.pishi.termbridge.domain.KeepAlivePolicy(maxMisses)

    @Volatile
    private var thread: Thread? = null

    fun start() {
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(intervalSeconds * 1000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!client.isConnected) break
                if (probe()) {
                    policy.onReply()
                } else if (policy.onMiss()) {
                    onGiveUp()
                    break
                }
            }
        }, "termbridge-keepalive")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun probe(): Boolean = try {
        val timeoutMs = try {
            client.connection.timeoutMs
        } catch (_: Exception) {
            30_000
        }
        val promise = client.connection.sendGlobalRequest(
            "keepalive@openssh.com", true, byteArrayOf()
        )
        promise.tryRetrieve(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) != null
    } catch (_: Exception) {
        false
    }
}

/** Transport-internal connection holder. UI must not use sshClient. */
/**
 * Items T-11/T-16: one-shot command execution over a SEPARATE
 * session channel on an already-authenticated connection —
 * never typed into the interactive shell. Both connection
 * holders implement it; the chained variant runs on the
 * target client (the end of the chain).
 */
interface SshExecChannel {
    /**
     * Runs [command] and returns its stdout as UTF-8 text,
     * capped at [EXEC_OUTPUT_CAP] bytes. The exit status is
     * deliberately NOT an error: probe commands legitimately
     * fail on some servers, and interpreting output is the
     * caller's job. Transport failures throw.
     */
    suspend fun execOnce(command: String, timeoutMs: Long = 10_000): String

    companion object {
        const val EXEC_OUTPUT_CAP = 512 * 1024
    }
}

/** Shared exec implementation over one sshj client. */
internal suspend fun execOnceOn(
    client: SSHClient,
    command: String,
    timeoutMs: Long
): String = withContext(Dispatchers.IO) {
    client.startSession().use { session ->
        val cmd = session.exec(command)
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        val input = cmd.inputStream
        while (total < SshExecChannel.EXEC_OUTPUT_CAP) {
            val read = input.read(
                chunk, 0, minOf(chunk.size, SshExecChannel.EXEC_OUTPUT_CAP - total)
            )
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        try {
            cmd.join(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // A probe that outlives its budget yields whatever
            // stdout arrived; the caller interprets it.
        }
        buffer.toString("UTF-8")
    }
}

class SshjConnection(
    override val profile: HostProfile,
    internal val sshClient: SSHClient
) : SshConnection, SshExecChannel {
    override suspend fun execOnce(command: String, timeoutMs: Long): String =
        execOnceOn(sshClient, command, timeoutMs)
    private var keepAlive: KeepAliveRunner? = null

    override val isConnected: Boolean
        get() = try {
            sshClient.isConnected
        } catch (_: Exception) {
            false
        }

    fun startKeepAlive(intervalSeconds: Int, maxMisses: Int) {
        if (intervalSeconds <= 0) return
        keepAlive?.stop()
        keepAlive = KeepAliveRunner(sshClient, intervalSeconds, maxMisses) { disconnect() }
            .also { it.start() }
    }

    override fun disconnect() {
        keepAlive?.stop()
        keepAlive = null
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
) : SshConnection, SshExecChannel {
    override suspend fun execOnce(command: String, timeoutMs: Long): String {
        val target = clients.lastOrNull()
            ?: throw SshTransportException(SshError.NetworkError)
        return execOnceOn(target, command, timeoutMs)
    }
    private var keepAlive: KeepAliveRunner? = null

    override val isConnected: Boolean
        get() = try {
            clients.lastOrNull()?.isConnected == true
        } catch (_: Exception) {
            false
        }

    /** Probes ride the target client: if the end of the chain
     *  answers, every hop under it is carrying traffic. */
    fun startKeepAlive(intervalSeconds: Int, maxMisses: Int) {
        if (intervalSeconds <= 0) return
        val target = clients.lastOrNull() ?: return
        keepAlive?.stop()
        keepAlive = KeepAliveRunner(target, intervalSeconds, maxMisses) { disconnect() }
            .also { it.start() }
    }

    override fun disconnect() {
        keepAlive?.stop()
        keepAlive = null
        for (client in clients.asReversed()) {
            try {
                client.disconnect()
            } catch (_: Exception) {
                // Disconnect is best-effort and idempotent.
            }
        }
    }
}
