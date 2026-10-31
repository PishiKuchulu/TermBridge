package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.ControlChars
import com.pishi.termbridge.domain.HostKeyVerification
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
    modifier: Modifier = Modifier
) {
    val hosts by viewModel.hosts.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.settings.collectAsState()
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
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
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
            TerminalView(
                viewModel = viewModel,
                snapshot = snapshot,
                inputEnabled = uiState.status == SessionStatus.CONNECTED,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.medium))
        }
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

private fun ansiColor(code: Int, default: Color): Color = when (code) {
    -1 -> default
    0 -> Color.Black
    1 -> Color(0xFFCC0000)
    2 -> Color(0xFF00AA00)
    3 -> Color(0xFFCCAA00)
    4 -> Color(0xFF0000CC)
    5 -> Color(0xFFCC00CC)
    6 -> Color(0xFF00AAAA)
    7 -> Color.White
    8 -> Color.Gray
    9 -> Color.Red
    10 -> Color.Green
    11 -> Color.Yellow
    12 -> Color.Blue
    13 -> Color.Magenta
    14 -> Color.Cyan
    15 -> Color.White
    else -> default
}

private fun lineToAnnotated(
    line: List<TerminalCell>,
    isCursorLine: Boolean,
    cursorCol: Int
): AnnotatedString {
    val builder = AnnotatedString.Builder()
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
        val fg = if (isCursor) Color.Black else ansiColor(cell.fg, Color.White)
        val bg = if (isCursor) Color.White else ansiColor(cell.bg, Color.Transparent)
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
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Prompt 14 pinned auto-scroll (see terminal/TerminalScroll):
    // new output scrolls only while the user is already at the
    // bottom. Scrolling up unpins the view; the count of lines
    // that arrived since is shown on the jump button below.
    var pinnedToBottom by remember { mutableStateOf(true) }
    var seenCount by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            val atBottom = isAtBottom(lastVisible, total)
            pinnedToBottom = atBottom
            if (atBottom) seenCount = total
        }
    }
    LaunchedEffect(snapshot.lines.size, pinnedToBottom) {
        if (shouldAutoScroll(pinnedToBottom, snapshot.lines.size)) {
            listState.scrollToItem(snapshot.lines.size - 1)
        }
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
                        pinnedToBottom = true
                        seenCount = snapshot.lines.size
                        scope.launch {
                            if (snapshot.lines.isNotEmpty()) {
                                listState.scrollToItem(snapshot.lines.size - 1)
                            }
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            // PTY size from measured area: ~8dp per monospace char,
            // ~20dp per line at 13sp, scaled with the user's font
            // size. Approximation, documented.
            val scale = prefs.terminalFontSp / 13f
            val cols = (maxWidth.value / (8f * scale)).toInt().coerceAtLeast(20)
            val rows = (maxHeight.value / (20f * scale)).toInt().coerceAtLeast(10)
            LaunchedEffect(cols, rows) {
                viewModel.resizeTerminal(cols, rows)
            }
            SelectionContainer {
                LazyColumn(state = listState) {
                    itemsIndexed(snapshot.lines) { lineIndex, line ->
                        Text(
                            text = lineToAnnotated(
                                line,
                                isCursorLine = lineIndex == snapshot.cursorRow,
                                cursorCol = snapshot.cursorCol
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = prefs.terminalFontSp.sp,
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
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
            value = input,
            onValueChange = { new ->
                if (!inputEnabled) {
                    input = ""
                    return@OutlinedTextField
                }
                if (new.length > input.length) {
                    val added = new.substring(input.length)
                    // Security fix 16 (L1): the old code sent
                    // single-line keyboard pastes straight to the
                    // shell, bypassing the paste dialog — only
                    // multi-line pastes went through it. A
                    // multi-character insert is a paste (or an
                    // autocomplete insertion), not a keystroke,
                    // so it goes through the dialog too; only
                    // single characters are sent directly. A lone
                    // newline is also dialog-routed: sent raw it
                    // would execute whatever is on the line.
                    // Tradeoff, stated honestly: an IME
                    // autocomplete insertion of a whole word
                    // also triggers the dialog — the price of
                    // never sending pasted text unreviewed.
                    if (added.length > 1 || added.any { it == '\n' || it == '\r' }) {
                        pendingPaste = added
                    } else {
                        viewModel.sendText(added)
                    }
                } else if (new.length < input.length) {
                    repeat(input.length - new.length) { viewModel.sendBackspace() }
                }
                // The field is a keyboard capture, not a command
                // line: it stays empty after every keystroke.
                input = ""
            },
            label = { Text(stringResource(R.string.terminal_input_hint)) },
            singleLine = true,
            enabled = inputEnabled,
            // Review #1 (C-03): no autocorrect, no IME learning
            // (a terminal capture field must not leak keystrokes
            // into suggestions), and hardware keys are handled
            // here — the old field had no key handling at all, so
            // hardware Backspace/arrows/Tab/Ctrl-letters did nothing.
            // Note, stated honestly: a soft keyboard's Backspace on
            // this always-empty field still may not arrive as a key
            // event (IME-dependent); the ⌫ button above is the
            // guaranteed path.
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password
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
private fun ControlButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = Spacing.minTouch)
            .padding(end = Spacing.small)
    ) {
        Text(text = label)
    }
}
