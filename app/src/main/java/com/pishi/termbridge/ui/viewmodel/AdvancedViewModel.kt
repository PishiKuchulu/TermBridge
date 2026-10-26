package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.AdvancedRepositoryException
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.JumpChain
import com.pishi.termbridge.domain.JumpChainValidator
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxySpecParser
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import com.pishi.termbridge.domain.TunnelValidator
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.transport.ActiveTunnel
import com.pishi.termbridge.transport.HostKeyDecisionHandler
import com.pishi.termbridge.transport.JumpHopRequest
import com.pishi.termbridge.transport.PendingTrust
import com.pishi.termbridge.transport.SshConnection
import com.pishi.termbridge.transport.SshConnectionRequest
import com.pishi.termbridge.transport.SshTransport
import com.pishi.termbridge.transport.SshTransportException
import com.pishi.termbridge.transport.SshTunnelManager
import com.pishi.termbridge.transport.TunnelException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TunnelSessionStatus {
    IDLE,
    CONNECTING,
    RUNNING,
    FAILED
}

/**
 * Prompt 10 advanced-connections state.
 *
 * Configuration (tunnels, jump chain, proxy) is per-host, loaded
 * when a host is selected, and saved through AdvancedRepository —
 * every control on the Advanced screen writes something real.
 *
 * The tunnel session is a DEDICATED connection, separate from the
 * terminal session in SessionViewModel: starting tunnels here
 * does not touch a live terminal, and disconnecting here does not
 * kill it. Two SSH connections to the same host is ordinary SSH.
 * The session owns its connection and its tunnel manager, and
 * stop order is fixed: forwards first, then the client — so no
 * forward outlives its connection, and no background tunnel
 * persists after the user stops it (the pack's explicit rule).
 *
 * Jump-hop credentials follow the prompt-05 rules per hop: loaded
 * from HostRepository (Keystore-decrypted) at connect time only,
 * never stored, wiped in a finally block. A hop's passphrase is
 * supplied per hop from the screen (key hops only); hops are never
 * given each other's passphrases, and no hop credential is ever
 * logged or shown.
 *
 * Trust flow mirrors SessionViewModel: the decision handler
 * suspends on a CompletableDeferred the dialog completes, per hop
 * in chain order. Rejecting any hop fails the whole chain and
 * disconnects every hop opened so far (transport guarantee).
 */
class AdvancedViewModel(
    private val repository: HostRepository,
    private val advancedRepository: AdvancedRepository
) : ViewModel() {
    private val transport = SshTransport(repository)

    val hosts: StateFlow<List<HostProfile>> = repository.getHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedHostId = MutableStateFlow<Long?>(null)
    val selectedHostId: StateFlow<Long?> = _selectedHostId.asStateFlow()

    private val _tunnels = MutableStateFlow<List<TunnelConfig>>(emptyList())
    val tunnels: StateFlow<List<TunnelConfig>> = _tunnels.asStateFlow()

    private val _jumpChain = MutableStateFlow(JumpChain(0L, emptyList()))
    val jumpChain: StateFlow<JumpChain> = _jumpChain.asStateFlow()

    private val _hopProfiles = MutableStateFlow<List<HostProfile>>(emptyList())
    val hopProfiles: StateFlow<List<HostProfile>> = _hopProfiles.asStateFlow()

    private val _proxy = MutableStateFlow(ProxyConfig())
    val proxy: StateFlow<ProxyConfig> = _proxy.asStateFlow()

    private val _tunnelErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val tunnelErrors: StateFlow<Map<String, String>> = _tunnelErrors.asStateFlow()

    private val _tunnelSaveError = MutableStateFlow<String?>(null)
    val tunnelSaveError: StateFlow<String?> = _tunnelSaveError.asStateFlow()

    private val _chainError = MutableStateFlow<String?>(null)
    val chainError: StateFlow<String?> = _chainError.asStateFlow()

    private val _proxyError = MutableStateFlow<String?>(null)
    val proxyError: StateFlow<String?> = _proxyError.asStateFlow()

    private val _sessionStatus = MutableStateFlow(TunnelSessionStatus.IDLE)
    val sessionStatus: StateFlow<TunnelSessionStatus> = _sessionStatus.asStateFlow()

    private val _activeTunnels = MutableStateFlow<List<ActiveTunnel>>(emptyList())
    val activeTunnels: StateFlow<List<ActiveTunnel>> = _activeTunnels.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    private val _pendingTrust = MutableStateFlow<PendingTrust?>(null)
    val pendingTrust: StateFlow<PendingTrust?> = _pendingTrust.asStateFlow()

    private var configJob: Job? = null
    private var sessionJob: Job? = null
    private var activeJob: Job? = null
    private var decisionDeferred: CompletableDeferred<Boolean>? = null
    private var tunnelConnection: SshConnection? = null
    private var tunnelManager: SshTunnelManager? = null

    private val decisionHandler = object : HostKeyDecisionHandler {
        override suspend fun decideUnknown(pending: PendingTrust): Boolean =
            awaitDecision(pending)

        override suspend fun decideChanged(pending: PendingTrust): Boolean =
            awaitDecision(pending)
    }

    private suspend fun awaitDecision(pending: PendingTrust): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decisionDeferred = deferred
        _pendingTrust.value = pending
        try {
            return deferred.await()
        } finally {
            decisionDeferred = null
            if (_pendingTrust.value == pending) {
                _pendingTrust.value = null
            }
        }
    }

    fun selectHost(id: Long) {
        if (_sessionStatus.value == TunnelSessionStatus.CONNECTING) return
        _selectedHostId.value = id
        _tunnelErrors.value = emptyMap()
        _tunnelSaveError.value = null
        _chainError.value = null
        _proxyError.value = null
        configJob?.cancel()
        configJob = viewModelScope.launch {
            launch { advancedRepository.getTunnels(id).collect { _tunnels.value = it } }
            launch {
                advancedRepository.getJumpChain(id).collect { chain ->
                    _jumpChain.value = chain
                    _hopProfiles.value = chain.jumpHostIds.mapNotNull { repository.getHost(it) }
                }
            }
            launch { advancedRepository.getProxy(id).collect { _proxy.value = it } }
        }
    }

    fun answerTrust(trust: Boolean) {
        decisionDeferred?.complete(trust)
    }

    fun clearSessionError() {
        _sessionError.value = null
    }

    // ---- Configuration saves ----

    fun saveTunnel(config: TunnelConfig) {
        val issues = TunnelValidator.validate(config)
        if (issues.isNotEmpty()) {
            _tunnelErrors.value = issues.associate { it.field to it.message }
            return
        }
        _tunnelErrors.value = emptyMap()
        _tunnelSaveError.value = null
        viewModelScope.launch {
            try {
                advancedRepository.saveTunnel(config)
            } catch (e: AdvancedRepositoryException) {
                _tunnelSaveError.value = e.message
            } catch (_: Exception) {
                _tunnelSaveError.value = "Could not save the tunnel. Please try again."
            }
        }
    }

    fun deleteTunnel(id: Long) {
        val hostId = _selectedHostId.value ?: return
        viewModelScope.launch {
            try {
                advancedRepository.deleteTunnel(id, hostId)
            } catch (_: Exception) {
                _tunnelSaveError.value = "Could not delete the tunnel. Please try again."
            }
        }
    }

    fun setTunnelEnabled(id: Long, enabled: Boolean) {
        val hostId = _selectedHostId.value ?: return
        viewModelScope.launch {
            try {
                advancedRepository.setTunnelEnabled(id, hostId, enabled)
            } catch (_: Exception) {
                _tunnelSaveError.value = "Could not change the tunnel. Please try again."
            }
        }
    }

    fun saveJumpChain(jumpHostIds: List<Long>) {
        val hostId = _selectedHostId.value ?: return
        val chain = JumpChain(hostId, jumpHostIds)
        val issues = JumpChainValidator.validate(chain)
        if (issues.isNotEmpty()) {
            _chainError.value = issues.joinToString("; ") { it.message }
            return
        }
        _chainError.value = null
        viewModelScope.launch {
            try {
                advancedRepository.saveJumpChain(chain)
            } catch (e: AdvancedRepositoryException) {
                _chainError.value = e.message
            } catch (_: Exception) {
                _chainError.value = "Could not save the jump chain. Please try again."
            }
        }
    }

    fun clearJumpChain() {
        val hostId = _selectedHostId.value ?: return
        viewModelScope.launch {
            try {
                advancedRepository.clearJumpChain(hostId)
            } catch (_: Exception) {
                _chainError.value = "Could not clear the jump chain. Please try again."
            }
        }
    }

    fun saveProxy(config: ProxyConfig) {
        val issues = ProxySpecParser.validate(config)
        if (issues.isNotEmpty()) {
            _proxyError.value = issues.joinToString("; ") { it.message }
            return
        }
        _proxyError.value = null
        viewModelScope.launch {
            try {
                advancedRepository.saveProxy(config)
            } catch (e: AdvancedRepositoryException) {
                _proxyError.value = e.message
            } catch (_: Exception) {
                _proxyError.value = "Could not save the proxy. Please try again."
            }
        }
    }

    // ---- Tunnel session ----

    /**
     * Start the selected host's enabled tunnels on a dedicated
     * connection (via its jump chain and proxy, if configured).
     * [targetPassphrase] applies to the target host only;
     * [hopPassphrases] maps jump-host id to passphrase (key hops
     * only). All credential buffers are wiped in finally.
     */
    fun startTunnels(targetPassphrase: String, hopPassphrases: Map<Long, String>) {
        val hostId = _selectedHostId.value ?: return
        if (_sessionStatus.value == TunnelSessionStatus.CONNECTING ||
            _sessionStatus.value == TunnelSessionStatus.RUNNING
        ) {
            return
        }
        sessionJob?.cancel()
        _sessionStatus.value = TunnelSessionStatus.CONNECTING
        _sessionError.value = null
        sessionJob = viewModelScope.launch {
            var targetCredential: ByteArray? = null
            var targetPassphraseChars: CharArray? = null
            val hopCredentials = mutableListOf<ByteArray>()
            val hopPassphraseChars = mutableListOf<CharArray>()
            try {
                val targetProfile = repository.getHost(hostId)
                if (targetProfile == null) {
                    failSession("Host not found.")
                    return@launch
                }
                val tunnels = advancedRepository.getTunnelsOnce(hostId)
                    .filter { it.enabled }
                if (tunnels.isEmpty()) {
                    failSession("Enable at least one tunnel before starting.")
                    return@launch
                }
                val chain = advancedRepository.getJumpChainOnce(hostId)
                val proxy = advancedRepository.getProxyOnce(hostId)
                targetCredential = repository.loadCredential(hostId)
                if (targetCredential == null) {
                    failSession("No credential stored for this host. Edit the host and add one.")
                    return@launch
                }
                targetPassphraseChars =
                    if (targetProfile.authType == AuthType.PRIVATE_KEY && targetPassphrase.isNotEmpty()) {
                        targetPassphrase.toCharArray()
                    } else {
                        null
                    }
                val targetRequest = SshConnectionRequest(
                    profile = targetProfile,
                    credential = targetCredential,
                    passphrase = targetPassphraseChars,
                    proxy = proxy
                )
                val connection: SshConnection
                if (chain.jumpHostIds.isEmpty()) {
                    connection = transport.connect(targetRequest, decisionHandler)
                } else {
                    val hopRequests = mutableListOf<JumpHopRequest>()
                    for (jumpHostId in chain.jumpHostIds) {
                        val hopProfile = repository.getHost(jumpHostId)
                        val hopCredential = repository.loadCredential(jumpHostId)
                        if (hopProfile == null || hopCredential == null) {
                            failSession(
                                "A jump host has no stored credential. " +
                                    "Edit that host and add one."
                            )
                            return@launch
                        }
                        hopCredentials += hopCredential
                        val passphraseText = hopPassphrases[jumpHostId].orEmpty()
                        val chars =
                            if (hopProfile.authType == AuthType.PRIVATE_KEY &&
                                passphraseText.isNotEmpty()
                            ) {
                                passphraseText.toCharArray().also { hopPassphraseChars += it }
                            } else {
                                null
                            }
                        hopRequests += JumpHopRequest(hopProfile, hopCredential, chars)
                    }
                    connection = transport.connectChain(hopRequests, targetRequest, decisionHandler)
                }
                tunnelConnection = connection
                val manager = SshTunnelManager()
                tunnelManager = manager
                // Partial-failure rule: if any tunnel fails to
                // start, stop the ones already started and close
                // the connection — never leave a half-started set
                // running while reporting success.
                try {
                    for (tunnel in tunnels) {
                        when (tunnel.kind) {
                            TunnelKind.LOCAL -> manager.startLocal(connection, tunnel)
                            TunnelKind.REMOTE -> manager.startRemote(connection, tunnel)
                        }
                    }
                } catch (e: TunnelException) {
                    manager.close(connection)
                    tunnelManager = null
                    connection.disconnect()
                    tunnelConnection = null
                    failSession(e.message ?: "A tunnel could not start.")
                    return@launch
                }
                activeJob?.cancel()
                activeJob = viewModelScope.launch {
                    manager.active.collect { _activeTunnels.value = it }
                }
                _activeTunnels.value = manager.getActive()
                _sessionStatus.value = TunnelSessionStatus.RUNNING
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User cancelled or stopped: clean up silently and
                // let the stop path own the status — do not
                // overwrite IDLE with FAILED after a cancel.
                tunnelManager?.close(tunnelConnection)
                tunnelManager = null
                tunnelConnection?.disconnect()
                tunnelConnection = null
                _activeTunnels.value = emptyList()
                throw e
            } catch (e: SshTransportException) {
                failSession(e.error.userMessage)
            } catch (_: Exception) {
                failSession("Connection failed. Please try again.")
            } finally {
                targetCredential?.wipe()
                targetPassphraseChars?.wipe()
                hopCredentials.forEach { it.wipe() }
                hopPassphraseChars.forEach { it.wipe() }
            }
        }
    }

    fun stopTunnels() {
        sessionJob?.cancel()
        activeJob?.cancel()
        // Forwards first, then the client — the fixed stop order.
        // Review #1 (C-02): closing forwards and disconnecting
        // sends socket traffic, so it runs on IO.
        val managerToClose = tunnelManager
        val connectionToClose = tunnelConnection
        tunnelManager = null
        tunnelConnection = null
        decisionDeferred?.complete(false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                managerToClose?.close(connectionToClose)
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
        _activeTunnels.value = emptyList()
        _sessionStatus.value = TunnelSessionStatus.IDLE
        _sessionError.value = null
    }

    fun cancelConnect() {
        decisionDeferred?.complete(false)
        sessionJob?.cancel()
        stopTunnels()
    }

    private fun failSession(message: String) {
        tunnelManager?.close(tunnelConnection)
        tunnelManager = null
        tunnelConnection?.disconnect()
        tunnelConnection = null
        _activeTunnels.value = emptyList()
        _sessionStatus.value = TunnelSessionStatus.FAILED
        _sessionError.value = message
    }

    override fun onCleared() {
        sessionJob?.cancel()
        activeJob?.cancel()
        configJob?.cancel()
        // viewModelScope is already cancelled here, so teardown
        // network I/O uses a detached IO scope (best-effort).
        // Review #1 (C-02).
        val managerToClose = tunnelManager
        val connectionToClose = tunnelConnection
        tunnelManager = null
        tunnelConnection = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                managerToClose?.close(connectionToClose)
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
        super.onCleared()
    }
}

class AdvancedViewModelFactory(
    private val repository: HostRepository,
    private val advancedRepository: AdvancedRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AdvancedViewModel::class.java)) {
            return AdvancedViewModel(repository, advancedRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
