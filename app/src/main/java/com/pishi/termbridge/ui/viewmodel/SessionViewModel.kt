package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.R
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.EnvVarValue
import com.pishi.termbridge.domain.EnvironmentValidator
import com.pishi.termbridge.domain.CommandRunner
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.LatchState
import com.pishi.termbridge.domain.ModifierLatch
import com.pishi.termbridge.domain.SpecialKey
import com.pishi.termbridge.domain.TerminalKeyEncoder
import com.pishi.termbridge.domain.PreparedCommand
import com.pishi.termbridge.domain.RiskyCommand
import com.pishi.termbridge.domain.SequenceProgress
import com.pishi.termbridge.domain.TerminalSender
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.terminal.TerminalSession
import com.pishi.termbridge.terminal.TerminalSnapshot
import com.pishi.termbridge.terminal.asText
import com.pishi.termbridge.transport.HostKeyDecisionHandler
import com.pishi.termbridge.transport.PendingTrust
import com.pishi.termbridge.transport.SshConnection
import com.pishi.termbridge.transport.SshConnectionRequest
import com.pishi.termbridge.transport.SshTransport
import com.pishi.termbridge.transport.SshTransportException
import com.pishi.termbridge.transport.SshjConnection
import com.pishi.termbridge.ui.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.update
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
    // Audit #3 (A-09): UiMessage (resource id + args, or
    // ready-made lower-layer text); the screen resolves.
    val errorMessage: UiMessage? = null,
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
    val workingDirectory: String? = null,
    /** Item F-02: names applied by the typed export fallback
     *  (a subset of [appliedNames], shown separately so the
     *  user can tell HOW they were set). */
    val fallbackNames: List<String> = emptyList()
)

/**
 * Audit #3 (A-13): shell-history lines that were fetched and
 * admitted but are NOT recorded yet. The screen shows the count
 * and a preview; only an explicit confirm records [lines].
 */
data class PendingHistoryImport(
    /** How many lines passed parsing + admission. */
    val totalAccepted: Int,
    /** The first lines, for the preview dialog. */
    val preview: List<String>,
    /** Every admitted line; recorded only on confirm. */
    val lines: List<String>
)

class SessionViewModel(
    private val repository: HostRepository,
    private val commandRepository: CommandRepository,
    private val environmentRepository: HostEnvironmentRepository,
    private val appSettings: com.pishi.termbridge.settings.AppSettingsStore? = null,
    private val connectionLog: com.pishi.termbridge.settings.ConnectionLogStore? = null,
    customThemeRepository: com.pishi.termbridge.settings.CustomThemeRepository? = null,
    private val keychainRepository: com.pishi.termbridge.data.KeychainRepository? = null,
    private val snippetRepository: com.pishi.termbridge.data.SnippetRepository? = null,
    private val transcriptStore: com.pishi.termbridge.settings.TranscriptStorage? = null,
    private val commandHistoryStore: com.pishi.termbridge.settings.CommandHistoryStore? = null
) : ViewModel() {
    /** User-defined colour schemes (item T-08), for resolution. */
    val customThemes: kotlinx.coroutines.flow.StateFlow<List<com.pishi.termbridge.domain.TerminalTheme>> =
        customThemeRepository?.themes
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    /** Ephemeral host id for quick connect (item T-04); never a Room row. */
    private val QUICK_HOST_ID = -1L

    // Item T-11: autocomplete state (tracker + suggestions).
    private val inputTracker = com.pishi.termbridge.domain.InputLineTracker()
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()
    private val _historyImportMessage = MutableStateFlow<UiMessage?>(null)
    val historyImportMessage: StateFlow<UiMessage?> = _historyImportMessage.asStateFlow()

    // Audit #3 (A-13): fetched history awaiting the user's
    // preview confirmation; null when no import is pending.
    private val _pendingHistoryImport = MutableStateFlow<PendingHistoryImport?>(null)
    val pendingHistoryImport: StateFlow<PendingHistoryImport?> =
        _pendingHistoryImport.asStateFlow()

    /** Item T-11: the history files, read over the exec
     *  channel only. Missing files are silent (2>/dev/null). */
    private val HISTORY_IMPORT_COMMAND =
        "cat ~/.bash_history ~/.zsh_history " +
            "~/.local/share/fish/fish_history 2>/dev/null | tail -n 4000"

    /** Audit #3 (A-13): how many admitted lines the import
     *  preview dialog shows before recording. */
    private val HISTORY_IMPORT_PREVIEW_LINES = 10

    /** Audit #6 TB-006: transcript queue element bound. */
    private val TRANSCRIPT_CHANNEL_CAPACITY = 256

    // Item T-16: the detected remote OS (display text), or
    // null when not probed / not detectable.
    private val _detectedOs = MutableStateFlow<UiMessage?>(null)
    val detectedOs: StateFlow<UiMessage?> = _detectedOs.asStateFlow()

    // Audit #3 (A-20): true when the probed OS family clearly
    // contradicts the connected host's declared shell family —
    // the screen warns next to the OS line, because button
    // quoting follows the DECLARED shell, not the probed OS.
    private val _shellMismatchWarning = MutableStateFlow(false)
    val shellMismatchWarning: StateFlow<Boolean> = _shellMismatchWarning.asStateFlow()

    /**
     * Item T-16: probes the remote OS over the exec channel —
     * never typed into the shell. Runs on demand, and after a
     * connect only when the user enabled it in settings.
     * Failure is a display outcome, never a session error.
     */
    fun detectOs() {
        val channel = connection as? com.pishi.termbridge.transport.SshExecChannel
            ?: return
        viewModelScope.launch {
            val detected = try {
                val posix = com.pishi.termbridge.domain.OsProbe.parsePosix(
                    channel.execOnce(
                        com.pishi.termbridge.domain.OsProbe.POSIX_COMMAND, 10_000
                    )
                )
                posix ?: com.pishi.termbridge.domain.OsProbe.parseWindows(
                    channel.execOnce(
                        com.pishi.termbridge.domain.OsProbe.WINDOWS_COMMAND, 10_000
                    )
                )
            } catch (_: Exception) {
                null
            }
            _detectedOs.value = detected?.let { UiMessage.ofText(it.display) }
                ?: UiMessage.of(R.string.session_unknown_os)
            // Audit #3 (A-20): compare families only — a failed
            // probe (null) carries no signal and never warns.
            _shellMismatchWarning.value =
                com.pishi.termbridge.domain.ShellMismatch.isMismatch(
                    detected, _connectedShell.value
                )
        }
    }

    /** Live user preferences (font size, scrollback, keep-alive...). */
    // MUST stay above the init block below: viewModelScope runs on
    // Dispatchers.Main.immediate, so the init block's launched
    // coroutine can start INLINE during construction (before any
    // property declared after it has been initialized). When this
    // val sat below init, the first history emission reached
    // autocompleteOn() while this field was still null and crashed
    // the app on launch (CI run #65, MainActivitySmokeTest).
    val settings: StateFlow<com.pishi.termbridge.domain.AppSettings> =
        appSettings?.settings ?: MutableStateFlow(com.pishi.termbridge.domain.AppSettings())

    init {
        // Imported/recorded history refreshes open suggestions.
        val store = commandHistoryStore
        if (store != null) {
            viewModelScope.launch {
                store.entries.collect { refreshSuggestions() }
            }
        }
    }

    private fun autocompleteOn(): Boolean =
        settings.value.autocompleteEnabled && commandHistoryStore != null

    private fun refreshSuggestions() {
        _suggestions.value = if (autocompleteOn()) {
            com.pishi.termbridge.domain.Autocomplete.suggestions(
                inputTracker.currentLine,
                commandHistoryStore!!.entries.value
            )
        } else {
            emptyList()
        }
    }

    private fun submitTrackedLine() {
        val line = inputTracker.submit()
        if (autocompleteOn()) commandHistoryStore?.record(line)
        refreshSuggestions()
    }

    /** Item T-11: types the remainder of a chosen suggestion. */
    fun applySuggestion(suggestion: String) {
        val suffix = suggestion.removePrefix(inputTracker.currentLine)
        if (suffix.isNotEmpty()) sendText(suffix)
    }

    /**
     * Item T-11: reads the remote shell history files over a
     * SEPARATE exec channel on the live connection (never the
     * interactive shell), after the user's explicit consent in
     * the UI. Lines pass ShellHistoryImport + CommandAdmission,
     * so secret-looking lines never reach the store.
     *
     * Audit #3 (A-13): fetching no longer records. The admitted
     * lines wait in [pendingHistoryImport] while the screen
     * shows the count and a preview; [confirmHistoryImport]
     * records them, [dismissHistoryImport] drops them.
     */
    fun importShellHistory() {
        commandHistoryStore ?: return
        val channel = connection as? com.pishi.termbridge.transport.SshExecChannel
        if (channel == null) {
            _historyImportMessage.value =
                UiMessage.of(R.string.history_import_needs_session)
            return
        }
        _historyImportMessage.value =
            UiMessage.of(R.string.history_import_running)
        viewModelScope.launch {
            try {
                val raw = channel.execOnce(HISTORY_IMPORT_COMMAND, 15_000)
                val lines = com.pishi.termbridge.domain.ShellHistoryImport.parse(raw)
                _historyImportMessage.value = null
                _pendingHistoryImport.value = PendingHistoryImport(
                    totalAccepted = lines.size,
                    preview = lines.take(HISTORY_IMPORT_PREVIEW_LINES),
                    lines = lines
                )
            } catch (_: Exception) {
                _historyImportMessage.value =
                    UiMessage.of(R.string.history_import_failed)
            }
        }
    }

    /** Audit #3 (A-13): record a previewed import for real. */
    fun confirmHistoryImport() {
        val pending = _pendingHistoryImport.value ?: return
        val store = commandHistoryStore ?: return
        _pendingHistoryImport.value = null
        val added = store.recordImported(pending.lines)
        _historyImportMessage.value = UiMessage.of(
            R.string.history_import_done, added, pending.lines.size
        )
        refreshSuggestions()
    }

    /** Audit #3 (A-13): drop a previewed import unrecorded. */
    fun dismissHistoryImport() {
        _pendingHistoryImport.value = null
    }

    // Item T-03: transcript capture state (all null/0 = off).
    // Audit #6 TB-002: line tracking is ABSOLUTE (see
    // TranscriptLineTracker) — a raw line-count diff stalls
    // forever once the parser's scrollback cap is reached.
    private var transcriptTarget: Long? = null
    private val transcriptTracker =
        com.pishi.termbridge.domain.TranscriptLineTracker()

    /** Audit #6 TB-006: transcript chunks dropped at the bound. */
    private var transcriptDroppedChunks = 0L
    private var transcriptChannel: kotlinx.coroutines.channels.Channel<String>? = null
    private var transcriptJob: Job? = null

    private var sessionStartedAt = 0L
    private var sessionHostName = ""

    /** Local history line; never stores terminal output or secrets. */
    private fun logEntry(
        hostName: String,
        outcome: com.pishi.termbridge.domain.ConnectionOutcome,
        durationMs: Long = 0L,
        detail: String = ""
    ) {
        if (hostName.isBlank()) return
        connectionLog?.record(
            com.pishi.termbridge.domain.ConnectionLogEntry(
                timestampMs = System.currentTimeMillis(),
                hostName = hostName,
                outcome = outcome,
                durationMs = durationMs,
                detail = detail
            )
        )
    }

    /** Pinch-to-zoom target: persists the new font size (clamped by the store). */
    fun setFontSize(sp: Int) {
        appSettings?.update { it.copy(terminalFontSp = sp) }
    }

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
    // Audit #3 (A-21): written on the sshj reader thread (inside
    // awaitDecision) and completed from the main thread — the
    // reference itself must be safely published across threads.
    @Volatile
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
        // Atomic update: this runs on the sshj transport thread while
        // the main thread also writes uiState (review #2 F-24).
        _uiState.update { it.copy(pendingTrust = pending) }
        try {
            return deferred.await()
        } finally {
            decisionDeferred = null
            _uiState.update { if (it.pendingTrust == pending) it.copy(pendingTrust = null) else it }
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
        prepareConnect()
        connectJob = viewModelScope.launch {
            var credentialBytes: ByteArray? = null
            var passphraseChars: CharArray? = null
            try {
                val profile = repository.getHost(hostId)
                sessionHostName = profile?.name ?: ""
                if (profile == null) {
                    _uiState.value = _uiState.value.copy(
                        status = SessionStatus.FAILED,
                        errorMessage = UiMessage.of(R.string.vm_error_host_not_found)
                    )
                    return@launch
                }
                credentialBytes = repository.loadCredential(hostId)
                if (credentialBytes == null) {
                    _uiState.value = _uiState.value.copy(
                        status = SessionStatus.FAILED,
                        errorMessage = UiMessage.of(R.string.vm_error_no_credential)
                    )
                    return@launch
                }
                passphraseChars = if (profile.authType == AuthType.PRIVATE_KEY && passphrase.isNotEmpty()) {
                    passphrase.toCharArray()
                } else {
                    null
                }
                startConnection(profile, credentialBytes, passphraseChars)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Resolution failed before any transport existed
                // (e.g. the stored credential no longer decrypts).
                logEntry(
                    sessionHostName, com.pishi.termbridge.domain.ConnectionOutcome.FAILED,
                    detail = "Connection failed."
                )
                _uiState.update {
                    it.copy(
                        status = SessionStatus.FAILED,
                        errorMessage = UiMessage.of(R.string.vm_error_connection_failed)
                    )
                }
            } finally {
                credentialBytes?.wipe()
                passphraseChars?.wipe()
            }
        }
    }

    /** Cancel any attempt, close any live session, show CONNECTING. */
    private fun prepareConnect() {
        connectJob?.cancel()
        // Audit #3 (A-04): cancelling the job cannot interrupt a
        // blocking sshj handshake, so tear down the previous
        // attempt's socket explicitly — BEFORE the new attempt
        // registers its own client, so only the stale one dies.
        transport.closeInFlight()
        // Review #2 F-05: a reconnect (or a retry after a failure)
        // must not orphan the previous authenticated transport.
        closeLiveSession()
        _uiState.value = _uiState.value.copy(
            status = SessionStatus.CONNECTING,
            errorMessage = null,
            pendingTrust = null
        )
    }

    /**
     * True quick connect (item T-04): dial an UNSAVED host from a
     * parsed quick-connect line. The credential arrives as a
     * [com.pishi.termbridge.domain.CredentialInput] collected by
     * the UI in its own field — never inside the free-text line —
     * and nothing is written to the database: no host row, no
     * stored credential. Command buttons and environment
     * settings are per-host features and do not apply (their
     * lookups key on the ephemeral id and return empty).
     */
    fun quickConnect(
        target: com.pishi.termbridge.domain.QuickConnectTarget,
        username: String,
        credential: com.pishi.termbridge.domain.CredentialInput
    ) {
        val user = username.ifBlank { target.username ?: "" }
        val authType = when (credential) {
            is com.pishi.termbridge.domain.CredentialInput.Password -> AuthType.PASSWORD
            is com.pishi.termbridge.domain.CredentialInput.PrivateKey -> AuthType.PRIVATE_KEY
        }
        val profile = HostProfile(
            id = QUICK_HOST_ID,
            name = if (user.isBlank()) target.host else "$user@${target.host}",
            hostname = target.host,
            port = target.port,
            username = user,
            authType = authType
        )
        val credentialBytes: ByteArray
        val passphraseChars: CharArray?
        when (credential) {
            is com.pishi.termbridge.domain.CredentialInput.Password -> {
                credentialBytes = String(credential.password).toByteArray(Charsets.UTF_8)
                credential.password.wipe()
                passphraseChars = null
            }
            is com.pishi.termbridge.domain.CredentialInput.PrivateKey -> {
                credentialBytes = credential.keyPem.toByteArray(Charsets.UTF_8)
                passphraseChars = credential.passphrase
            }
        }
        startQuick(profile, credentialBytes, passphraseChars)
    }

    /**
     * Quick connect with a keychain entry (item T-04 + T-01):
     * the entry's material is decrypted for this one connection
     * and wiped with it; the passphrase (for key entries) is
     * asked by the UI, never stored.
     */
    fun quickConnectWithKeychain(
        target: com.pishi.termbridge.domain.QuickConnectTarget,
        username: String,
        entryId: Long,
        passphrase: String
    ) {
        val repo = keychainRepository ?: return
        viewModelScope.launch {
            val entry = repo.getEntry(entryId) ?: return@launch
            val material = repo.loadMaterial(entryId) ?: return@launch
            val user = username.ifBlank { target.username ?: "" }
            val profile = HostProfile(
                id = QUICK_HOST_ID,
                name = if (user.isBlank()) target.host else "$user@${target.host}",
                hostname = target.host,
                port = target.port,
                username = user,
                authType = entry.kind
            )
            val passphraseChars = if (
                entry.kind == AuthType.PRIVATE_KEY && passphrase.isNotEmpty()
            ) {
                passphrase.toCharArray()
            } else {
                null
            }
            startQuick(profile, material, passphraseChars)
        }
    }

    private fun startQuick(
        profile: HostProfile,
        credentialBytes: ByteArray,
        passphraseChars: CharArray?
    ) {
        sessionHostName = profile.name
        prepareConnect()
        connectJob = viewModelScope.launch {
            startConnection(profile, credentialBytes, passphraseChars)
        }
    }

    private suspend fun startConnection(
        profile: HostProfile,
        credentialBytes: ByteArray,
        passphraseChars: CharArray?
    ) {
        var envValues: List<EnvVarValue> = emptyList()
        try {
            val conn = transport.connect(
                SshConnectionRequest(
                    profile = profile,
                    credential = credentialBytes,
                    passphrase = passphraseChars,
                    keepAliveSeconds = settings.value.keepAliveSeconds,
                    keepAliveMaxMisses = settings.value.keepAliveMaxMisses
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
                    errorMessage = UiMessage.of(R.string.session_error_terminal_start)
                )
                return
            }
            val prefs = settings.value
            val terminal = TerminalSession(
                sshjConn, 80, 24,
                terminalType = prefs.terminalType,
                scrollbackLines = prefs.scrollbackLines
            )
            terminal.onClosed = { error ->
                // Runs on the terminal's IO threads: atomic update.
                if (sessionStartedAt > 0L) {
                    logEntry(
                        sessionHostName, com.pishi.termbridge.domain.ConnectionOutcome.CLOSED,
                        durationMs = System.currentTimeMillis() - sessionStartedAt
                    )
                    sessionStartedAt = 0L
                }
                _uiState.update {
                    if (it.status == SessionStatus.CONNECTED) {
                        it.copy(
                            status = SessionStatus.FAILED,
                            errorMessage = if (error == null) {
                                UiMessage.of(R.string.session_shell_closed)
                            } else {
                                UiMessage.of(R.string.session_connection_lost)
                            }
                        )
                    } else {
                        it
                    }
                }
            }
            terminalSession = terminal
            // Was a bare `launch` inside the old connect coroutine;
            // startConnection is a suspend fun, so scope it explicitly.
            viewModelScope.launch {
                terminal.snapshot.collect { snap ->
                    _terminalSnapshot.value = snap
                    // Item T-03: transcript capture — new lines
                    // only, scrubbed before they queue. Absolute
                    // line positions (audit #6 TB-002): a line is
                    // recorded when it scrolls into history; the
                    // final screenful is not captured (documented
                    // limit), but capture no longer stalls at the
                    // scrollback cap.
                    val target = transcriptTarget
                    if (target != null) {
                        val range = transcriptTracker.advance(
                            snap.scrollbackTrimmed, snap.lines.size
                        )
                        if (range != null) {
                            val fresh = snap.lines.subList(
                                range.first, range.last + 1
                            )
                            val text = fresh.joinToString("\n") { line ->
                                com.pishi.termbridge.domain.SecretRedactor
                                    .scrubFreeText(line.asText())
                            } + "\n"
                            if (transcriptChannel?.trySend(text)?.isFailure == true) {
                                transcriptDroppedChunks++
                            }
                        }
                    }
                }
            }
            // Prompt 09: env vars are requested with the shell
            // (setEnvVar before startShell, inside
            // TerminalSession.start), and the initial working
            // directory is one visible `cd` immediately after
            // the shell starts, before CONNECTED is set — so
            // it is the first input the shell sees. Neither
            // mechanism touches command buttons: buttons are
            // user-authored exact text (prompt 08 guarantee).
            val decrypted = environmentRepository.loadEnvVarsForConnection(profile.id)
            envValues = decrypted.values
            val settings = environmentRepository.getSettingsOnce(profile.id)
            // Security fix 16 (H1): buttons are quoted and
            // classified for THIS host's shell, never for an
            // assumed POSIX shell.
            _connectedShell.value = settings?.remoteShell
                ?: com.pishi.termbridge.domain.RemoteShell.POSIX
            _connectedShellConfirmed.value = settings?.shellConfirmed ?: false
            // Item T-03: start the transcript when this host
            // opted in (saved hosts only — never quick connect).
            if (settings?.sessionLogEnabled == true && profile.id > 0) {
                startTranscript(profile)
            }
            val envPairs = envValues.map { it.name to String(it.value) }
            var envResult: com.pishi.termbridge.domain.EnvApplyResult? = null
            terminal.start(envPairs) { result ->
                envResult = result
                _envStatus.value = EnvStatus(
                    configuredNames = envPairs.map { it.first } +
                        decrypted.undecryptableNames,
                    appliedNames = result.appliedNames,
                    rejectedNames = result.rejectedNames +
                        decrypted.undecryptableNames,
                    workingDirectory = null
                )
            }
            // Item F-02: the opt-in shell-export fallback. The
            // server refused these names through the env channel
            // (its sshd has no matching AcceptEnv — a server-admin
            // setting). With the per-host toggle on AND the shell
            // user-confirmed, type one visible export line per
            // refused variable instead, before the initial cd.
            // Without either condition nothing is typed and the
            // names stay in the rejected list, exactly as before.
            val capturedEnv = envResult
            if (capturedEnv != null && settings != null &&
                settings.envExportFallback && settings.shellConfirmed &&
                capturedEnv.rejectedNames.isNotEmpty()
            ) {
                val valuesByName = envValues.associate {
                    it.name to String(it.value)
                }
                val plan = com.pishi.termbridge.domain.ExportCommand.planFallback(
                    valuesByName, capturedEnv.rejectedNames, settings.remoteShell
                )
                for ((_, line) in plan.lines) {
                    terminal.sendText(line)
                    terminal.sendEnter()
                }
                if (plan.lines.isNotEmpty()) {
                    val fallbackNames = plan.lines.map { it.first }
                    val current = _envStatus.value ?: EnvStatus()
                    _envStatus.value = current.copy(
                        appliedNames = current.appliedNames + fallbackNames,
                        rejectedNames = current.rejectedNames
                            .filter { it !in fallbackNames },
                        fallbackNames = fallbackNames
                    )
                }
            }
            val workingDir = settings?.remoteWorkingDirectory?.takeIf { it.isNotBlank() }
            if (workingDir != null && settings != null) {
                // Re-validate at the point of use (review #2 F-03):
                // a value stored before the control-character rule
                // existed must not be typed into the shell.
                val safeCd = try {
                    EnvironmentValidator.canonicalDirectory(workingDir, settings.remoteShell)
                        ?.let { CdCommand.render(it, settings.remoteShell) }
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (safeCd != null) {
                    terminal.sendText(safeCd)
                    terminal.sendEnter()
                    _envStatus.value = (_envStatus.value ?: EnvStatus()).copy(
                        workingDirectory = workingDir
                    )
                }
            }
            sessionStartedAt = System.currentTimeMillis()
            logEntry(profile.name, com.pishi.termbridge.domain.ConnectionOutcome.CONNECTED)
            // Item T-22: stamp the last-connected time for the
            // hosts list. Bookkeeping only — a failure here must
            // never affect the session, and a quick-connect host
            // (negative id) has no row to stamp.
            if (profile.id > 0) {
                try {
                    repository.touchLastConnected(profile.id)
                } catch (e: Exception) {
                    // Deliberately ignored: see above.
                }
            }
            _uiState.value = _uiState.value.copy(
                status = SessionStatus.CONNECTED,
                connectedHost = profile,
                errorMessage = null
            )
            // Item T-16: auto-detect only when the user turned
            // it on (default off); manual detection is in the UI.
            // (this.settings is the AppSettings flow — the local
            // `settings` here is the host environment settings.)
            if (this.settings.value.detectOsOnConnect) {
                detectOs()
            }
            // Prompt 08: load this host's buttons for the
            // session's buttons area. Another host's buttons are
            // never offered here; global snippets (T-02) are the
            // one deliberate exception, merged below.
            buttonsJob?.cancel()
            // viewModelScope, not a child of connectJob: the
            // buttons flow must outlive the connect attempt
            // itself and be cancelled only on disconnect.
            buttonsJob = viewModelScope.launch {
                // Per-host buttons first, then the global
                // snippets (item T-02), which every session —
                // including a quick-connect one — offers.
                val globals = snippetRepository?.getSnippets()
                    ?: kotlinx.coroutines.flow.flowOf(emptyList())
                kotlinx.coroutines.flow.combine(
                    commandRepository.getButtons(profile.id), globals
                ) { own, global -> own + global }.collect {
                    _commandButtons.value = it
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A superseded or cancelled attempt must not write
            // FAILED over the state of the attempt that replaced
            // it; whoever cancelled owns the state.
            throw e
        } catch (e: SshTransportException) {
            logEntry(
                sessionHostName, com.pishi.termbridge.domain.ConnectionOutcome.FAILED,
                detail = e.userFacingMessage()
            )
            closeLiveSession()
            _uiState.update {
                it.copy(
                    status = SessionStatus.FAILED,
                    errorMessage = UiMessage.ofText(e.userFacingMessage())
                )
            }
        } catch (e: Exception) {
            logEntry(
                sessionHostName, com.pishi.termbridge.domain.ConnectionOutcome.FAILED,
                detail = "Connection failed."
            )
            // Generic only — repository/transport messages are
            // already secret-free, but this is the second net.
            // Review #2 F-05: a failure AFTER authentication
            // (e.g. the shell could not start) must close the
            // already-authenticated transport.
            closeLiveSession()
            _uiState.update {
                it.copy(
                    status = SessionStatus.FAILED,
                    errorMessage = UiMessage.of(R.string.vm_error_connection_failed)
                )
            }
        } finally {
            credentialBytes.wipe()
            passphraseChars?.wipe()
            envValues.forEach { it.value.wipe() }
        }
    }

    /**
     * Close the current terminal channel and transport WITHOUT
     * touching UI state (review #2 F-05). Closing sends protocol
     * messages over the socket, so it runs on IO.
     */
    private fun closeLiveSession() {
        val sessionToClose = terminalSession
        val connectionToClose = connection
        terminalSession = null
        connection = null
        if (sessionToClose == null && connectionToClose == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionToClose?.close()
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun cancel() {
        decisionDeferred?.complete(false)
        connectJob?.cancel()
        // Audit #3 (A-04): the job cancel alone leaves a blocking
        // handshake socket open until its timeout; close it now.
        transport.closeInFlight()
        disconnect()
        _uiState.value = _uiState.value.copy(status = SessionStatus.IDLE, errorMessage = null)
    }

    /** Item T-03: opens the transcript queue for [profile] and
     *  writes the session header. The consumer is the ONLY
     *  writer, so appends stay ordered; text arrives already
     *  scrubbed from the snapshot collector. */
    private fun startTranscript(profile: com.pishi.termbridge.domain.HostProfile) {
        val store = transcriptStore ?: return
        stopTranscript()
        // Audit #6 TB-006: bounded — a stalled disk must not let
        // scrubbed chunks accumulate in the heap without limit.
        // Overflow drops chunks and counts them in
        // transcriptDroppedChunks.
        val channel = kotlinx.coroutines.channels.Channel<String>(
            TRANSCRIPT_CHANNEL_CAPACITY
        )
        transcriptChannel = channel
        transcriptTracker.reset()
        transcriptTarget = profile.id
        transcriptJob = viewModelScope.launch(Dispatchers.IO) {
            for (chunk in channel) {
                try {
                    store.append(profile.id, chunk)
                } catch (_: Exception) {
                    // A full disk must never break the session;
                    // the transcript just stops growing.
                }
            }
        }
        channel.trySend(
            "=== TermBridge session: ${profile.name} " +
                "(${profile.username}@${profile.hostname}:${profile.port}) " +
                "started ${java.time.Instant.now()} ===\n"
        )
    }

    /** Item T-03: closes the transcript queue (the consumer
     *  drains what is queued, then ends). */
    private fun stopTranscript() {
        transcriptTarget = null
        transcriptChannel?.close()
        transcriptChannel = null
        transcriptJob = null
        transcriptTracker.reset()
    }

    fun disconnect() {
        stopTranscript()
        inputTracker.reset()
        _suggestions.value = emptyList()
        _historyImportMessage.value = null
        _pendingHistoryImport.value = null
        _pendingShakeEffect.value = null
        _detectedOs.value = null
        _shellMismatchWarning.value = false
        if (_uiState.value.status == SessionStatus.CONNECTED && sessionStartedAt > 0L) {
            logEntry(
                sessionHostName, com.pishi.termbridge.domain.ConnectionOutcome.CLOSED,
                durationMs = System.currentTimeMillis() - sessionStartedAt
            )
            sessionStartedAt = 0L
        }
        connectJob?.cancel()
        // Audit #3 (A-04): if disconnect is tapped mid-handshake,
        // the in-flight socket is closed too, not just the job.
        // (An established connection is not in the registry; it
        // is closed through the normal path below.)
        transport.closeInFlight()
        stopSequence()
        // A latched modifier must never leak into the next session.
        keyLatch.clear()
        publishLatch()
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
        // disconnect. Review #1 (C-02): closing sends
        // SSH_MSG_CHANNEL_CLOSE / SSH_MSG_DISCONNECT over the socket,
        // so it runs on IO, never on the calling (main) thread.
        val sessionToClose = terminalSession
        val connectionToClose = connection
        terminalSession = null
        _terminalSnapshot.value = null
        connection = null
        decisionDeferred?.complete(false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionToClose?.close()
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
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
        // Item T-11: a button writes to the shell line itself;
        // the typed-line tracker no longer describes it.
        inputTracker.reset()
        refreshSuggestions()
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
        // Review #2 F-14: do not trust the caller-supplied flag
        // alone — re-derive the classification from the text that
        // is about to be typed, so a hand-built PreparedCommand with
        // isRisky = false cannot skip the confirmation.
        val reclassified = when (prepared.button.mode) {
            com.pishi.termbridge.domain.CommandMode.SEQUENCE ->
                RiskyCommand.classifySteps(prepared.renderedSteps, prepared.shell)
            else -> RiskyCommand.classify(prepared.rendered, prepared.shell)
        }
        val treatAsRisky = prepared.isRisky || reclassified.isRisky
        if (treatAsRisky && !confirmedRisky) {
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
    // Item T-11: every path also maintains the input tracker —
    // plain text composes, anything that makes the shell-side
    // line unknowable resets it.
    fun sendText(text: String) {
        if (text.any { com.pishi.termbridge.domain.ControlChars.isControl(it) }) {
            inputTracker.reset()
        } else {
            inputTracker.type(text)
        }
        refreshSuggestions()
        terminalSession?.sendText(text)
    }

    fun sendEnter() {
        submitTrackedLine()
        terminalSession?.sendEnter()
    }

    fun sendCtrlC() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendCtrlC()
    }

    fun sendCtrlD() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendCtrlD()
    }

    fun sendTab() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendTab()
    }

    fun sendEscape() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendEscape()
    }

    fun sendBackspace() {
        inputTracker.backspace()
        refreshSuggestions()
        terminalSession?.sendByte(0x7F)
    }
    fun sendArrowUp() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendArrowUp()
    }

    fun sendArrowDown() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendArrowDown()
    }

    fun sendArrowLeft() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendArrowLeft()
    }

    fun sendArrowRight() {
        inputTracker.reset()
        refreshSuggestions()
        terminalSession?.sendArrowRight()
    }
    fun resizeTerminal(cols: Int, rows: Int) = terminalSession?.resize(cols, rows)

    // Extra-keys bar (item T-09): sticky/lock Ctrl and Alt.
    // The latch lives here, not in the composable, so a
    // recomposition can never lose a latched modifier mid-key.
    private val keyLatch = ModifierLatch()

    private val _latchState = MutableStateFlow(LatchUiState())
    val latchState: StateFlow<LatchUiState> = _latchState.asStateFlow()

    /** What the bar renders for the two modifier buttons. */
    data class LatchUiState(
        val ctrl: LatchState = LatchState.OFF,
        val alt: LatchState = LatchState.OFF
    )

    private fun publishLatch() {
        _latchState.value = LatchUiState(ctrl = keyLatch.ctrl, alt = keyLatch.alt)
    }

    fun pressCtrlLatch() {
        keyLatch.pressCtrl()
        publishLatch()
    }

    fun pressAltLatch() {
        keyLatch.pressAlt()
        publishLatch()
    }

    /** A bar key: encoded with the latched modifiers, which a
     *  one-shot latch spends on this press. */
    fun sendSpecialKey(key: SpecialKey) {
        // Item T-11: keep the input tracker honest per key.
        when (key) {
            SpecialKey.BACKSPACE -> inputTracker.backspace()
            SpecialKey.ENTER -> submitTrackedLine()
            else -> inputTracker.reset()
        }
        if (key != SpecialKey.ENTER) refreshSuggestions()
        val bytes = TerminalKeyEncoder.encode(key, keyLatch.consume())
        publishLatch()
        terminalSession?.sendBytes(bytes)
    }

    // Hardware actions (item T-19): volume keys + shake. The
    // mapping itself is pure domain logic (DeviceActionMapping);
    // here it is executed against the live session. Both entry
    // points return false when nothing was consumed — no live
    // session, or the action is disabled — so the caller (the
    // activity for volume keys) falls back to system behaviour.

    /** Cumulative page-scroll requests from the volume keys;
     *  the terminal view applies the delta since it last read. */
    private val _scrollRequest = MutableStateFlow(0)
    val scrollRequest: StateFlow<Int> = _scrollRequest.asStateFlow()

    /**
     * Audit #3 (A-10): a shake effect (disconnect / clear
     * screen) that fired and is WAITING for the user's
     * confirmation in the dialog — it has not acted yet. Null
     * when no shake confirmation is pending.
     */
    private val _pendingShakeEffect =
        MutableStateFlow<com.pishi.termbridge.domain.ShakeEffect?>(null)
    val pendingShakeEffect:
        StateFlow<com.pishi.termbridge.domain.ShakeEffect?> =
        _pendingShakeEffect.asStateFlow()

    fun onVolumeKey(
        key: com.pishi.termbridge.domain.VolumeKey,
        isRepeat: Boolean = false
    ): Boolean {
        if (_uiState.value.status != SessionStatus.CONNECTED) return false
        val action = com.pishi.termbridge.domain.VolumeKeyAction.fromId(
            settings.value.volumeKeyAction
        )
        return when (
            val effect = com.pishi.termbridge.domain.DeviceActionMapping
                .volumeEffect(action, key)
        ) {
            com.pishi.termbridge.domain.VolumeEffect.None -> false
            is com.pishi.termbridge.domain.VolumeEffect.AdjustFontSize -> {
                appSettings?.update {
                    it.copy(
                        terminalFontSp = (it.terminalFontSp + effect.deltaSp)
                            .coerceIn(
                                com.pishi.termbridge.domain.AppSettings.MIN_FONT_SP,
                                com.pishi.termbridge.domain.AppSettings.MAX_FONT_SP
                            )
                    )
                }
                true
            }
            com.pishi.termbridge.domain.VolumeEffect.ArmCtrl -> {
                // A held key must not spin the latch cycle:
                // repeats are consumed but not re-applied.
                if (!isRepeat) pressCtrlLatch()
                true
            }
            is com.pishi.termbridge.domain.VolumeEffect.ScrollPage -> {
                _scrollRequest.value += effect.direction
                true
            }
            is com.pishi.termbridge.domain.VolumeEffect.SendKey -> {
                sendSpecialKey(effect.key)
                true
            }
        }
    }

    fun onShake(): Boolean {
        if (_uiState.value.status != SessionStatus.CONNECTED) return false
        val action = com.pishi.termbridge.domain.ShakeAction.fromId(
            settings.value.shakeAction
        )
        return when (
            val effect = com.pishi.termbridge.domain.DeviceActionMapping.shakeEffect(action)
        ) {
            com.pishi.termbridge.domain.ShakeEffect.None -> false
            // Audit #3 (A-10): a shake is easy to trigger by
            // accident and both effects are destructive (a lost
            // session, a wiped screen) — the shake now only ASKS.
            // The screen shows a confirmation dialog and the
            // effect runs solely from confirmShakeEffect().
            com.pishi.termbridge.domain.ShakeEffect.ClearScreen,
            com.pishi.termbridge.domain.ShakeEffect.Disconnect -> {
                _pendingShakeEffect.value = effect
                true
            }
        }
    }

    /** Audit #3 (A-10): run the shake effect the user confirmed. */
    fun confirmShakeEffect() {
        val effect = _pendingShakeEffect.value
        _pendingShakeEffect.value = null
        when (effect) {
            com.pishi.termbridge.domain.ShakeEffect.Disconnect -> disconnect()
            com.pishi.termbridge.domain.ShakeEffect.ClearScreen ->
                terminalSession?.clearLocal()
            else -> {
                // Nothing pending (or None): nothing to run.
            }
        }
    }

    /** Audit #3 (A-10): drop a pending shake effect unacted. */
    fun dismissShakeEffect() {
        _pendingShakeEffect.value = null
    }

    /**
     * One typed character from the input field. With a latch
     * active the character goes through the encoder (Ctrl-C
     * from a latched Ctrl + 'c'); without one it is exactly
     * the old sendText path.
     */
    fun sendTypedChar(c: Char) {
        val current = keyLatch.current()
        if (!current.ctrl && !current.alt) {
            // Item T-11: a plain typed character composes.
            if (com.pishi.termbridge.domain.ControlChars.isControl(c)) {
                inputTracker.reset()
            } else {
                inputTracker.type(c.toString())
            }
            refreshSuggestions()
            terminalSession?.sendText(c.toString())
            return
        }
        // A latched chord can rewrite the line — stop tracking.
        inputTracker.reset()
        refreshSuggestions()
        val bytes = TerminalKeyEncoder.encodeChar(c, keyLatch.consume())
        publishLatch()
        terminalSession?.sendBytes(bytes)
    }

    override fun onCleared() {
        sequenceJob?.cancel()
        buttonsJob?.cancel()
        // viewModelScope is already cancelled here, so teardown
        // network I/O uses a detached IO scope (best-effort).
        // Review #1 (C-02): same socket-write reason as disconnect().
        val sessionToClose = terminalSession
        val connectionToClose = connection
        terminalSession = null
        connection = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sessionToClose?.close()
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
        _envStatus.value = null
        _connectedShellConfirmed.value = false
        super.onCleared()
    }
}

class SessionViewModelFactory(
    private val repository: HostRepository,
    private val commandRepository: CommandRepository,
    private val environmentRepository: HostEnvironmentRepository,
    private val appSettings: com.pishi.termbridge.settings.AppSettingsStore? = null,
    private val connectionLog: com.pishi.termbridge.settings.ConnectionLogStore? = null,
    private val customThemeRepository: com.pishi.termbridge.settings.CustomThemeRepository? = null,
    private val keychainRepository: com.pishi.termbridge.data.KeychainRepository? = null,
    private val snippetRepository: com.pishi.termbridge.data.SnippetRepository? = null,
    private val transcriptStore: com.pishi.termbridge.settings.TranscriptStorage? = null,
    private val commandHistoryStore: com.pishi.termbridge.settings.CommandHistoryStore? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            return SessionViewModel(
                repository, commandRepository, environmentRepository,
                appSettings, connectionLog, customThemeRepository, keychainRepository,
                snippetRepository, transcriptStore, commandHistoryStore
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
