package com.pishi.termbridge.transport

import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener

/** A tunnel failure with a fixed, secret-free message. */
class TunnelException(message: String) : Exception(message)

/** One running forward, for the UI's active-tunnel list. */
data class ActiveTunnel(
    val configId: Long,
    val kind: TunnelKind,
    val bindAddress: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
    val label: String
)

/**
 * Prompt 10 tunnel manager, in the transport package so it can use
 * the sshj client (UI code must never touch it). One manager per
 * tunnel connection; the ViewModel owns it and calls [stopAll]
 * before disconnecting the client — closing the connection closes
 * its forwards, and the order matters: forwards first (so their
 * accept loops end cleanly), then the client.
 *
 * Local forwarding (verified against the 0.40.0 jar):
 * SSHClient.newLocalPortForwarder(Parameters, ServerSocket);
 * listen() blocks on the calling thread until close(), so it runs
 * on an IO job per tunnel and close() ends it.
 *
 * Remote forwarding (verified against the 0.40.0 jar):
 * client.remotePortForwarder.bind(Forward, listener); cancel()
 * unbinds. The listener forwards each incoming connection to the
 * local target with SocketForwardingConnectListener.
 *
 * The manager takes an SshConnection, not a raw SSHClient, and
 * resolves the client internally (this package can see the
 * internal client holders). UI/ViewModel code must never hold an
 * SSHClient — it hands the manager the connection it got from
 * SshTransport and lets the manager do the rest.
 *
 * Dynamic/SOCKS forwarding is NOT here: the 0.40.0 jar contains
 * no SOCKS server class, and writing one is unproven protocol
 * code — it stays blocked (see docs/ADVANCED_SSH.md), not faked
 * with a local forward.
 */
class SshTunnelManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Audit #6 TB-007: the four registries below are mutated from
    // caller threads, IO bodies and the per-tunnel listen jobs
    // with (previously) no synchronisation at all — a stop()
    // racing a listen-loop death could lose updates or throw
    // ConcurrentModificationException during iteration. Every
    // access now goes through this one monitor; blocking calls
    // (close / cancel) are made on snapshots taken under the lock,
    // never while holding it.
    private val registryLock = Any()
    private val localForwarders = mutableMapOf<Long, LocalPortForwarder>()
    private val localJobs = mutableMapOf<Long, Job>()
    private val remoteForwards = mutableMapOf<Long, RemotePortForwarder.Forward>()
    private val activeMap = mutableMapOf<Long, ActiveTunnel>()

    private val _active = MutableStateFlow<List<ActiveTunnel>>(emptyList())
    val active: StateFlow<List<ActiveTunnel>> = _active.asStateFlow()

    fun getActive(): List<ActiveTunnel> = _active.value

    /** Resolve the sshj client for a transport connection. Hop
     *  clients in a chain are not usable for tunnels — only the
     *  target client carries the connection's channels. */
    private fun clientFor(connection: SshConnection): SSHClient = when (connection) {
        is SshjConnection -> connection.sshClient
        is ChainedSshConnection -> connection.clients.last()
        else -> throw TunnelException("Tunnels cannot start on this connection type.")
    }

    suspend fun startLocal(connection: SshConnection, config: TunnelConfig): ActiveTunnel {
        val client = clientFor(connection)
        if (synchronized(registryLock) {
                localForwarders.containsKey(config.id) ||
                    remoteForwards.containsKey(config.id)
            }
        ) {
            throw TunnelException("This tunnel is already started.")
        }
        return withContext(Dispatchers.IO) {
            val serverSocket = try {
                ServerSocket(
                    config.bindPort,
                    50,
                    InetAddress.getByName(config.bindAddress)
                )
            } catch (e: Exception) {
                throw TunnelException(
                    "Could not listen on ${config.bindAddress}:${config.bindPort}. " +
                        "The port may already be in use."
                )
            }
            // Item F-03b: bindPort 0 means "any free port" — the
            // RESOLVED port is what the forwarder and the active
            // record must carry (the VNC-over-SSH flow connects
            // to it and cannot guess it).
            val boundPort = serverSocket.localPort
            val forwarder = try {
                client.newLocalPortForwarder(
                    Parameters(
                        config.bindAddress,
                        boundPort,
                        config.targetHost,
                        config.targetPort
                    ),
                    serverSocket
                )
            } catch (e: Exception) {
                try {
                    serverSocket.close()
                } catch (_: Exception) {
                }
                throw TunnelException("Could not start local forwarding.")
            }
            synchronized(registryLock) {
                localForwarders[config.id] = forwarder
                localJobs[config.id] = scope.launch {
                try {
                    forwarder.listen()
                } catch (_: Exception) {
                    // Security fix 16 (L2): the old code swallowed
                    // every listen-loop exit, so a tunnel killed
                    // by a connection death stayed listed as
                    // active. stopAll() removes the forwarder
                    // BEFORE cancelling the job, so a still-
                    // registered forwarder here means an
                    // unexpected exit — drop the tunnel from
                    // the active list so the UI stops showing
                    // a dead forward as live. A stop() exit
                    // finds nothing registered and is ignored.
                    synchronized(registryLock) {
                        if (localForwarders.containsKey(config.id)) {
                            localForwarders.remove(config.id)
                            localJobs.remove(config.id)
                            activeMap.remove(config.id)
                            refresh()
                        }
                    }
                }
                }
            }
            val activeTunnel = config.copy(bindPort = boundPort).toActive()
            synchronized(registryLock) { activeMap[config.id] = activeTunnel }
            refresh()
            activeTunnel
        }
    }

    suspend fun startRemote(connection: SshConnection, config: TunnelConfig): ActiveTunnel {
        val client = clientFor(connection)
        if (synchronized(registryLock) {
                localForwarders.containsKey(config.id) ||
                    remoteForwards.containsKey(config.id)
            }
        ) {
            throw TunnelException("This tunnel is already started.")
        }
        return withContext(Dispatchers.IO) {
            val forward = RemotePortForwarder.Forward(config.bindAddress, config.bindPort)
            try {
                client.remotePortForwarder.bind(
                    forward,
                    SocketForwardingConnectListener(
                        InetSocketAddress(config.targetHost, config.targetPort)
                    )
                )
            } catch (e: Exception) {
                throw TunnelException(
                    "Remote forwarding was rejected by the server. " +
                        "The server may not allow remote forwarding, or the port may be in use there."
                )
            }
            val activeTunnel = config.toActive()
            synchronized(registryLock) {
                remoteForwards[config.id] = forward
                activeMap[config.id] = activeTunnel
            }
            refresh()
            activeTunnel
        }
    }

    /** Item T-20: stop ONE forward; the rest keep running.
     *  The Advanced screen has a per-forward stop button backed
     *  by this. Removal from the registries happens BEFORE the
     *  forwarder closes, so the local listen loop's exit handler
     *  sees an unregistered forwarder and stays quiet (the
     *  security-fix-16 invariant). */
    fun stop(connection: SshConnection?, configId: Long) {
        val client = try {
            connection?.let { clientFor(it) }
        } catch (_: Exception) {
            null
        }
        // Removals happen atomically and BEFORE any close, so the
        // listen loop's exit handler sees an unregistered
        // forwarder and stays quiet (the security-fix-16
        // invariant). The blocking close/cancel calls run on the
        // snapshots, outside the lock.
        val forwarder: LocalPortForwarder?
        val job: Job?
        val forward: RemotePortForwarder.Forward?
        synchronized(registryLock) {
            forwarder = localForwarders.remove(configId)
            job = localJobs.remove(configId)
            forward = remoteForwards.remove(configId)
            activeMap.remove(configId)
        }
        if (forwarder != null) {
            try {
                forwarder.close()
            } catch (_: Exception) {
            }
        }
        job?.cancel()
        if (forward != null && client != null) {
            try {
                client.remotePortForwarder.cancel(forward)
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    /** Stop every forward. Call before disconnecting the client. */
    fun stopAll(connection: SshConnection?) {
        val ids = synchronized(registryLock) { activeMap.keys.toList() }
        for (id in ids) {
            stop(connection, id)
        }
        refresh()
    }

    /** Final cleanup: stops forwards and ends the manager's jobs.
     *  After close(), this manager must not be reused. */
    fun close(connection: SshConnection?) {
        stopAll(connection)
        scope.cancel()
    }

    private fun refresh() {
        _active.value = synchronized(registryLock) {
            activeMap.values.sortedBy { it.configId }
        }
    }

    private fun TunnelConfig.toActive(): ActiveTunnel = ActiveTunnel(
        configId = id,
        kind = kind,
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
        label = label
    )
}
