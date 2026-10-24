package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.ProxySpecParser
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.components.TrustDialog
import com.pishi.termbridge.ui.components.UnavailableRow
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.AdvancedViewModel
import com.pishi.termbridge.ui.viewmodel.TunnelSessionStatus

/**
 * Prompt 10 Advanced screen — no longer a shell. Host picker,
 * tunnel CRUD, jump-chain ordering, proxy configuration, and a
 * tunnel session with real start/stop are all wired to
 * AdvancedViewModel. What is NOT implemented stays in the
 * "Not available" section with a reason per item — the honesty
 * banners for those items are kept, not removed.
 *
 * The tunnel session is a dedicated connection, separate from the
 * terminal session: starting tunnels here never touches a live
 * terminal, and stopping them disconnects only this connection.
 */
@Composable
fun AdvancedScreen(
    viewModel: AdvancedViewModel,
    onAddHost: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hosts by viewModel.hosts.collectAsState()
    val selectedHostId by viewModel.selectedHostId.collectAsState()
    val pendingTrust by viewModel.pendingTrust.collectAsState()

    pendingTrust?.let { pending ->
        TrustDialog(
            pending = pending,
            onTrust = { viewModel.answerTrust(true) },
            onReject = { viewModel.answerTrust(false) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(text = stringResource(R.string.advanced_intro))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(R.string.advanced_loopback_note),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.advanced_pick_host), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(Spacing.small))
        if (hosts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.advanced_no_hosts),
                body = stringResource(R.string.hosts_empty_body),
                actionLabel = stringResource(R.string.action_add_host),
                onAction = onAddHost
            )
        } else {
            for (host in hosts) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectHost(host.id) }
                ) {
                    RadioButton(
                        selected = selectedHostId == host.id,
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
        }

        val selectedId = selectedHostId
        if (selectedId != null) {
            key(selectedId) {
                AdvancedHostContent(viewModel = viewModel, hostId = selectedId)
            }
        }

        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.advanced_blocked_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(Spacing.medium))
        BlockedItem(
            label = stringResource(R.string.advanced_blocked_dynamic),
            reason = stringResource(R.string.advanced_blocked_dynamic_reason)
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        BlockedItem(
            label = stringResource(R.string.advanced_blocked_http),
            reason = stringResource(R.string.advanced_blocked_http_reason)
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        BlockedItem(
            label = stringResource(R.string.advanced_blocked_command),
            reason = stringResource(R.string.advanced_blocked_command_reason)
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        BlockedItem(
            label = stringResource(R.string.advanced_blocked_socks_auth),
            reason = stringResource(R.string.advanced_blocked_socks_auth_reason)
        )
    }
}

@Composable
private fun BlockedItem(label: String, reason: String) {
    UnavailableRow(label = label)
    Text(text = reason, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AdvancedHostContent(viewModel: AdvancedViewModel, hostId: Long) {
    val hosts by viewModel.hosts.collectAsState()
    val tunnels by viewModel.tunnels.collectAsState()
    val jumpChain by viewModel.jumpChain.collectAsState()
    val hopProfiles by viewModel.hopProfiles.collectAsState()
    val proxy by viewModel.proxy.collectAsState()
    val tunnelErrors by viewModel.tunnelErrors.collectAsState()
    val tunnelSaveError by viewModel.tunnelSaveError.collectAsState()
    val chainError by viewModel.chainError.collectAsState()
    val proxyError by viewModel.proxyError.collectAsState()
    val sessionStatus by viewModel.sessionStatus.collectAsState()
    val activeTunnels by viewModel.activeTunnels.collectAsState()
    val sessionError by viewModel.sessionError.collectAsState()

    val selectedHost = hosts.firstOrNull { it.id == hostId }

    var pendingDeleteTunnel by remember { mutableStateOf<TunnelConfig?>(null) }
    var confirmClearChain by remember { mutableStateOf(false) }

    if (pendingDeleteTunnel != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteTunnel = null },
            title = { Text(stringResource(R.string.tunnel_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.tunnel_delete_body,
                        pendingDeleteTunnel?.label?.ifBlank { null }
                            ?: "${pendingDeleteTunnel?.bindAddress}:${pendingDeleteTunnel?.bindPort}"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteTunnel?.let { viewModel.deleteTunnel(it.id) }
                        pendingDeleteTunnel = null
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTunnel = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (confirmClearChain) {
        AlertDialog(
            onDismissRequest = { confirmClearChain = false },
            title = { Text(stringResource(R.string.jump_clear_title)) },
            text = { Text(stringResource(R.string.jump_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearJumpChain()
                        confirmClearChain = false
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChain = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Spacer(modifier = Modifier.height(Spacing.large))
    Text(text = stringResource(R.string.advanced_tunnels), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.small))
    if (tunnels.isEmpty()) {
        Text(text = stringResource(R.string.tunnel_empty), style = MaterialTheme.typography.bodyMedium)
    }
    for (tunnel in tunnels) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val kindLabel = if (tunnel.kind == TunnelKind.LOCAL) {
                    stringResource(R.string.tunnel_kind_local)
                } else {
                    stringResource(R.string.tunnel_kind_remote)
                }
                Text(
                    text = if (tunnel.label.isNotBlank()) tunnel.label else kindLabel,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "$kindLabel ${tunnel.bindAddress}:${tunnel.bindPort} → " +
                        "${tunnel.targetHost}:${tunnel.targetPort}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = tunnel.enabled,
                onCheckedChange = { viewModel.setTunnelEnabled(tunnel.id, it) }
            )
            IconButton(onClick = { pendingDeleteTunnel = tunnel }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.tunnel_delete)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(Spacing.medium))
    TunnelForm(
        hostId = hostId,
        errors = tunnelErrors,
        onAdd = { viewModel.saveTunnel(it) }
    )
    tunnelSaveError?.let {
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }

    Spacer(modifier = Modifier.height(Spacing.large))
    Text(text = stringResource(R.string.advanced_jump), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.small))
    if (hopProfiles.isEmpty()) {
        Text(text = stringResource(R.string.jump_empty), style = MaterialTheme.typography.bodyMedium)
    }
    for ((index, hop) in hopProfiles.withIndex()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${index + 1}. ${hop.name}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${hop.username}@${hop.hostname}:${hop.port}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(
                onClick = {
                    val ids = jumpChain.jumpHostIds.toMutableList()
                    if (index > 0) {
                        val moved = ids.removeAt(index)
                        ids.add(index - 1, moved)
                        viewModel.saveJumpChain(ids)
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.tunnel_move_up)
                )
            }
            IconButton(
                onClick = {
                    val ids = jumpChain.jumpHostIds.toMutableList()
                    if (index < ids.lastIndex) {
                        val moved = ids.removeAt(index)
                        ids.add(index + 1, moved)
                        viewModel.saveJumpChain(ids)
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = stringResource(R.string.tunnel_move_down)
                )
            }
            IconButton(
                onClick = {
                    viewModel.saveJumpChain(jumpChain.jumpHostIds - hop.id)
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.tunnel_delete)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(Spacing.medium))
    HopAdder(
        hosts = hosts,
        targetHostId = hostId,
        chainIds = jumpChain.jumpHostIds,
        onAdd = { viewModel.saveJumpChain(jumpChain.jumpHostIds + it) }
    )
    if (jumpChain.jumpHostIds.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.small))
        SecondaryAction(
            label = stringResource(R.string.jump_clear),
            onClick = { confirmClearChain = true }
        )
    }
    chainError?.let {
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }

    Spacer(modifier = Modifier.height(Spacing.large))
    Text(text = stringResource(R.string.advanced_proxy), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.small))
    ProxyForm(
        current = proxy,
        error = proxyError,
        onSave = { viewModel.saveProxy(it) }
    )

    Spacer(modifier = Modifier.height(Spacing.large))
    Text(text = stringResource(R.string.advanced_session_title), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.small))
    TunnelSessionSection(
        viewModel = viewModel,
        selectedHost = selectedHost,
        hopProfiles = hopProfiles,
        tunnels = tunnels,
        sessionStatus = sessionStatus,
        activeTunnels = activeTunnels,
        sessionError = sessionError
    )
}

@Composable
private fun TunnelForm(
    hostId: Long,
    errors: Map<String, String>,
    onAdd: (TunnelConfig) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TunnelKind.LOCAL) }
    var bindAddress by remember { mutableStateOf("127.0.0.1") }
    var bindPort by remember { mutableStateOf("") }
    var targetHost by remember { mutableStateOf("localhost") }
    var targetPort by remember { mutableStateOf("") }
    var allowNonLoopback by remember { mutableStateOf(false) }
    var portError by remember { mutableStateOf<String?>(null) }
    val portNumberError = stringResource(R.string.tunnel_port_number_error)

    Text(text = stringResource(R.string.tunnel_add_title), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.small))
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text(stringResource(R.string.tunnel_label)) },
        placeholder = { Text(stringResource(R.string.tunnel_label_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = kind == TunnelKind.LOCAL, onClick = { kind = TunnelKind.LOCAL })
        Text(text = stringResource(R.string.tunnel_kind_local))
        RadioButton(selected = kind == TunnelKind.REMOTE, onClick = { kind = TunnelKind.REMOTE })
        Text(text = stringResource(R.string.tunnel_kind_remote))
    }
    OutlinedTextField(
        value = bindAddress,
        onValueChange = { bindAddress = it },
        label = { Text(stringResource(R.string.tunnel_bind)) },
        placeholder = { Text(stringResource(R.string.tunnel_bind_hint)) },
        singleLine = true,
        isError = errors["bindAddress"] != null,
        modifier = Modifier.fillMaxWidth()
    )
    errors["bindAddress"]?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    Spacer(modifier = Modifier.height(Spacing.small))
    OutlinedTextField(
        value = bindPort,
        onValueChange = { bindPort = it },
        label = { Text(stringResource(R.string.tunnel_bind_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = errors["bindPort"] != null || portError != null,
        modifier = Modifier.fillMaxWidth()
    )
    (errors["bindPort"] ?: portError)?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(Spacing.small))
    OutlinedTextField(
        value = targetHost,
        onValueChange = { targetHost = it },
        label = { Text(stringResource(R.string.tunnel_target_host)) },
        placeholder = { Text(stringResource(R.string.tunnel_target_hint)) },
        singleLine = true,
        isError = errors["targetHost"] != null,
        modifier = Modifier.fillMaxWidth()
    )
    errors["targetHost"]?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    Spacer(modifier = Modifier.height(Spacing.small))
    OutlinedTextField(
        value = targetPort,
        onValueChange = { targetPort = it },
        label = { Text(stringResource(R.string.tunnel_target_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = errors["targetPort"] != null || portError != null,
        modifier = Modifier.fillMaxWidth()
    )
    (errors["targetPort"] ?: portError)?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = allowNonLoopback, onCheckedChange = { allowNonLoopback = it })
        Text(
            text = stringResource(R.string.tunnel_allow_non_loopback),
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(modifier = Modifier.height(Spacing.small))
    PrimaryAction(
        label = stringResource(R.string.tunnel_add_title),
        onClick = {
            val bind = bindPort.toIntOrNull()
            val target = targetPort.toIntOrNull()
            if (bind == null || target == null) {
                portError = portNumberError
                return@PrimaryAction
            }
            portError = null
            onAdd(
                TunnelConfig(
                    hostId = hostId,
                    kind = kind,
                    bindAddress = bindAddress,
                    bindPort = bind,
                    targetHost = targetHost,
                    targetPort = target,
                    allowNonLoopback = allowNonLoopback,
                    label = label
                )
            )
        }
    )
}

@Composable
private fun HopAdder(
    hosts: List<com.pishi.termbridge.domain.HostProfile>,
    targetHostId: Long,
    chainIds: List<Long>,
    onAdd: (Long) -> Unit
) {
    var selected by remember { mutableStateOf<Long?>(null) }
    val candidates = hosts.filter { it.id != targetHostId && it.id !in chainIds }
    if (candidates.isEmpty()) return
    for (host in candidates) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { selected = host.id }
        ) {
            RadioButton(selected = selected == host.id, onClick = { selected = host.id })
            Text(text = host.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Spacer(modifier = Modifier.height(Spacing.small))
    PrimaryAction(
        label = stringResource(R.string.jump_add),
        onClick = { selected?.let { onAdd(it) } },
        enabled = selected != null
    )
}

@Composable
private fun ProxyForm(
    current: ProxyConfig,
    error: String?,
    onSave: (ProxyConfig) -> Unit
) {
    var kind by remember(current) { mutableStateOf(current.kind) }
    var spec by remember { mutableStateOf("") }
    var specError by remember { mutableStateOf<String?>(null) }
    var host by remember(current) { mutableStateOf(current.proxyHost) }
    var port by remember(current) {
        mutableStateOf(if (current.proxyPort > 0) current.proxyPort.toString() else "")
    }
    Text(
        text = if (current.kind == ProxyKind.SOCKS) {
            stringResource(R.string.proxy_current, ProxySpecParser.format(current))
        } else {
            stringResource(R.string.proxy_current, stringResource(R.string.proxy_none))
        },
        style = MaterialTheme.typography.bodyMedium
    )
    // Security fix 16 (L6): the proxy operator's metadata view,
    // stated before the user saves a proxy, not buried in docs.
    Spacer(modifier = Modifier.height(Spacing.small))
    Text(
        text = stringResource(R.string.proxy_metadata_note),
        style = MaterialTheme.typography.bodySmall
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = kind == ProxyKind.NONE, onClick = { kind = ProxyKind.NONE })
        Text(text = stringResource(R.string.proxy_none))
        RadioButton(selected = kind == ProxyKind.SOCKS, onClick = { kind = ProxyKind.SOCKS })
        Text(text = stringResource(R.string.proxy_socks))
    }
    OutlinedTextField(
        value = spec,
        onValueChange = { spec = it },
        label = { Text(stringResource(R.string.proxy_spec)) },
        placeholder = { Text(stringResource(R.string.proxy_spec_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    specError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    Spacer(modifier = Modifier.height(Spacing.small))
    SecondaryAction(
        label = stringResource(R.string.proxy_parse),
        onClick = {
            val result = ProxySpecParser.parse(spec, current.hostId)
            if (result.config != null) {
                host = result.config.proxyHost
                port = result.config.proxyPort.toString()
                kind = ProxyKind.SOCKS
                specError = null
            } else {
                specError = result.error
            }
        }
    )
    Spacer(modifier = Modifier.height(Spacing.small))
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.proxy_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(Spacing.small))
    OutlinedTextField(
        value = port,
        onValueChange = { port = it },
        label = { Text(stringResource(R.string.proxy_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    error?.let {
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(Spacing.small))
    Row {
        PrimaryAction(
            label = stringResource(R.string.proxy_save),
            onClick = {
                onSave(
                    ProxyConfig(
                        hostId = current.hostId,
                        kind = kind,
                        proxyHost = host,
                        proxyPort = port.toIntOrNull() ?: 0
                    )
                )
            }
        )
    }
    Spacer(modifier = Modifier.height(Spacing.small))
    SecondaryAction(
        label = stringResource(R.string.proxy_clear),
        onClick = { onSave(ProxyConfig(hostId = current.hostId, kind = ProxyKind.NONE)) }
    )
}

@Composable
private fun TunnelSessionSection(
    viewModel: AdvancedViewModel,
    selectedHost: com.pishi.termbridge.domain.HostProfile?,
    hopProfiles: List<com.pishi.termbridge.domain.HostProfile>,
    tunnels: List<TunnelConfig>,
    sessionStatus: TunnelSessionStatus,
    activeTunnels: List<com.pishi.termbridge.transport.ActiveTunnel>,
    sessionError: String?
) {
    var targetPassphrase by remember { mutableStateOf("") }
    var hopPassphrases by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    if (selectedHost?.authType == AuthType.PRIVATE_KEY) {
        OutlinedTextField(
            value = targetPassphrase,
            onValueChange = { targetPassphrase = it },
            label = { Text(stringResource(R.string.advanced_passphrase_target)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.small))
    }
    for (hop in hopProfiles) {
        if (hop.authType == AuthType.PRIVATE_KEY) {
            OutlinedTextField(
                value = hopPassphrases[hop.id].orEmpty(),
                onValueChange = { hopPassphrases = hopPassphrases + (hop.id to it) },
                label = { Text(stringResource(R.string.advanced_passphrase_hop, hop.name)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.small))
        }
    }
    when (sessionStatus) {
        TunnelSessionStatus.RUNNING -> {
            Text(text = stringResource(R.string.tunnel_running))
            Spacer(modifier = Modifier.height(Spacing.small))
            PrimaryAction(
                label = stringResource(R.string.tunnel_stop),
                onClick = { viewModel.stopTunnels() }
            )
        }
        TunnelSessionStatus.CONNECTING -> {
            Text(text = stringResource(R.string.tunnel_connecting))
            Spacer(modifier = Modifier.height(Spacing.small))
            SecondaryAction(
                label = stringResource(R.string.action_cancel),
                onClick = { viewModel.cancelConnect() }
            )
        }
        else -> {
            PrimaryAction(
                label = stringResource(R.string.tunnel_start),
                onClick = {
                    viewModel.startTunnels(targetPassphrase, hopPassphrases)
                    targetPassphrase = ""
                    hopPassphrases = emptyMap()
                },
                enabled = selectedHost != null && tunnels.any { it.enabled }
            )
        }
    }
    sessionError?.let {
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    if (activeTunnels.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(text = stringResource(R.string.tunnel_active_title), style = MaterialTheme.typography.titleSmall)
        for (active in activeTunnels) {
            val kindLabel = if (active.kind == TunnelKind.LOCAL) {
                stringResource(R.string.tunnel_kind_local)
            } else {
                stringResource(R.string.tunnel_kind_remote)
            }
            Text(
                text = "$kindLabel ${active.bindAddress}:${active.bindPort} → " +
                    "${active.targetHost}:${active.targetPort}" +
                    if (active.label.isNotBlank()) " (${active.label})" else "",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
