package com.pishi.termbridge.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.RfbKeys
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.DesktopStatus
import com.pishi.termbridge.ui.viewmodel.RemoteDesktopViewModel
import kotlin.math.min

/**
 * Remote desktop over VNC (RFB). The protocol core was written and
 * unit-tested earlier; this screen is its first user-facing session
 * UI, and CI now exercises the core against a real VNC server.
 *
 * RDP is still not available (the feasibility gate failed, see
 * docs/RDP_NOTES.md) and the screen says so instead of hiding it.
 * VNC traffic is not encrypted; the warning is always visible on the
 * connect form.
 */
@Composable
fun RemoteDesktopScreen(
    viewModel: RemoteDesktopViewModel,
    initialHost: String = "",
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    // Audit #3 (A-16, warning half): Back while a VNC session
    // is connected asks first, instead of leaving the live
    // session screen without a word. Only the dialog's Leave
    // lets the press through (the handler disables itself,
    // then the press is re-dispatched). The ViewModel's scope
    // and its onCleared teardown are unchanged.
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var leaveConfirmed by remember { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    BackHandler(enabled = state.status == DesktopStatus.CONNECTED && !leaveConfirmed) {
        showLeaveConfirm = true
    }
    LaunchedEffect(leaveConfirmed) {
        if (leaveConfirmed) backDispatcher?.onBackPressed()
    }
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.desktop_leave_title)) },
            text = { Text(stringResource(R.string.desktop_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        leaveConfirmed = true
                    }
                ) { Text(stringResource(R.string.action_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (state.status == DesktopStatus.CONNECTED) {
        DesktopSession(viewModel, modifier)
    } else {
        ConnectForm(
            viewModel,
            state.status == DesktopStatus.CONNECTING,
            state.error,
            initialHost,
            state.lastHost,
            state.lastPort,
            modifier
        )
    }
}

@Composable
private fun ConnectForm(
    viewModel: RemoteDesktopViewModel,
    connecting: Boolean,
    error: String?,
    initialHost: String,
    lastHost: String,
    lastPort: Int,
    modifier: Modifier
) {
    // Item F-03b: reconnect refills the last endpoint attempted.
    var host by remember(initialHost, lastHost) {
        mutableStateOf(lastHost.ifBlank { initialHost })
    }
    var portText by remember(lastPort) { mutableStateOf(lastPort.toString()) }
    var password by remember { mutableStateOf("") }
    var noPassword by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    // Item F-03b: through-SSH + profiles state.
    var viaSsh by remember { mutableStateOf(false) }
    var sshHostId by remember { mutableStateOf<Long?>(null) }
    var sshPassphrase by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    val profiles by viewModel.profiles.collectAsState()
    val sshHosts by viewModel.sshHosts.collectAsState()
    val invalidHost = stringResource(R.string.desktop_invalid_host)
    val invalidPort = stringResource(R.string.desktop_invalid_port)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text(stringResource(R.string.desktop_host_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.desktop_port_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        if (!noPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.desktop_password_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = noPassword, onCheckedChange = { noPassword = it })
            Text(
                text = stringResource(R.string.desktop_no_password),
                modifier = Modifier.padding(start = Spacing.small)
            )
        }
        val shown = localError ?: error
        if (shown != null) {
            Text(text = shown, color = MaterialTheme.colorScheme.error)
        }
        // Item F-03b: optional SSH tunnel for this connection.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = viaSsh, onCheckedChange = { viaSsh = it })
            Text(
                text = stringResource(R.string.desktop_via_ssh),
                modifier = Modifier.padding(start = Spacing.small)
            )
        }
        if (viaSsh) {
            Text(
                text = stringResource(R.string.desktop_via_ssh_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (sshHosts.isEmpty()) {
                Text(
                    text = stringResource(R.string.desktop_via_ssh_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            sshHosts.forEach { saved ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = sshHostId == saved.id,
                        onClick = { sshHostId = saved.id }
                    )
                    Text(text = "${saved.name} (${saved.username}@${saved.hostname})")
                }
            }
            val selected = sshHosts.firstOrNull { it.id == sshHostId }
            if (selected != null &&
                selected.authType == com.pishi.termbridge.domain.AuthType.PRIVATE_KEY
            ) {
                OutlinedTextField(
                    value = sshPassphrase,
                    onValueChange = { sshPassphrase = it },
                    label = { Text(stringResource(R.string.desktop_ssh_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        PrimaryAction(
            label = stringResource(
                if (connecting) R.string.desktop_connecting else R.string.desktop_connect
            ),
            enabled = !connecting,
            onClick = {
                val port = portText.toIntOrNull()
                when {
                    host.isBlank() -> localError = invalidHost
                    port == null || port !in 1..65535 -> localError = invalidPort
                    viaSsh && sshHostId == null -> localError = invalidHost
                    else -> {
                        localError = null
                        val chosenSsh = sshHostId
                        if (viaSsh && chosenSsh != null) {
                            viewModel.connectViaSsh(
                                chosenSsh, sshPassphrase, host, port, password, noPassword
                            )
                        } else {
                            viewModel.connect(host, port, password, noPassword)
                        }
                        password = ""
                        sshPassphrase = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // Item F-03b: saved profiles (endpoints only — a
        // profile never stores the VNC password).
        if (profiles.isNotEmpty()) {
            Text(
                text = stringResource(R.string.desktop_profiles),
                style = MaterialTheme.typography.titleSmall
            )
            profiles.forEach { profile ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Text(
                        text = "${profile.name} — ${profile.host}:${profile.port}" +
                            if (profile.sshHostId != null) " (SSH)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            host = profile.host
                            portText = profile.port.toString()
                            viaSsh = profile.sshHostId != null
                            sshHostId = profile.sshHostId
                        }
                    ) { Text(stringResource(R.string.desktop_profile_apply)) }
                    OutlinedButton(
                        onClick = { viewModel.deleteProfile(profile.id) }
                    ) { Text(stringResource(R.string.desktop_profile_delete)) }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text(stringResource(R.string.desktop_profile_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = profileName.isNotBlank() && host.isNotBlank(),
                onClick = {
                    val port = portText.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        viewModel.saveProfile(
                            profileName.trim(), host, port,
                            if (viaSsh) sshHostId else null
                        )
                        profileName = ""
                    }
                }
            ) { Text(stringResource(R.string.desktop_profile_save)) }
        }
        Text(
            text = stringResource(R.string.desktop_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Text(
            text = stringResource(R.string.desktop_rdp_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DesktopSession(viewModel: RemoteDesktopViewModel, modifier: Modifier) {
    val state by viewModel.state.collectAsState()
    val frame by viewModel.frame.collectAsState()
    var typed by remember { mutableStateOf("") }
    // Item F-03b: sticky modifiers + clipboard-send text.
    var stickyCtrl by remember { mutableStateOf(false) }
    var stickyAlt by remember { mutableStateOf(false) }
    var stickyShift by remember { mutableStateOf(false) }
    var clipText by remember { mutableStateOf("") }
    fun keyTap(keysym: Int) {
        if (stickyCtrl || stickyAlt || stickyShift) {
            viewModel.tapModified(keysym, stickyCtrl, stickyAlt, stickyShift)
        } else {
            viewModel.tap(keysym)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.desktop_connected_to, state.desktopName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            SecondaryAction(
                label = stringResource(R.string.desktop_disconnect),
                onClick = { viewModel.disconnect() }
            )
        }
        val current = frame
        // Item F-03b: fit + pinch zoom + pan, one transform for
        // BOTH drawing and touch mapping (domain/DesktopGeometry)
        // so they can never disagree.
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        var zoom by remember { mutableStateOf(1f) }
        var panX by remember { mutableStateOf(0f) }
        var panY by remember { mutableStateOf(0f) }
        fun currentTransform(): com.pishi.termbridge.domain.DesktopGeometry.Transform {
            val f = frame
                ?: return com.pishi.termbridge.domain.DesktopGeometry.Transform(0f, 0f, 0f)
            return com.pishi.termbridge.domain.DesktopGeometry.transform(
                canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                f.width, f.height, zoom, panX, panY
            )
        }
        fun mapToDesktop(p: Offset): Pair<Int, Int>? {
            val f = frame ?: return null
            return com.pishi.termbridge.domain.DesktopGeometry.toRemote(
                currentTransform(), f.width, f.height, p.x, p.y
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            mapToDesktop(p)?.let { (x, y) ->
                                viewModel.pointer(1, x, y)
                                viewModel.pointer(0, x, y)
                            }
                        },
                        onDoubleTap = { p ->
                            mapToDesktop(p)?.let { (x, y) ->
                                repeat(2) {
                                    viewModel.pointer(1, x, y)
                                    viewModel.pointer(0, x, y)
                                }
                            }
                        },
                        onLongPress = { p ->
                            // Right click.
                            mapToDesktop(p)?.let { (x, y) ->
                                viewModel.pointer(4, x, y)
                                viewModel.pointer(0, x, y)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Pinch zoom. Only the zoom component is
                    // applied; while zoomed in, one-finger drags
                    // pan the VIEW (below) instead of the mouse.
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            zoom = (zoom * zoomChange).coerceIn(
                                1f,
                                com.pishi.termbridge.domain.DesktopGeometry.MAX_ZOOM
                            )
                            if (zoom == 1f) {
                                panX = 0f
                                panY = 0f
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    var last: Pair<Int, Int>? = null
                    var dragging = false
                    detectDragGestures(
                        onDragStart = { p ->
                            if (zoom <= 1f) {
                                last = mapToDesktop(p)
                                dragging = last != null
                                if (dragging) {
                                    viewModel.pointer(1, last!!.first, last!!.second)
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (zoom > 1f) {
                                panX += dragAmount.x
                                panY += dragAmount.y
                            } else if (dragging) {
                                last = mapToDesktop(change.position) ?: last
                                if (last != null) {
                                    viewModel.pointer(1, last!!.first, last!!.second)
                                }
                            }
                        },
                        onDragEnd = {
                            if (dragging && last != null) {
                                viewModel.pointer(0, last!!.first, last!!.second)
                            }
                            dragging = false
                        },
                        onDragCancel = {
                            if (dragging && last != null) {
                                viewModel.pointer(0, last!!.first, last!!.second)
                            }
                            dragging = false
                        }
                    )
                }
        ) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            if (current != null) {
                // Reading the version makes every new frame redraw.
                @Suppress("UNUSED_VARIABLE") val version = current.version
                val t = com.pishi.termbridge.domain.DesktopGeometry.transform(
                    size.width, size.height,
                    current.width, current.height, zoom, panX, panY
                )
                drawImage(
                    image = current.bitmap.asImageBitmap(),
                    dstOffset = IntOffset(t.offsetX.toInt(), t.offsetY.toInt()),
                    dstSize = IntSize(
                        (current.width * t.scale).toInt(),
                        (current.height * t.scale).toInt()
                    )
                )
            }
        }
        // Item F-03b: sticky modifiers + view reset.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.FilterChip(
                selected = stickyCtrl,
                onClick = { stickyCtrl = !stickyCtrl },
                label = { Text(stringResource(R.string.desktop_key_ctrl)) }
            )
            androidx.compose.material3.FilterChip(
                selected = stickyAlt,
                onClick = { stickyAlt = !stickyAlt },
                label = { Text(stringResource(R.string.desktop_key_alt)) }
            )
            androidx.compose.material3.FilterChip(
                selected = stickyShift,
                onClick = { stickyShift = !stickyShift },
                label = { Text(stringResource(R.string.desktop_key_shift)) }
            )
            if (zoom > 1f) {
                OutlinedButton(
                    onClick = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    }
                ) { Text(stringResource(R.string.desktop_reset_view)) }
            }
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            KeyButton(R.string.desktop_key_esc) { keyTap(RfbKeys.ESCAPE) }
            KeyButton(R.string.desktop_key_tab) { keyTap(RfbKeys.TAB) }
            KeyButton(R.string.desktop_key_enter) { keyTap(RfbKeys.ENTER) }
            KeyButton(R.string.desktop_key_backspace) { keyTap(RfbKeys.BACKSPACE) }
            KeyButton(R.string.desktop_key_delete) { keyTap(RfbKeys.DELETE) }
            KeyButton(R.string.desktop_key_win) { keyTap(RfbKeys.SUPER_L) }
            KeyButton(R.string.desktop_key_ctrl_alt_del) { viewModel.ctrlAltDelete() }
            OutlinedButton(onClick = { keyTap(RfbKeys.LEFT) }) {
                Text(stringResource(R.string.desktop_key_arrow_left))
            }
            OutlinedButton(onClick = { keyTap(RfbKeys.UP) }) {
                Text(stringResource(R.string.desktop_key_arrow_up))
            }
            OutlinedButton(onClick = { keyTap(RfbKeys.DOWN) }) {
                Text(stringResource(R.string.desktop_key_arrow_down))
            }
            OutlinedButton(onClick = { keyTap(RfbKeys.RIGHT) }) {
                Text(stringResource(R.string.desktop_key_arrow_right))
            }
            // Item F-03b: middle click + wheel, at the frame centre.
            KeyButton(R.string.desktop_key_middle) {
                frame?.let { f ->
                    viewModel.pointer(2, f.width / 2, f.height / 2)
                    viewModel.pointer(0, f.width / 2, f.height / 2)
                }
            }
            KeyButton(R.string.desktop_wheel_up) {
                frame?.let { f ->
                    viewModel.pointer(8, f.width / 2, f.height / 2)
                    viewModel.pointer(0, f.width / 2, f.height / 2)
                }
            }
            KeyButton(R.string.desktop_wheel_down) {
                frame?.let { f ->
                    viewModel.pointer(16, f.width / 2, f.height / 2)
                    viewModel.pointer(0, f.width / 2, f.height / 2)
                }
            }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { new ->
                if (new.length > typed.length) viewModel.typeText(new.substring(typed.length))
                else repeat(typed.length - new.length) { viewModel.tap(RfbKeys.BACKSPACE) }
                typed = ""
            },
            placeholder = { Text(stringResource(R.string.desktop_type_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.small)
        )
        // Item F-03b: clipboard send (RFB ClientCutText).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            OutlinedTextField(
                value = clipText,
                onValueChange = { clipText = it },
                placeholder = { Text(stringResource(R.string.desktop_clipboard_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = clipText.isNotEmpty(),
                onClick = {
                    viewModel.sendClipboard(clipText)
                    clipText = ""
                }
            ) { Text(stringResource(R.string.desktop_clipboard_send)) }
        }
        Text(
            text = stringResource(R.string.desktop_hint_touch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.small)
        )
    }
}

@Composable
private fun KeyButton(labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(stringResource(labelRes)) }
}
