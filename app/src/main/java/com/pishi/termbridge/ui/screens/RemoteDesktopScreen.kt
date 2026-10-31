package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    if (state.status == DesktopStatus.CONNECTED) {
        DesktopSession(viewModel, modifier)
    } else {
        ConnectForm(viewModel, state.status == DesktopStatus.CONNECTING, state.error, initialHost, modifier)
    }
}

@Composable
private fun ConnectForm(
    viewModel: RemoteDesktopViewModel,
    connecting: Boolean,
    error: String?,
    initialHost: String,
    modifier: Modifier
) {
    var host by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf("5900") }
    var password by remember { mutableStateOf("") }
    var noPassword by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
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
                    else -> {
                        localError = null
                        viewModel.connect(host, port, password, noPassword)
                        password = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
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
        // Letterboxed, fit-to-screen mapping between touch and framebuffer.
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        fun mapToDesktop(p: Offset): Triple<Int, Int, Boolean> {
            val f = frame ?: return Triple(0, 0, false)
            val scale = min(canvasSize.width / f.width.toFloat(), canvasSize.height / f.height.toFloat())
            if (scale <= 0f) return Triple(0, 0, false)
            val ox = (canvasSize.width - f.width * scale) / 2f
            val oy = (canvasSize.height - f.height * scale) / 2f
            val x = ((p.x - ox) / scale).toInt().coerceIn(0, f.width - 1)
            val y = ((p.y - oy) / scale).toInt().coerceIn(0, f.height - 1)
            return Triple(x, y, true)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            val (x, y, ok) = mapToDesktop(p)
                            if (ok) {
                                viewModel.pointer(1, x, y)
                                viewModel.pointer(0, x, y)
                            }
                        },
                        onDoubleTap = { p ->
                            val (x, y, ok) = mapToDesktop(p)
                            if (ok) repeat(2) {
                                viewModel.pointer(1, x, y)
                                viewModel.pointer(0, x, y)
                            }
                        },
                        onLongPress = { p ->
                            val (x, y, ok) = mapToDesktop(p)
                            if (ok) {
                                viewModel.pointer(4, x, y)
                                viewModel.pointer(0, x, y)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    var last = Triple(0, 0, false)
                    detectDragGestures(
                        onDragStart = { p ->
                            last = mapToDesktop(p)
                            if (last.third) viewModel.pointer(1, last.first, last.second)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            last = mapToDesktop(change.position)
                            if (last.third) viewModel.pointer(1, last.first, last.second)
                        },
                        onDragEnd = { if (last.third) viewModel.pointer(0, last.first, last.second) },
                        onDragCancel = { if (last.third) viewModel.pointer(0, last.first, last.second) }
                    )
                }
        ) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            if (current != null) {
                // Reading the version makes every new frame redraw.
                @Suppress("UNUSED_VARIABLE") val version = current.version
                val scale = min(size.width / current.width, size.height / current.height)
                val w = (current.width * scale).toInt()
                val h = (current.height * scale).toInt()
                drawImage(
                    image = current.bitmap.asImageBitmap(),
                    dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w, h)
                )
            }
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            KeyButton(R.string.desktop_key_esc) { viewModel.tap(RfbKeys.ESCAPE) }
            KeyButton(R.string.desktop_key_tab) { viewModel.tap(RfbKeys.TAB) }
            KeyButton(R.string.desktop_key_enter) { viewModel.tap(RfbKeys.ENTER) }
            KeyButton(R.string.desktop_key_backspace) { viewModel.tap(RfbKeys.BACKSPACE) }
            KeyButton(R.string.desktop_key_delete) { viewModel.tap(RfbKeys.DELETE) }
            KeyButton(R.string.desktop_key_win) { viewModel.tap(RfbKeys.SUPER_L) }
            KeyButton(R.string.desktop_key_ctrl_alt_del) { viewModel.ctrlAltDelete() }
            OutlinedButton(onClick = { viewModel.tap(RfbKeys.LEFT) }) { Text("←") }
            OutlinedButton(onClick = { viewModel.tap(RfbKeys.UP) }) { Text("↑") }
            OutlinedButton(onClick = { viewModel.tap(RfbKeys.DOWN) }) { Text("↓") }
            OutlinedButton(onClick = { viewModel.tap(RfbKeys.RIGHT) }) { Text("→") }
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
