package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import com.pishi.termbridge.domain.LatchState
import com.pishi.termbridge.domain.SpecialKey
import com.pishi.termbridge.domain.TerminalGeometry
import com.pishi.termbridge.domain.TerminalViewport
import com.pishi.termbridge.domain.UrlDetect
import com.pishi.termbridge.domain.TerminalTheme
import com.pishi.termbridge.domain.TerminalThemes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AppSettings
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.ControlChars
import com.pishi.termbridge.domain.HostKeyVerification
import com.pishi.termbridge.terminal.CursorShape
import com.pishi.termbridge.terminal.TerminalCell
import com.pishi.termbridge.terminal.TerminalSnapshot
import com.pishi.termbridge.terminal.isAtBottom
import com.pishi.termbridge.terminal.newLinesSince
import com.pishi.termbridge.terminal.shouldAutoScroll
import kotlinx.coroutines.launch
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.ParameterDialog
import com.pishi.termbridge.ui.components.RiskyConfirmDialog
import com.pishi.termbridge.ui.components.TrustDialog
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.SessionStatus
import com.pishi.termbridge.ui.viewmodel.SessionViewModel

/**
 * Prompt 06 session screen: connection UI + a real terminal.
 *
 * The terminal is genuinely interactive: typed characters go to
 * the shell as they are typed (the input field is a keyboard
 * capture, not a command line), output streams back through the
 * VT parser, and control keys send raw bytes. Pasted text is
 * NEVER sent automatically: a paste (via the Paste button or via
 * the input field) opens a confirmation dialog, and only an
 * explicit Send tap submits it.
 *
 * Session survival: this screen's ViewModel is activity-scoped,
 * so leaving the screen does not disconnect. Disconnect is an
 * explicit button tap.
 */
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onAddHost: () -> Unit,
    onTerminalExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hosts by viewModel.hosts.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.settings.collectAsState()
    // About 60% of the screen (never below 320dp) instead of a fixed
    // 420dp, so tall phones and tablets get a usable terminal.
    val terminalHeight = maxOf(LocalConfiguration.current.screenHeightDp * 0.6f, 320f).dp
    val hostView = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        hostView.keepScreenOn = prefs.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }
    val terminalSnapshot by viewModel.terminalSnapshot.collectAsState()
    val commandButtons by viewModel.commandButtons.collectAsState()
    val blockedMessage by viewModel.blockedMessage.collectAsState()
    val connectedShell by viewModel.connectedShell.collectAsState()
    val connectedShellConfirmed by viewModel.connectedShellConfirmed.collectAsState()
    val activeSequence by viewModel.activeSequence.collectAsState()
    val envStatus by viewModel.envStatus.collectAsState()
    var passphrase by remember { mutableStateOf("") }

    // Immersive terminal (item Q-01): expanded mode fills the
    // whole content area with the terminal and hides the app
    // chrome (reported upward) and the system bars (here).
    var terminalExpanded by rememberSaveable { mutableStateOf(false) }
    // Item T-11: shell-history import consent dialog visibility.
    var showHistoryImportConsent by rememberSaveable { mutableStateOf(false) }
    val snapshotForExpand = terminalSnapshot
    val effectiveExpanded = terminalExpanded && snapshotForExpand != null
    LaunchedEffect(effectiveExpanded) { onTerminalExpandedChange(effectiveExpanded) }
    DisposableEffect(Unit) { onDispose { onTerminalExpandedChange(false) } }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(effectiveExpanded) {
        val controller = activity?.window?.let {
            WindowInsetsControllerCompat(it, hostView)
        }
        if (effectiveExpanded) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val pageScroll = rememberScrollState()

    val pending = uiState.pendingTrust
    if (pending != null) {
        TrustDialog(
            pending = pending,
            onTrust = { viewModel.answerTrust(true) },
            onReject = { viewModel.answerTrust(false) }
        )
    }

    // Prompt 08 dialogs: parameter + preview first, risky
    // confirmation second. Neither dialog runs anything on open.
    val actionButton by viewModel.actionButton.collectAsState()
    val action = actionButton
    if (action != null) {
        ParameterDialog(
            button = action,
            onDismiss = { viewModel.clearActionButton() },
            onSubmit = { prepared -> viewModel.actOnPrepared(prepared, false) },
            // Security fix 16 (H1): the dialog quotes and
            // classifies for this host's shell, never POSIX
            // by assumption. Prompt 18 (audit-17 R1): an
            // unconfirmed shell warns on parameterised buttons.
            shell = connectedShell,
            shellConfirmed = connectedShellConfirmed
        )
    }
    val pendingRisky by viewModel.pendingRisky.collectAsState()
    val risky = pendingRisky
    if (risky != null) {
        RiskyConfirmDialog(
            prepared = risky,
            onDismiss = { viewModel.clearPendingRisky() },
            onConfirm = { viewModel.actOnPrepared(risky, true) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (effectiveExpanded) {
                    Modifier
                } else {
                    Modifier.verticalScroll(pageScroll).padding(Spacing.medium)
                }
            )
    ) {
        if (hosts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.session_no_hosts_title),
                body = stringResource(R.string.session_no_hosts_body),
                actionLabel = stringResource(R.string.action_add_host),
                onAction = onAddHost
            )
            return@Column
        }
        // Terminal first when it exists (connected or just closed,
        // so the user can read the last output before reconnecting).
        val snapshot = terminalSnapshot
        if (snapshot != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { terminalExpanded = !terminalExpanded }) {
                    Icon(
                        imageVector = if (effectiveExpanded) {
                            Icons.Filled.FullscreenExit
                        } else {
                            Icons.Filled.Fullscreen
                        },
                        contentDescription = stringResource(
                            if (effectiveExpanded) {
                                R.string.terminal_exit_fullscreen
                            } else {
                                R.string.terminal_enter_fullscreen
                            }
                        )
                    )
                }
            }
            TerminalView(
                viewModel = viewModel,
                snapshot = snapshot,
                inputEnabled = uiState.status == SessionStatus.CONNECTED,
                modifier = if (effectiveExpanded) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().height(terminalHeight)
                }
            )
            Spacer(modifier = Modifier.height(Spacing.medium))
        }
        if (!effectiveExpanded) {
        when (uiState.status) {
            SessionStatus.CONNECTED -> {
                Text(
                    stringResource(
                        R.string.session_connected_to,
                        uiState.connectedHost?.name ?: ""
                    )
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                PrimaryAction(
                    label = stringResource(R.string.session_disconnect),
                    onClick = { viewModel.disconnect() }
                )
                // Item T-16: OS detection over the exec channel.
                val detectedOs by viewModel.detectedOs.collectAsState()
                if (detectedOs != null) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(
                        text = stringResource(
                            R.string.session_detected_os, detectedOs!!
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))
                SecondaryAction(
                    label = stringResource(R.string.session_detect_os),
                    onClick = { viewModel.detectOs() }
                )
                // Item T-11: shell-history import — an explicit,
                // consented action over a separate exec channel.
                if (prefs.autocompleteEnabled) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.history_import_action),
                        onClick = { showHistoryImportConsent = true }
                    )
                    val importMessage by viewModel.historyImportMessage.collectAsState()
                    if (importMessage != null) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = importMessage!!,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                // Prompt 09: environment outcome, names and path
                // only — a value never appears here. The section
                // is hidden entirely when nothing is configured,
                // so an empty feature shows no decorative UI.
                val env = envStatus
                if (env != null &&
                    (env.configuredNames.isNotEmpty() || env.workingDirectory != null)
                ) {
                    Spacer(modifier = Modifier.height(Spacing.large))
                    Text(
                        text = stringResource(R.string.env_session_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (env.workingDirectory != null) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource(
                                R.string.env_session_working_dir,
                                env.workingDirectory
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (env.configuredNames.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        if (env.appliedNames.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.env_session_applied,
                                    env.appliedNames.joinToString(", ")
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (env.rejectedNames.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.env_session_rejected,
                                    env.rejectedNames.joinToString(", ")
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (env.fallbackNames.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(
                                text = stringResource(
                                    R.string.env_session_fallback,
                                    env.fallbackNames.joinToString(", ")
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource(R.string.env_session_server_note),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                // Prompt 08: buttons for the connected host, wired
                // to this live terminal. Insert is a secondary
                // (outlined) action, Run is primary — visually and
                // semantically distinct, as the pack requires.
                Spacer(modifier = Modifier.height(Spacing.large))
                Text(
                    text = stringResource(R.string.commands_session_buttons_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(R.string.commands_session_buttons_note),
                    style = MaterialTheme.typography.bodySmall
                )
                val sequence = activeSequence
                if (sequence != null) {
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    if (sequence.stopped) {
                        Text(
                            text = stringResource(
                                R.string.commands_sequence_stopped,
                                sequence.progress.stepIndex,
                                sequence.progress.totalSteps
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        SecondaryAction(
                            label = stringResource(R.string.action_cancel),
                            onClick = { viewModel.clearStoppedSequence() }
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.commands_sequence_progress,
                                sequence.progress.stepIndex,
                                sequence.progress.totalSteps
                            )
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        SecondaryAction(
                            label = stringResource(R.string.commands_action_stop),
                            onClick = { viewModel.stopSequence() }
                        )
                    }
                }
                // Remediation phase 1: a refused submission
                // (dialog bypass or stale prepared command)
                // explains itself here, in the buttons area.
                blockedMessage?.let { block ->
                    Text(
                        text = stringResource(
                            when (block) {
                                CommandTemplate.SubmissionBlock.STALE_SHELL ->
                                    R.string.shell_blocked_stale
                                CommandTemplate.SubmissionBlock.SHELL_UNCONFIRMED ->
                                    R.string.shell_blocked_unconfirmed
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                }
                if (commandButtons.isEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(
                        text = stringResource(R.string.commands_no_buttons_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    commandButtons.forEach { button ->
                        Spacer(modifier = Modifier.height(Spacing.medium))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = button.label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (button.category.isNotBlank()) {
                                    Text(
                                        text = button.category,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            when (button.mode) {
                                CommandMode.INSERT_ONLY -> SecondaryAction(
                                    label = stringResource(R.string.commands_action_insert),
                                    onClick = { viewModel.selectButtonForAction(button.id) }
                                )
                                CommandMode.EXECUTE_ONE -> PrimaryAction(
                                    label = stringResource(R.string.commands_action_run),
                                    onClick = { viewModel.selectButtonForAction(button.id) }
                                )
                                CommandMode.SEQUENCE -> PrimaryAction(
                                    label = stringResource(R.string.commands_action_run_sequence),
                                    onClick = { viewModel.selectButtonForAction(button.id) }
                                )
                            }
                        }
                    }
                }
            }
            SessionStatus.CONNECTING -> {
                Text(text = stringResource(R.string.session_connecting))
                Spacer(modifier = Modifier.height(Spacing.medium))
                SecondaryAction(
                    label = stringResource(R.string.action_cancel),
                    onClick = { viewModel.cancel() }
                )
            }
            else -> {
                if (snapshot == null) {
                    Text(text = stringResource(R.string.session_select_host))
                    Spacer(modifier = Modifier.height(Spacing.small))
                    hosts.forEach { host ->
                        // Prompt 14: the whole row selects the
                        // host (48dp minimum), not just the small
                        // radio dot — the radio keeps its own
                        // onClick for accessibility semantics,
                        // and selecting twice is idempotent.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.minTouch)
                                .clickable { viewModel.selectHost(host.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.selectedHostId == host.id,
                                onClick = { viewModel.selectHost(host.id) }
                            )
                            Column {
                                Text(text = host.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "${host.username}@${host.hostname}:${host.port}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    val selected = hosts.firstOrNull { it.id == uiState.selectedHostId }
                    if (selected?.authType == AuthType.PRIVATE_KEY) {
                        Spacer(modifier = Modifier.height(Spacing.medium))
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text(stringResource(R.string.session_passphrase_label)) },
                            placeholder = { Text(stringResource(R.string.session_passphrase_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.large))
                }
                PrimaryAction(
                    label = stringResource(R.string.session_connect),
                    onClick = {
                        viewModel.connect(passphrase)
                        passphrase = ""
                    },
                    enabled = uiState.selectedHostId != null ||
                        uiState.connectedHost != null
                )
                uiState.errorMessage?.let {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        }
    }
    // Item T-11: the import reads remote history files, so it
    // runs only after this explicit consent.
    if (showHistoryImportConsent) {
        AlertDialog(
            onDismissRequest = { showHistoryImportConsent = false },
            title = { Text(stringResource(R.string.history_import_title)) },
            text = { Text(stringResource(R.string.history_import_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHistoryImportConsent = false
                        viewModel.importShellHistory()
                    }
                ) { Text(stringResource(R.string.history_import_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHistoryImportConsent = false }
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Unwrap the hosting Activity from a (possibly wrapped) context. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun paletteColor(theme: TerminalTheme, code: Int, default: Color): Color =
    if (code in 0..15) Color(theme.ansi[code]) else default

private fun lineToAnnotated(
    line: List<TerminalCell>,
    isCursorLine: Boolean,
    cursorCol: Int,
    theme: TerminalTheme
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val defaultFg = Color(theme.foreground)
    var index = 0
    while (index < line.size) {
        val cell = line[index]
        var end = index + 1
        while (end < line.size) {
            val next = line[end]
            if (next.fg != cell.fg || next.bg != cell.bg || next.bold != cell.bold) break
            end++
        }
        val isCursor = isCursorLine && cursorCol in index until end
        val fg = if (isCursor) Color(theme.background) else paletteColor(theme, cell.fg, defaultFg)
        val bg = if (isCursor) Color(theme.cursor) else paletteColor(theme, cell.bg, Color.Transparent)
        builder.pushStyle(
            SpanStyle(
                color = fg,
                background = bg,
                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal
            )
        )
        for (i in index until end) {
            builder.append(line[i].char)
        }
        builder.pop()
        index = end
    }
    return builder.toAnnotatedString()
}

@Composable
private fun TerminalView(
    viewModel: SessionViewModel,
    snapshot: TerminalSnapshot,
    inputEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val prefs by viewModel.settings.collectAsState()
    val latch by viewModel.latchState.collectAsState()
    val customThemes by viewModel.customThemes.collectAsState()
    var showFunctionKeys by rememberSaveable { mutableStateOf(false) }
    val theme = TerminalThemes.resolve(prefs.terminalTheme, customThemes)
    val currentFont by rememberUpdatedState(prefs.terminalFontSp)
    val haptic = LocalHapticFeedback.current
    var lastBell by remember { mutableStateOf(snapshot.bellCount) }
    // Bell sound (item T-10): one shared generator for the
    // view's lifetime, released with it.
    val toneGenerator = remember {
        try {
            android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION, 50
            )
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(Unit) { onDispose { toneGenerator?.release() } }
    // Shake action (item T-19): the accelerometer listener is
    // registered exactly while this view lives and the setting
    // is not Disabled; thresholds are the pure ShakeDetector's.
    // A device without an accelerometer simply never fires.
    val shakeContext = LocalContext.current
    DisposableEffect(prefs.shakeAction) {
        val sensorManager = shakeContext.getSystemService(
            android.content.Context.SENSOR_SERVICE
        ) as? android.hardware.SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(
            android.hardware.Sensor.TYPE_ACCELEROMETER
        )
        if (prefs.shakeAction == com.pishi.termbridge.domain.ShakeAction.DISABLED.id ||
            sensorManager == null || accelerometer == null
        ) {
            onDispose { }
        } else {
            val detector = com.pishi.termbridge.domain.ShakeDetector()
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    val v = event.values
                    if (detector.onSample(v[0], v[1], v[2], event.timestamp / 1_000_000L)) {
                        viewModel.onShake()
                    }
                }

                override fun onAccuracyChanged(
                    sensor: android.hardware.Sensor?,
                    accuracy: Int
                ) {
                }
            }
            sensorManager.registerListener(
                listener,
                accelerometer,
                android.hardware.SensorManager.SENSOR_DELAY_UI
            )
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }
    var bellFlashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.bellCount) {
        val rang = snapshot.bellCount > lastBell
        if (rang && prefs.bellHaptic) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        if (rang && prefs.bellSound) {
            toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        }
        if (rang && prefs.bellFlash) {
            bellFlashVisible = true
        }
        lastBell = snapshot.bellCount
    }
    LaunchedEffect(bellFlashVisible) {
        if (bellFlashVisible) {
            kotlinx.coroutines.delay(130)
            bellFlashVisible = false
        }
    }
    // Cursor appearance (item T-12): the remote app's DECSCUSR
    // wins once it sets one; until then the user's setting is
    // the default. ?25 can hide the cursor entirely.
    val effectiveCursorShape = if (snapshot.cursorStyleSetByApp) {
        snapshot.cursorShape
    } else {
        when (prefs.cursorStyle) {
            AppSettings.CURSOR_STYLE_UNDERLINE -> CursorShape.UNDERLINE
            AppSettings.CURSOR_STYLE_BAR -> CursorShape.BAR
            else -> CursorShape.BLOCK
        }
    }
    val effectiveCursorBlink = if (snapshot.cursorStyleSetByApp) {
        snapshot.cursorBlinking
    } else {
        prefs.cursorBlink
    }
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(effectiveCursorBlink) {
        blinkOn = true
        while (effectiveCursorBlink) {
            kotlinx.coroutines.delay(530)
            blinkOn = !blinkOn
        }
    }
    val cursorShown = snapshot.cursorVisible && (!effectiveCursorBlink || blinkOn)
    // Item T-13: the capture field holds a TextFieldValue so
    // an IME composition (CJK candidates, RTL shaping, gesture
    // typing, voice dictation) can exist as a draft before it
    // commits; the old String state flattened every
    // intermediate composition change into a keystroke.
    var inputValue by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var hadComposition by remember { mutableStateOf(false) }
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    // Canvas renderer state (item Q-03). The scroll model keeps
    // the prompt-14 semantics (see terminal/TerminalScroll):
    // pinned to the live edge at offset 0; scrolling up unpins
    // and new output must not move what the user is reading —
    // the offset grows by exactly the lines that arrived.
    var offsetFromBottom by remember { mutableStateOf(0) }
    var seenCount by remember { mutableStateOf(0) }
    var prevLineCount by remember { mutableStateOf(snapshot.lines.size) }
    var selection by remember { mutableStateOf<TerminalViewport.Selection?>(null) }
    val pinnedToBottom = offsetFromBottom == 0
    LaunchedEffect(snapshot.lines.size) {
        val total = snapshot.lines.size
        val arrived = total - prevLineCount
        if (arrived > 0 && offsetFromBottom > 0) {
            offsetFromBottom += arrived
        }
        if (offsetFromBottom == 0) seenCount = total
        prevLineCount = total
    }
    val newLines = newLinesSince(seenCount, snapshot.lines.size)

    val paste = pendingPaste
    if (paste != null) {
        AlertDialog(
            onDismissRequest = { pendingPaste = null },
            title = { Text(stringResource(R.string.paste_dialog_title)) },
            text = { Text(stringResource(R.string.paste_dialog_body, ControlChars.visualize(paste))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.sendText(paste)
                        pendingPaste = null
                    }
                ) { Text(stringResource(R.string.action_send)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPaste = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(modifier = modifier) {
        if (!pinnedToBottom && newLines > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction(
                    label = stringResource(R.string.terminal_jump_to_latest, newLines),
                    onClick = {
                        offsetFromBottom = 0
                        seenCount = snapshot.lines.size
                    }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
        }
        val activeSelection = selection
        if (activeSelection != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction(
                    label = stringResource(R.string.action_copy),
                    onClick = {
                        val plainLines = snapshot.lines.map { line ->
                            line.joinToString("") { cell -> cell.char.toString() }
                        }
                        clipboardManager.setText(
                            AnnotatedString(
                                TerminalViewport.extractText(plainLines, activeSelection)
                            )
                        )
                        selection = null
                    }
                )
                SecondaryAction(
                    label = stringResource(R.string.action_cancel),
                    onClick = { selection = null }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(theme.background))
                .pointerInput(Unit) {
                    // Two-finger pinch changes the font size; a single
                    // finger is left alone so scrolling and text
                    // selection still work.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var accumulated = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2) {
                                accumulated *= event.calculateZoom()
                                if (accumulated > 1.15f) {
                                    viewModel.setFontSize(currentFont + 1)
                                    accumulated = 1f
                                } else if (accumulated < 0.87f) {
                                    viewModel.setFontSize(currentFont - 1)
                                    accumulated = 1f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            // PTY size from the REAL measured cell (item Q-02):
            // the same monospace style the lines are drawn with,
            // laid out by the text measurer — the remote side now
            // wraps exactly where this screen does. (Before this
            // the size came from a documented 8dp/20dp guess.)
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val cellStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = prefs.terminalFontSp.sp
            )
            val cellWidthPx = remember(textMeasurer, cellStyle) {
                textMeasurer.measure("MMMMMMMMMM", cellStyle).size.width / 10f
            }
            val cellHeightPx = remember(textMeasurer, cellStyle) {
                textMeasurer.measure("M", cellStyle).size.height.toFloat()
            }
            val grid = TerminalGeometry.gridSize(
                availableWidthPx = with(density) { maxWidth.toPx() },
                availableHeightPx = with(density) { maxHeight.toPx() },
                cellWidthPx = cellWidthPx,
                cellHeightPx = cellHeightPx
            )
            LaunchedEffect(grid.cols, grid.rows) {
                viewModel.resizeTerminal(grid.cols, grid.rows)
            }
            // Volume-key page scroll (item T-19): the view model
            // accumulates page requests; apply the delta since
            // the last one in whole visible pages.
            val scrollRequest by viewModel.scrollRequest.collectAsState()
            var appliedScrollRequest by remember { mutableStateOf(0) }
            LaunchedEffect(scrollRequest) {
                val delta = scrollRequest - appliedScrollRequest
                appliedScrollRequest = scrollRequest
                if (delta != 0) {
                    val maxOffset = (snapshot.lines.size - grid.rows).coerceAtLeast(0)
                    offsetFromBottom = (offsetFromBottom + delta * grid.rows)
                        .coerceIn(0, maxOffset)
                    selection = null
                }
            }
            // Canvas renderer (item Q-03): only the visible
            // window is drawn, and a row's text layout is
            // recomputed only when its content actually changes
            // (content-keyed LRU) — that pair is what keeps a
            // fast, huge output stream from costing layout work
            // per frame. All geometry decisions (window, hit
            // cells, selection text, URL spans) are the pure
            // domain functions in TerminalViewport/UrlDetect.
            val windowStart = TerminalViewport.windowStart(
                totalLines = snapshot.lines.size,
                visibleRows = grid.rows,
                offsetFromBottom = offsetFromBottom
            )
            val layoutCache = remember {
                android.util.LruCache<String, TextLayoutResult>(512)
            }
            var dragAccumPx by remember { mutableStateOf(0f) }
            var selectionAnchor by remember {
                mutableStateOf<TerminalViewport.CellPos?>(null)
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragAccumPx += dragAmount.y
                            // Scroll sensitivity (item T-12):
                            // 2x covers a line in half a cell of drag.
                            val stepPx = cellHeightPx / prefs.scrollSensitivity
                            val lineDelta = (dragAccumPx / stepPx).toInt()
                            if (lineDelta != 0) {
                                dragAccumPx -= lineDelta * stepPx
                                val maxOffset =
                                    (snapshot.lines.size - grid.rows).coerceAtLeast(0)
                                offsetFromBottom =
                                    (offsetFromBottom + lineDelta).coerceIn(0, maxOffset)
                                if (offsetFromBottom == 0) {
                                    seenCount = snapshot.lines.size
                                }
                                selection = null
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                selectionAnchor = TerminalViewport.cellAt(
                                    offset.x, offset.y,
                                    cellWidthPx, cellHeightPx,
                                    windowStart, snapshot.lines.size, grid.cols
                                )
                                selection = selectionAnchor?.let {
                                    TerminalViewport.Selection(it, it)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val anchor = selectionAnchor
                                val current = TerminalViewport.cellAt(
                                    change.position.x, change.position.y,
                                    cellWidthPx, cellHeightPx,
                                    windowStart, snapshot.lines.size, grid.cols
                                )
                                if (anchor != null && current != null) {
                                    selection = TerminalViewport.Selection(anchor, current)
                                }
                            },
                            onDragEnd = { selectionAnchor = null },
                            onDragCancel = { selectionAnchor = null }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (selection != null) {
                                selection = null
                            } else {
                                val cell = TerminalViewport.cellAt(
                                    offset.x, offset.y,
                                    cellWidthPx, cellHeightPx,
                                    windowStart, snapshot.lines.size, grid.cols
                                )
                                if (cell != null && cell.row in snapshot.lines.indices) {
                                    val plain = snapshot.lines[cell.row]
                                        .joinToString("") { c -> c.char.toString() }
                                    val hit = UrlDetect.findUrls(plain)
                                        .firstOrNull { cell.col in it }
                                    if (hit != null) {
                                        uriHandler.openUri(plain.substring(hit))
                                    }
                                }
                            }
                        }
                    }
            ) {
                val visibleEnd = minOf(snapshot.lines.size, windowStart + grid.rows)
                val drawStyle = cellStyle.copy(color = Color(theme.foreground))
                val selectionNow = selection
                for (row in windowStart until visibleEnd) {
                    val y = (row - windowStart) * cellHeightPx
                    if (selectionNow != null) {
                        val (first, last) = selectionNow.normalized()
                        if (row in first.row..last.row) {
                            val fromCol = if (row == first.row) first.col else 0
                            val toCol = if (row == last.row) last.col else grid.cols - 1
                            drawRect(
                                color = Color(theme.cursor).copy(alpha = 0.35f),
                                topLeft = Offset(fromCol * cellWidthPx, y),
                                size = Size((toCol - fromCol + 1) * cellWidthPx, cellHeightPx)
                            )
                        }
                    }
                    val line = snapshot.lines[row]
                    // Block cursor = inverted cell (only while
                    // shown); underline/bar are drawn as shapes
                    // below, so their rows keep normal colours.
                    val invertCursor = cursorShown &&
                        effectiveCursorShape == CursorShape.BLOCK &&
                        row == snapshot.cursorRow
                    val annotated = lineToAnnotated(
                        line,
                        isCursorLine = invertCursor,
                        cursorCol = snapshot.cursorCol,
                        theme = theme
                    )
                    val key = annotated.text + "|" + annotated.spanStyles.hashCode() +
                        "|" + invertCursor + "|" + snapshot.cursorCol +
                        "|" + prefs.terminalFontSp + "|" + theme.id
                    val layout = layoutCache.get(key) ?: textMeasurer.measure(
                        annotated, drawStyle
                    ).also { layoutCache.put(key, it) }
                    drawText(layout, topLeft = Offset(0f, y))
                    if (cursorShown && row == snapshot.cursorRow) {
                        val cursorX = snapshot.cursorCol * cellWidthPx
                        when (effectiveCursorShape) {
                            CursorShape.UNDERLINE -> drawLine(
                                color = Color(theme.cursor),
                                start = Offset(cursorX, y + cellHeightPx - 2.5f),
                                end = Offset(cursorX + cellWidthPx, y + cellHeightPx - 2.5f),
                                strokeWidth = 2.5f
                            )
                            CursorShape.BAR -> drawRect(
                                color = Color(theme.cursor),
                                topLeft = Offset(cursorX, y),
                                size = Size(2.5f, cellHeightPx)
                            )
                            CursorShape.BLOCK -> Unit // Inverted above.
                        }
                    }
                    val plain = line.joinToString("") { c -> c.char.toString() }
                    for (span in UrlDetect.findUrls(plain)) {
                        drawLine(
                            color = Color(theme.foreground).copy(alpha = 0.6f),
                            start = Offset(span.first * cellWidthPx, y + cellHeightPx - 2f),
                            end = Offset(
                                (span.last + 1) * cellWidthPx,
                                y + cellHeightPx - 2f
                            ),
                            strokeWidth = 1.5f
                        )
                    }
                }
            }
            // Visual bell (item T-10): a brief flash above the grid.
            if (bellFlashVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(theme.foreground).copy(alpha = 0.15f))
                )
            }
        }
        // Autocomplete suggestions (item T-11): remembered
        // commands matching the line being typed. Tapping one
        // types only the remainder.
        val suggestions by viewModel.suggestions.collectAsState()
        if (prefs.autocompleteEnabled && suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (suggestion in suggestions) {
                    OutlinedButton(
                        onClick = { viewModel.applySuggestion(suggestion) }
                    ) {
                        Text(
                            text = suggestion,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.small))
                }
            }
        }
        // Extra-keys bar (item T-09): sticky/lock modifiers and
        // the keys a phone keyboard does not have. Hidden as a
        // whole by the settings toggle; the classic row below
        // is untouched either way.
        if (prefs.showExtraKeysBar) {
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    label = stringResource(R.string.terminal_key_ctrl),
                    toggled = latch.ctrl != LatchState.OFF,
                    locked = latch.ctrl == LatchState.LOCKED,
                    onClick = { viewModel.pressCtrlLatch() }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_alt),
                    toggled = latch.alt != LatchState.OFF,
                    locked = latch.alt == LatchState.LOCKED,
                    onClick = { viewModel.pressAltLatch() }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_home),
                    onClick = { viewModel.sendSpecialKey(SpecialKey.HOME) }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_end),
                    onClick = { viewModel.sendSpecialKey(SpecialKey.END) }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_pgup),
                    onClick = { viewModel.sendSpecialKey(SpecialKey.PAGE_UP) }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_pgdn),
                    onClick = { viewModel.sendSpecialKey(SpecialKey.PAGE_DOWN) }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_ins),
                    onClick = { viewModel.sendSpecialKey(SpecialKey.INSERT) }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_del),
                    onClick = { viewModel.sendSpecialKey(SpecialKey.DELETE) }
                )
                ControlButton(
                    label = stringResource(R.string.terminal_key_fn),
                    toggled = showFunctionKeys,
                    onClick = { showFunctionKeys = !showFunctionKeys }
                )
            }
            if (showFunctionKeys) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val fKeys = listOf(
                        SpecialKey.F1, SpecialKey.F2, SpecialKey.F3, SpecialKey.F4,
                        SpecialKey.F5, SpecialKey.F6, SpecialKey.F7, SpecialKey.F8,
                        SpecialKey.F9, SpecialKey.F10, SpecialKey.F11, SpecialKey.F12
                    )
                    fKeys.forEachIndexed { index, key ->
                        ControlButton(
                            label = "F${index + 1}",
                            onClick = { viewModel.sendSpecialKey(key) }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.small))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(
                label = stringResource(R.string.terminal_ctrl_c),
                onClick = { viewModel.sendCtrlC() }
            )
            ControlButton(
                label = stringResource(R.string.terminal_ctrl_d),
                onClick = { viewModel.sendCtrlD() }
            )
            ControlButton(
                label = stringResource(R.string.terminal_key_tab),
                onClick = { viewModel.sendTab() }
            )
            ControlButton(
                label = stringResource(R.string.terminal_key_esc),
                onClick = { viewModel.sendEscape() }
            )
            // Review #1 (C-03): the guaranteed Backspace path.
            // Soft keyboards do not reliably deliver a Backspace
            // key event (or a value change) for an empty capture
            // field, so the button — not the field — is what a
            // user can always rely on.
            ControlButton(
                label = "⌫",
                onClick = { viewModel.sendBackspace() }
            )
            ControlButton(label = "↑", onClick = { viewModel.sendArrowUp() })
            ControlButton(label = "↓", onClick = { viewModel.sendArrowDown() })
            ControlButton(label = "←", onClick = { viewModel.sendArrowLeft() })
            ControlButton(label = "→", onClick = { viewModel.sendArrowRight() })
            ControlButton(
                label = stringResource(R.string.action_paste),
                onClick = {
                    clipboardManager.getText()?.text?.let { pendingPaste = it }
                }
            )
            ControlButton(
                label = stringResource(R.string.action_keyboard),
                onClick = { focusRequester.requestFocus() }
            )
        }
        Spacer(modifier = Modifier.height(Spacing.small))
        OutlinedTextField(
            value = inputValue,
            onValueChange = { new ->
                if (!inputEnabled) {
                    inputValue = androidx.compose.ui.text.input.TextFieldValue("")
                    hadComposition = false
                    return@OutlinedTextField
                }
                // Item T-13: the routing rules are pure and
                // unit-tested (domain/ImeRouting): a composition
                // draft is held, its commit is typing, and only
                // non-composition multi-char inserts reach the
                // security-fix-16 paste gate.
                when (
                    val route = com.pishi.termbridge.domain.ImeRouting.route(
                        oldText = inputValue.text,
                        newText = new.text,
                        wasComposing = hadComposition,
                        isComposing = new.composition != null
                    )
                ) {
                    com.pishi.termbridge.domain.ImeRoute.HoldDraft -> {
                        hadComposition = true
                        inputValue = new
                        return@OutlinedTextField
                    }
                    is com.pishi.termbridge.domain.ImeRoute.TypeText -> {
                        if (route.text.length == 1) {
                            viewModel.sendTypedChar(route.text[0])
                        } else {
                            viewModel.sendText(route.text)
                        }
                    }
                    is com.pishi.termbridge.domain.ImeRoute.TypeChar ->
                        viewModel.sendTypedChar(route.char)
                    is com.pishi.termbridge.domain.ImeRoute.PasteCandidate ->
                        pendingPaste = route.text
                    is com.pishi.termbridge.domain.ImeRoute.Backspaces ->
                        repeat(route.count) { viewModel.sendBackspace() }
                    com.pishi.termbridge.domain.ImeRoute.Nothing -> {}
                }
                hadComposition = false
                // The field is a keyboard capture, not a command
                // line: empty after every commit; a composition
                // draft is the only text it ever holds.
                inputValue = androidx.compose.ui.text.input.TextFieldValue("")
            },
            label = { Text(stringResource(R.string.terminal_input_hint)) },
            singleLine = true,
            enabled = inputEnabled,
            // Review #1 (C-03): no autocorrect, and hardware
            // keys are handled here — the old field had no key
            // handling at all, so hardware
            // Backspace/arrows/Tab/Ctrl-letters did nothing.
            // (Item T-13 amended the field type to Text for
            // composition/voice; the field itself still retains
            // no text after a commit.)
            // Note, stated honestly: a soft keyboard's Backspace on
            // this always-empty field still may not arrive as a key
            // event (IME-dependent); the ⌫ button above is the
            // guaranteed path.
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrectEnabled = false,
                // Item T-13: a Text field, not Password — voice
                // input and IME composition are blocked in
                // password fields. The field still retains
                // nothing after a commit; Compose exposes no
                // "no learning" flag for text fields, so
                // keyboard-side learning is the user's own
                // keyboard's behaviour.
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(onSend = { viewModel.sendEnter() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (!inputEnabled || event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.Backspace -> {
                            viewModel.sendBackspace(); true
                        }
                        Key.Enter -> {
                            viewModel.sendEnter(); true
                        }
                        Key.Tab -> {
                            viewModel.sendTab(); true
                        }
                        Key.DirectionUp -> {
                            viewModel.sendArrowUp(); true
                        }
                        Key.DirectionDown -> {
                            viewModel.sendArrowDown(); true
                        }
                        Key.DirectionLeft -> {
                            viewModel.sendArrowLeft(); true
                        }
                        Key.DirectionRight -> {
                            viewModel.sendArrowRight(); true
                        }
                        Key.Escape -> {
                            viewModel.sendEscape(); true
                        }
                        Key.C -> {
                            if (event.isCtrlPressed) {
                                viewModel.sendCtrlC(); true
                            } else {
                                false
                            }
                        }
                        Key.D -> {
                            if (event.isCtrlPressed) {
                                viewModel.sendCtrlD(); true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
        )
    }
}

@Composable
private fun ControlButton(
    label: String,
    toggled: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = if (toggled) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier
            .heightIn(min = Spacing.minTouch)
            .padding(end = Spacing.small)
    ) {
        Text(
            text = label,
            textDecoration = if (locked) TextDecoration.Underline else TextDecoration.None
        )
    }
}
