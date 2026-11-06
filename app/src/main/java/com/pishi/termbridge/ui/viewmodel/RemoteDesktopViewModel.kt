package com.pishi.termbridge.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.domain.RfbKeys
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.transport.RfbConnector
import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.transport.RfbServerMessage
import com.pishi.termbridge.transport.RfbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DesktopStatus { IDLE, CONNECTING, CONNECTED, FAILED }

data class DesktopUiState(
    val status: DesktopStatus = DesktopStatus.IDLE,
    val error: String? = null,
    val desktopName: String = "",
    /** Item F-03b: the last endpoint attempted — the connect
     *  form refills from these so reconnecting after a drop
     *  does not mean retyping the address. Never a password. */
    val lastHost: String = "",
    val lastPort: Int = 5900
)

/** One rendered frame; [version] changes on every update so a redraw is triggered. */
class DesktopFrame(val bitmap: Bitmap, val version: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * Drives a VNC (RFB) session: connect, keep the framebuffer fresh,
 * and forward pointer and key input. All socket I/O is on IO threads;
 * input is queued through a single consumer so events stay ordered
 * and never touch the network on the main thread. The VNC password is
 * used once for the handshake and is never stored.
 */
class RemoteDesktopViewModel(
    private val hostRepository: com.pishi.termbridge.data.HostRepository? = null,
    private val profileRepository: com.pishi.termbridge.settings.DesktopProfileRepository? = null
) : ViewModel() {
    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<DesktopFrame?>(null)
    val frame: StateFlow<DesktopFrame?> = _frame.asStateFlow()

    /** Item F-03b: saved desktop profiles (endpoint-only). */
    val profiles: StateFlow<List<com.pishi.termbridge.domain.DesktopProfile>> =
        profileRepository?.profiles ?: MutableStateFlow(emptyList())

    /** Item F-03b: saved SSH hosts, for the through-SSH picker. */
    val sshHosts: StateFlow<List<com.pishi.termbridge.domain.HostProfile>> =
        hostRepository?.getHosts()?.let { flow ->
            MutableStateFlow<List<com.pishi.termbridge.domain.HostProfile>>(
                emptyList()
            ).also { holder ->
                viewModelScope.launch {
                    flow.collect { holder.value = it }
                }
            }
        } ?: MutableStateFlow(emptyList())

    private var session: RfbSession? = null
    private var job: Job? = null
    // Audit #6 TB-006: bounded — input events produced faster
    // than the network drains them are dropped, never queued
    // without limit (a stale mouse move is worthless anyway).
    private var outbox = Channel<() -> Unit>(OUTBOX_CAPACITY)
    private var bitmap: Bitmap? = null
    private var version = 0L

    // Item F-03b: the optional SSH tunnel under a VNC session.
    private var sshConnection: com.pishi.termbridge.transport.SshConnection? = null
    private var tunnelManager: com.pishi.termbridge.transport.SshTunnelManager? = null

    private fun stateWith(
        status: DesktopStatus,
        error: String? = null,
        desktopName: String = ""
    ): DesktopUiState {
        val current = _state.value
        return DesktopUiState(
            status = status,
            error = error,
            desktopName = desktopName,
            lastHost = current.lastHost,
            lastPort = current.lastPort
        )
    }

    fun connect(host: String, port: Int, password: String, noPassword: Boolean) {
        disconnect()
        _state.value = DesktopUiState(
            status = DesktopStatus.CONNECTING, lastHost = host, lastPort = port
        )
        val secret = if (password.isEmpty()) null else password.toCharArray()
        job = viewModelScope.launch {
            try {
                val opened = RfbConnector.connect(
                    host, port, secret, noPassword,
                    // No idle read timeout: a quiet desktop sends nothing.
                    socketTimeoutMs = Int.MAX_VALUE
                )
                runSession(opened)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RfbException) {
                fail(e.message ?: "Connection failed.")
            } catch (e: Exception) {
                fail("Connection failed.")
            } finally {
                secret?.fill('\u0000')
            }
        }
    }

    /**
     * Item F-03b: connect to a VNC server through a saved SSH
     * host — the app's own local forward carries the RFB
     * traffic inside the SSH connection, so VNC's plaintext
     * never crosses the network. Host-key policy: the stored
     * trust record decides; an unknown or changed key is
     * DECLINED here (verify the host in a terminal session
     * first) — this flow never prompts and never accepts-all.
     */
    fun connectViaSsh(
        sshHostId: Long,
        sshPassphrase: String,
        vncHost: String,
        vncPort: Int,
        password: String,
        noPassword: Boolean
    ) {
        val repo = hostRepository ?: run {
            fail("SSH hosts are unavailable in this build.")
            return
        }
        disconnect()
        _state.value = DesktopUiState(
            status = DesktopStatus.CONNECTING, lastHost = vncHost, lastPort = vncPort
        )
        val secret = if (password.isEmpty()) null else password.toCharArray()
        job = viewModelScope.launch {
            var credential: ByteArray? = null
            var passphraseChars: CharArray? = null
            try {
                val profile = repo.getHost(sshHostId) ?: run {
                    fail("The selected SSH host no longer exists.")
                    return@launch
                }
                credential = repo.loadCredential(sshHostId) ?: run {
                    fail("The selected SSH host has no stored credential.")
                    return@launch
                }
                passphraseChars = if (
                    profile.authType == com.pishi.termbridge.domain.AuthType.PRIVATE_KEY &&
                    sshPassphrase.isNotEmpty()
                ) {
                    sshPassphrase.toCharArray()
                } else {
                    null
                }
                val transport = com.pishi.termbridge.transport.SshTransport(repo)
                val conn = transport.connect(
                    com.pishi.termbridge.transport.SshConnectionRequest(
                        profile = profile,
                        credential = credential,
                        passphrase = passphraseChars
                    ),
                    StoredTrustOnlyHandler
                )
                val manager = com.pishi.termbridge.transport.SshTunnelManager()
                val active = manager.startLocal(
                    conn,
                    com.pishi.termbridge.domain.TunnelConfig(
                        id = SSH_TUNNEL_ID,
                        hostId = sshHostId,
                        kind = com.pishi.termbridge.domain.TunnelKind.LOCAL,
                        bindAddress = "127.0.0.1",
                        bindPort = 0,
                        targetHost = vncHost,
                        targetPort = vncPort
                    )
                )
                sshConnection = conn
                tunnelManager = manager
                val opened = RfbConnector.connect(
                    "127.0.0.1", active.bindPort, secret, noPassword,
                    socketTimeoutMs = Int.MAX_VALUE
                )
                runSession(opened)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.pishi.termbridge.transport.SshTransportException) {
                closeTunnel()
                fail("SSH connection failed: ${e.userFacingMessage()}")
            } catch (e: com.pishi.termbridge.transport.TunnelException) {
                closeTunnel()
                fail(e.message ?: "The SSH tunnel could not start.")
            } catch (e: RfbException) {
                closeTunnel()
                fail(e.message ?: "Connection failed.")
            } catch (e: Exception) {
                closeTunnel()
                fail("Connection failed.")
            } finally {
                credential?.wipe()
                passphraseChars?.wipe()
                secret?.fill('\u0000')
            }
        }
    }

    /** The shared session body: publish state, run the sender
     *  and reader until the reader ends. */
    private suspend fun runSession(opened: RfbSession) {
        session = opened
        _state.value = stateWith(
            status = DesktopStatus.CONNECTED,
            desktopName = opened.desktopName
        )
        outbox = Channel(OUTBOX_CAPACITY)
        val sender = viewModelScope.launch(Dispatchers.IO) {
            for (action in outbox) {
                try {
                    action()
                } catch (_: Exception) {
                    // The reader loop reports the broken connection.
                }
            }
        }
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { readLoop(opened) }
        } finally {
            sender.cancel()
        }
    }

    private fun closeTunnel() {
        val manager = tunnelManager
        val conn = sshConnection
        tunnelManager = null
        sshConnection = null
        if (manager != null || conn != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    manager?.close(conn)
                } catch (_: Exception) {
                }
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Declines every unknown/changed host key: the stored
     *  trust record is the only authority in this flow. */
    private object StoredTrustOnlyHandler :
        com.pishi.termbridge.transport.HostKeyDecisionHandler {
        override suspend fun decideUnknown(
            pending: com.pishi.termbridge.transport.PendingTrust
        ): Boolean = false

        override suspend fun decideChanged(
            pending: com.pishi.termbridge.transport.PendingTrust
        ): Boolean = false
    }

    private companion object {
        /** Not a saved tunnel: the desktop's private forward. */
        const val SSH_TUNNEL_ID = -77L

        /** Audit #6 TB-006: input outbox element bound. */
        const val OUTBOX_CAPACITY = 512
    }


    private fun readLoop(opened: RfbSession) {
        try {
            opened.requestUpdate(false)
            while (job?.isActive != false) {
                when (opened.readServerMessage()) {
                    RfbServerMessage.FramebufferApplied -> {
                        publish(opened)
                        opened.requestUpdate(true)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (_state.value.status == DesktopStatus.CONNECTED) {
                fail(if (e is RfbException) e.message ?: "Connection lost." else "Connection lost.")
            }
        } finally {
            opened.close()
        }
    }

    private fun publish(opened: RfbSession) {
        val fb = opened.framebuffer
        var target = bitmap
        if (target == null || target.width != fb.width || target.height != fb.height) {
            target = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
            bitmap = target
        }
        target.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
        version += 1
        _frame.value = DesktopFrame(target, version)
    }

    private fun fail(message: String) {
        _state.value = stateWith(status = DesktopStatus.FAILED, error = message)
    }

    fun disconnect() {
        job?.cancel()
        job = null
        val old = session
        session = null
        outbox.close()
        if (old != null) {
            viewModelScope.launch(Dispatchers.IO) { old.close() }
        }
        closeTunnel()
        _frame.value = null
        bitmap = null
        _state.value = stateWith(status = DesktopStatus.IDLE)
    }

    /** Item F-03b: send the local clipboard text to the remote
     *  side (RFB ClientCutText; the protocol carries Latin-1,
     *  the session layer handles the encoding). */
    fun sendClipboard(text: String) {
        if (text.isNotEmpty()) post { it.sendCutText(text) }
    }

    /** Item F-03b: tap a key with sticky modifiers held. */
    fun tapModified(keysym: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        if (ctrl) key(RfbKeys.CONTROL_L, true)
        if (alt) key(RfbKeys.ALT_L, true)
        if (shift) key(RfbKeys.SHIFT_L, true)
        tap(keysym)
        if (shift) key(RfbKeys.SHIFT_L, false)
        if (alt) key(RfbKeys.ALT_L, false)
        if (ctrl) key(RfbKeys.CONTROL_L, false)
    }

    /** Item F-03b: profile mutations (no-ops without a store). */
    fun saveProfile(name: String, host: String, port: Int, sshHostId: Long?) {
        profileRepository?.save(
            com.pishi.termbridge.domain.DesktopProfile(
                id = 0, name = name, host = host, port = port, sshHostId = sshHostId
            )
        )
    }

    fun deleteProfile(id: Long) {
        profileRepository?.delete(id)
    }

    private fun post(action: (RfbSession) -> Unit) {
        val current = session ?: return
        outbox.trySend { action(current) }
    }

    fun pointer(buttonMask: Int, x: Int, y: Int) = post { it.sendPointer(buttonMask, x, y) }

    fun key(keysym: Int, down: Boolean) {
        if (keysym != 0) post { it.sendKey(down, keysym) }
    }

    /** Press and release one key. */
    fun tap(keysym: Int) {
        key(keysym, true)
        key(keysym, false)
    }

    fun typeText(text: String) {
        text.codePoints().forEach { cp ->
            val sym = RfbKeys.keysymForCodePoint(cp)
            if (sym != 0) tap(sym)
        }
    }

    fun ctrlAltDelete() {
        key(RfbKeys.CONTROL_L, true)
        key(RfbKeys.ALT_L, true)
        tap(RfbKeys.DELETE)
        key(RfbKeys.ALT_L, false)
        key(RfbKeys.CONTROL_L, false)
    }

    override fun onCleared() {
        disconnect()
    }
}

/** Item F-03b: factory carrying the optional repositories. */
class RemoteDesktopViewModelFactory(
    private val hostRepository: com.pishi.termbridge.data.HostRepository?,
    private val profileRepository: com.pishi.termbridge.settings.DesktopProfileRepository?
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return RemoteDesktopViewModel(hostRepository, profileRepository) as T
    }
}
