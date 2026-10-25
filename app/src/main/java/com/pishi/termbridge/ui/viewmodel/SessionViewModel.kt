package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.EnvVarValue
import com.pishi.termbridge.domain.CommandRunner
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.PreparedCommand
import com.pishi.termbridge.domain.SequenceProgress
import com.pishi.termbridge.domain.TerminalSender
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.terminal.TerminalSession
import com.pishi.termbridge.terminal.TerminalSnapshot
import com.pishi.termbridge.transport.HostKeyDecisionHandler
import com.pishi.termbridge.transport.PendingTrust
import com.pishi.termbridge.transport.SshConnection
import com.pishi.termbridge.transport.SshConnectionRequest
import com.pishi.termbridge.transport.SshTransport
import com.pishi.termbridge.transport.SshTransportException
import com.pishi.termbridge.transport.SshjConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
// Prompt 09 fix for a latent issue: the terminal-snapshot and
// buttons flows below call collect(), which is an extension that
// needs this import. It was missing since prompt 06 and never
// compiled anywhere (no CI run yet), so it is fixed here rather
// than carried forward as a known risk.
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SessionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class SessionUiState(
    val selectedHostId: Long? = null,
    val status: SessionStatus = SessionStatus.IDLE,
    val connectedHost: HostProfile? = null,
    val errorMessage: String? = null,
    val pendingTrust: PendingTrust? = null
)

/**
 * Prompt 05 session state, extended in prompt 06 with a real
 * terminal. This ViewModel owns the transport connection AND the
 * shell channel (TerminalSession).
 *
 * Trust flow: the decision handler suspends on a CompletableDeferred
 * that the dialog completes. Cancelling while the dialog is open
 * completes the decision with false, so the connecting thread
 * unblocks instead of hanging.
 *
 * Session-survival decision (prompt 06, explicit): this ViewModel
 * is activity-scoped (see TermBridgeNavGraph, which passes the
 * activity as ViewModelStoreOwner). Navigating to another tab
 * therefore does NOT disconnect — the session survives tab
 * switches, and returning to the session screen reattaches to the
 * live terminal. Disconnect is explicit (Disconnect button) or
 * happens when the activity is destroyed. Process death still
 * loses the session (no background service in prompt 06); on
 * return the user reconnects, which is the honest behaviour.
 */
/** A sequence currently being submitted, for the progress UI. */
data class ActiveSequence(
    val label: String,
    val progress: SequenceProgress,
    val stopped: Boolean = false
)

/**
 * Prompt 09 environment status for the live session. NAMES and
 * the working-directory PATH only — an env value never appears
 * here. Null when no env vars are configured and no working
 * directory is set, in which case the session screen shows
 * nothing (no decorative section for an empty feature).
 */
data class EnvStatus(
    val configuredNames: List<String> = emptyList(),
    val appliedNames: List<String> = emptyList(),
    val rejectedNames: List<String> = emptyList(),
    val workingDirectory: String? = null
)

class SessionViewModel(
    private val repository: HostRepository,
    private val commandRepository: CommandRepository,
    private val environmentRepository: HostEnvironmentRepository
) : ViewModel() {
    private val transport = SshTransport(repository)

    val hosts: StateFlow<List<HostProfile>> = repository.getHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _terminalSnapshot = MutableStateFlow<TerminalSnapshot?>(null)
    val terminalSnapshot: StateFlow<TerminalSnapshot?> = _terminalSnapshot.asStateFlow()

    private var connection: SshConnection? = null
    private var terminalSession: TerminalSession? = null
    private var connectJob: Job? = null
    private var decisionDeferred: CompletableDeferred<Boolean>? = null

    // Prompt 08: command buttons for the connected host, and the
    // action/sequence state. List items are shallow (label/mode/
    // order only); selectButtonForAction loads the full button
    // (steps + variables) before any dialog is shown.
    private val _commandButtons = MutableStateFlow<List<CommandButton>>(emptyList())
    val commandButtons: StateFlow<List<CommandButton>> = _commandButtons.asStateFlow()

    private val _actionButton = MutableStateFlow<CommandButton?>(null)
    val actionButton: StateFlow<CommandButton?> = _actionButton.asStateFlow()

    private val _pendingRisky = MutableStateFlow<PreparedCommand?>(null)
    val pendingRisky: StateFlow<PreparedCommand?> = _pendingRisky.asStateFlow()

    // Remediation phase 1: the last execution-gate block, if
    // any. The screen maps this to a user-visible string.
    // Set only when actOnPrepared refuses a submission (a
    // dialog bypass or a stale prepared command — the dialog
    // itself disables its button and explains in place).
    // Cleared on the next button tap and on disconnect, so a
    // stale refusal never lingers after a later good action.
    private val _blockedMessage =
        MutableStateFlow<CommandTemplate.SubmissionBlock?>(null)
    val blockedMessage: StateFlow<CommandTemplate.SubmissionBlock?> =
        _blockedMessage.asStateFlow()

    private val _activeSequence = MutableStateFlow<ActiveSequence?>(null)
    val activeSequence: StateFlow<ActiveSequence?> = _activeSequence.asStateFlow()

    // Prompt 09: environment outcome for the current session.
    private val _envStatus = MutableStateFlow<EnvStatus?>(null)
    val envStatus: StateFlow<EnvStatus?> = _envStatus.asStateFlow()

    // Security fix 16 (H1): the connected host's shell, for
    // shell-aware button quoting and risky classification in the
    // parameter dialog. POSIX until a connection's settings say
    // otherwise; cleared on disconnect so a stale shell can never
    // gate (or un-gate) another host's buttons.
    private val _connectedShell = MutableStateFlow(com.pishi.termbridge.domain.RemoteShell.POSIX)
    val connectedShell: StateFlow<com.pishi.termbridge.domain.RemoteShell> =
        _connectedShell.asStateFlow()

    // Prompt 18 (audit-17 R1): whether the connected host's shell
    // was ever confirmed (environment screen saved). False when
    // no settings row exists at all, or when the row predates
    // confirmation tracking — in both cases the stored POSIX
    // value is an untouched default, and the parameter dialog
    // warns instead of silently applying POSIX quoting.
    private val _connectedShellConfirmed = MutableStateFlow(false)
    val connectedShellConfirmed: StateFlow<Boolean> =
        _connectedShellConfirmed.asStateFlow()

    private var buttonsJob: Job? = null
    private var sequenceJob: Job? = null

    private val terminalSender = object : TerminalSender {
        override fun sendText(text: String) {
            terminalSession?.sendText(text)
        }

        override fun sendEnter() {
            terminalSession?.sendEnter()
        }
    }
    private val commandRunner = CommandRunner(terminalSender)

    private val decisionHandler = object : HostKeyDecisionHandler {
        override suspend fun decideUnknown(pending: PendingTrust): Boolean =
            awaitDecision(pending)

        override suspend fun decideChanged(pending: PendingTrust): Boolean =
            awaitDecision(pending)
    }

    private suspend fun awaitDecision(pending: PendingTrust): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decisionDeferred = deferred
        _uiState.value = _uiState.value.copy(pendingTrust = pending)
        try {
            return deferred.await()
        } finally {
            decisionDeferred = null
            if (_uiState.value.pendingTrust == pending) {
                _uiState.value = _uiState.value.copy(pendingTrust = null)
            }
        }
    }

    fun selectHost(id: Long) {
        if (_uiState.value.status != SessionStatus.CONNECTING) {
            _uiState.value = _uiState.value.copy(selectedHostId = id, errorMessage = null)
        }
    }

    fun answerTrust(trust: Boolean) {
        decisionDeferred?.complete(trust)
    }

    fun connect(passphrase: String) {
        // Reconnect after a shell close reuses the connected host
        // when no host is newly selected (the picker is hidden
        // while a terminal snapshot exists).
        val hostId = _uiState.value.selectedHostId
            ?: _uiState.value.connectedHost?.id
            ?: return
        connectJob?.cancel()
        _uiState.value = _uiState.value.copy(
            status = SessionStatus.CONNECTING,
            errorMessage = null,
            pendingTrust = null
        )
        connectJob = viewModelScope.launch {
            var credentialBytes: ByteArray? = null
            var passphraseChars: CharArray? = null
            var envValues: List<EnvVarValue> = emptyList()
            try {
                val profile = repository.getHost(hostId)
                if (profile == null) {
                    _uiState.value = _uiState.value.copy(
                        status = SessionStatus.FAILED,
                        errorMessage = "Host not found."
                    )
                    return@launch
                }
                credentialBytes = repository.loadCredential(hostId)
                if (credentialBytes == null) {
                    _uiState.value = _uiState.value.copy(
                        status = SessionStatus.FAILED,
                        errorMessage = "No credential stored for this host. Edit the host and add one."
                    )
                    return@launch
                }
                passphraseChars = if (profile.authType == AuthType.PRIVATE_KEY && passphrase.isNotEmpty()) {
                    passphrase.toCharArray()
                } else {
                    null
                }
                val conn = transport.connect(
                    SshConnectionRequest(
                        profile = profile,
                        credential = credentialBytes,
                        passphrase = passphraseChars
                    ),
                    decisionHandler
                )
                connection = conn
                // Prompt 06: open a real shell channel on the
                // authenticated transport. If the connection is not
                // an sshj connection (it always is in production),
                // the terminal cannot start — fail, don't fake it.
                val sshjConn = conn as? SshjConnection
                if (sshjConn == null) {
                    conn.disconnect()
                    connection = null
                    _uiState.value = _uiState.value.copy(
                        status = SessionStatus.FAILED,
                        errorMessage = "Terminal could not start on this connection."
                    )
                    return@launch
                }
                val terminal = TerminalSession(sshjConn, 80, 24)
                terminal.onClosed = { error ->
                    if (_uiState.value.status == SessionStatus.CONNECTED) {
                        _uiState.value = _uiState.value.copy(
                            status = SessionStatus.FAILED,
                            errorMessage = if (error == null) {
                                "Shell closed by the server."
                            } else {
                                "Connection lost."
                            }
                        )
                    }
                }
                terminalSession = terminal
                launch {
                    terminal.snapshot.collect { _terminalSnapshot.value = it }
                }
                // Prompt 09: env vars are requested with the shell
                // (setEnvVar before startShell, inside
                // TerminalSession.start), and the initial working
                // directory is one visible `cd` immediately after
                // the shell starts, before CONNECTED is set — so
                // it is the first input the shell sees. Neither
                // mechanism touches command buttons: buttons are
                // user-authored exact text (prompt 08 guarantee).
                val decrypted = environmentRepository.loadEnvVarsForConnection(hostId)
                envValues = decrypted.values
                val settings = environmentRepository.getSettingsOnce(hostId)
                // Security fix 16 (H1): buttons are quoted and
                // classified for THIS host's shell, never for an
                // assumed POSIX shell.
                _connectedShell.value = settings?.remoteShell
                    ?: com.pishi.termbridge.domain.RemoteShell.POSIX
                _connectedShellConfirmed.value = settings?.shellConfirmed ?: false
                val envPairs = envValues.map { it.name to String(it.value) }
                terminal.start(envPairs) { result ->
                    _envStatus.value = EnvStatus(
                        configuredNames = envPairs.map { it.first } +
                            decrypted.undecryptableNames,
                        appliedNames = result.appliedNames,
                        rejectedNames = result.rejectedNames +
                            decrypted.undecryptableNames,
                        workingDirectory = null
                    )
                }
                val workingDir = settings?.remoteWorkingDirectory?.takeIf { it.isNotBlank() }
                if (workingDir != null && settings != null) {
                    terminal.sendText(CdCommand.render(workingDir, settings.remoteShell))
                    terminal.sendEnter()
                    _envStatus.value = (_envStatus.value ?: EnvStatus()).copy(
                        workingDirectory = workingDir
                    )
                }
                _uiState.value = _uiState.value.copy(
                    status = SessionStatus.CONNECTED,
                    connectedHost = profile,
                    errorMessage = null
                )
                // Prompt 08: load this host's buttons for the
                // session's buttons area. Buttons are per-host, so
                // a different host's buttons are never offered here.
                buttonsJob?.cancel()
                // viewModelScope, not a child of connectJob: the
                // buttons flow must outlive the connect attempt
                // itself and be cancelled only on disconnect.
                buttonsJob = viewModelScope.launch {
                    commandRepository.getButtons(profile.id).collect {
                        _commandButtons.value = it
                    }
                }
            } catch (e: SshTransportException) {
                _uiState.value = _uiState.value.copy(
                    status = SessionStatus.FAILED,
                    errorMessage = e.error.userMessage
                )
            } catch (e: Exception) {
                // Generic only — repository/transport messages are
                // already secret-free, but this is the second net.
                _uiState.value = _uiState.value.copy(
                    status = SessionStatus.FAILED,
                    errorMessage = "Connection failed. Please try again."
                )
            } finally {
                credentialBytes?.wipe()
                passphraseChars?.wipe()
                envValues.forEach { it.value.wipe() }
            }
        }
    }

    fun cancel() {
        decisionDeferred?.complete(false)
        connectJob?.cancel()
        disconnect()
        _uiState.value = _uiState.value.copy(status = SessionStatus.IDLE, errorMessage = null)
    }

    fun disconnect() {
        connectJob?.cancel()
        stopSequence()
        buttonsJob?.cancel()
        _commandButtons.value = emptyList()
        _actionButton.value = null
        _pendingRisky.value = null
        _blockedMessage.value = null
        _envStatus.value = null
        _connectedShell.value = com.pishi.termbridge.domain.RemoteShell.POSIX
        _connectedShellConfirmed.value = false
        // Terminal channel first, then transport — so the reader
        // loop does not report a "connection lost" for an intentional
        // disconnect.
        terminalSession?.close()
        terminalSession = null
        _terminalSnapshot.value = null
        connection?.disconnect()
        connection = null
        decisionDeferred?.complete(false)
        _uiState.value = _uiState.value.copy(
            status = SessionStatus.IDLE,
            connectedHost = null,
            pendingTrust = null
        )
    }

    // ---- Prompt 08 command-button actions ----
    //
    // Flow: tap a button -> selectButtonForAction loads the full
    // button -> parameter dialog (live preview) -> Run/Insert tap
    // -> actOnPrepared: risky + not yet confirmed -> risky dialog;
    // otherwise execute. Nothing here runs on screen open, on
    // selection alone, or on dialog open — only on the explicit
    // Run/Insert/confirm tap.
    fun selectButtonForAction(buttonId: Long) {
        _blockedMessage.value = null
        viewModelScope.launch {
            _actionButton.value = commandRepository.getButton(buttonId)
        }
    }

    fun clearBlockedMessage() {
        _blockedMessage.value = null
    }

    fun clearActionButton() {
        _actionButton.value = null
    }

    fun clearPendingRisky() {
        _pendingRisky.value = null
    }

    /**
     * Act on a prepared command. When [confirmedRisky] is false and
     * the command is risky, this only raises the risky dialog and
     * sends nothing. Call again with confirmedRisky = true from
     * the dialog's confirm tap to actually execute.
     */
    fun actOnPrepared(prepared: PreparedCommand, confirmedRisky: Boolean) {
        if (_uiState.value.status != SessionStatus.CONNECTED || terminalSession == null) {
            return
        }
        // Remediation phase 1 — the execution gate, FIRST,
        // before the risky dialog and before any submission,
        // in every mode (insert counts: inserted text becomes
        // execution when the user presses Enter). The dialog's
        // disabled button is only a mirror of this check; a
        // prepared command that reaches here by any other path
        // — including the risky-confirm re-submission of a
        // stale prepared object — is refused here, and the
        // screen explains why and what to do.
        _blockedMessage.value = null
        val block = CommandTemplate.blockReason(
            prepared, _connectedShell.value, _connectedShellConfirmed.value
        )
        if (block != null) {
            _pendingRisky.value = null
            _actionButton.value = null
            _blockedMessage.value = block
            return
        }
        if (prepared.isRisky && !confirmedRisky) {
            _pendingRisky.value = prepared
            return
        }
        _pendingRisky.value = null
        _actionButton.value = null
        when (prepared.button.mode) {
            com.pishi.termbridge.domain.CommandMode.INSERT_ONLY -> {
                commandRunner.insert(prepared)
            }
            com.pishi.termbridge.domain.CommandMode.EXECUTE_ONE -> {
                commandRunner.executeOne(prepared)
            }
            com.pishi.termbridge.domain.CommandMode.SEQUENCE -> {
                // One sequence at a time: starting a second one
                // while one runs would interleave submissions.
                // The user stops the running sequence first.
                if (sequenceJob?.isActive == true) return
                _activeSequence.value = ActiveSequence(
                    label = prepared.button.label,
                    progress = SequenceProgress(0, prepared.renderedSteps.size)
                )
                sequenceJob = viewModelScope.launch {
                    try {
                        commandRunner.executeSequence(prepared) { progress ->
                            _activeSequence.value = ActiveSequence(
                                label = prepared.button.label,
                                progress = progress
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Stop: remaining steps are never sent.
                        // The progress state keeps the last sent
                        // step so the user can see where it stopped.
                        val last = _activeSequence.value
                        if (last != null) {
                            _activeSequence.value = last.copy(stopped = true)
                        }
                        throw e
                    } finally {
                        if (_activeSequence.value?.stopped != true) {
                            _activeSequence.value = null
                        }
                    }
                }
            }
        }
    }

    /** Stop a running sequence. Already-sent steps keep running
     *  on the server; only further submissions are stopped. */
    fun stopSequence() {
        sequenceJob?.cancel()
        sequenceJob = null
        val last = _activeSequence.value
        if (last != null && last.progress.stepIndex < last.progress.totalSteps) {
            _activeSequence.value = last.copy(stopped = true)
        } else {
            _activeSequence.value = null
        }
    }

    fun clearStoppedSequence() {
        _activeSequence.value = null
    }

    /**
     * Pure preparation helper for the parameter dialog preview.
     * Uses the connected host's shell (security fix 16, H1)
     * and its confirmation state (remediation phase 1),
     * like the dialog itself does.
     */
    fun preparePreview(
        button: CommandButton,
        supplied: Map<String, String>
    ): PreparedCommand = CommandTemplate.prepare(
        button, supplied, _connectedShell.value, _connectedShellConfirmed.value
    )

    // Terminal input — raw bytes to the shell, not commands.
    fun sendText(text: String) = terminalSession?.sendText(text)
    fun sendEnter() = terminalSession?.sendEnter()
    fun sendCtrlC() = terminalSession?.sendCtrlC()
    fun sendCtrlD() = terminalSession?.sendCtrlD()
    fun sendTab() = terminalSession?.sendTab()
    fun sendEscape() = terminalSession?.sendEscape()
    fun sendBackspace() = terminalSession?.sendByte(0x7F)
    fun sendArrowUp() = terminalSession?.sendArrowUp()
    fun sendArrowDown() = terminalSession?.sendArrowDown()
    fun sendArrowLeft() = terminalSession?.sendArrowLeft()
    fun sendArrowRight() = terminalSession?.sendArrowRight()
    fun resizeTerminal(cols: Int, rows: Int) = terminalSession?.resize(cols, rows)

    override fun onCleared() {
        sequenceJob?.cancel()
        buttonsJob?.cancel()
        terminalSession?.close()
        terminalSession = null
        connection?.disconnect()
        connection = null
        _envStatus.value = null
        _connectedShellConfirmed.value = false
        super.onCleared()
    }
}

class SessionViewModelFactory(
    private val repository: HostRepository,
    private val commandRepository: CommandRepository,
    private val environmentRepository: HostEnvironmentRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            return SessionViewModel(repository, commandRepository, environmentRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
