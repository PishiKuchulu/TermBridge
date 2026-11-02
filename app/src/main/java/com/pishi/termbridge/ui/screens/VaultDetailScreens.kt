package com.pishi.termbridge.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.pishi.termbridge.R
import com.pishi.termbridge.data.TrustedHostKeyEntity
import com.pishi.termbridge.domain.ConnectionLogEntry
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.DiscoverViewModel
import com.pishi.termbridge.ui.viewmodel.KnownHostsViewModel
import com.pishi.termbridge.ui.viewmodel.LogsViewModel
import java.net.Inet4Address
import java.text.DateFormat
import java.util.Date

@Composable
fun KnownHostsScreen(viewModel: KnownHostsViewModel, modifier: Modifier = Modifier) {
    val keys by viewModel.keys.collectAsState()
    var pending by remember { mutableStateOf<TrustedHostKeyEntity?>(null) }
    if (keys.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.known_hosts_empty_title),
            body = stringResource(R.string.known_hosts_empty_body),
            modifier = modifier.padding(Spacing.medium)
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            items(keys, key = { it.endpoint }) { key ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = key.endpoint, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = key.keyType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Text(
                                    text = key.fingerprint,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.known_hosts_first_seen,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(key.firstSeenAt))
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { pending = key }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.known_hosts_forget)
                            )
                        }
                    }
                }
            }
        }
    }
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.known_hosts_forget_title)) },
            text = { Text(stringResource(R.string.known_hosts_forget_body, target.endpoint)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forget(target.endpoint)
                    pending = null
                }) { Text(stringResource(R.string.known_hosts_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun LogsScreen(viewModel: LogsViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    if (entries.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.logs_empty_title),
            body = stringResource(R.string.logs_empty_body),
            modifier = modifier.padding(Spacing.medium)
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.medium)
            ) {
                items(entries) { entry: ConnectionLogEntry ->
                    LogRow(entry)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            TextButton(
                onClick = { confirmClear = true },
                modifier = Modifier.padding(Spacing.small)
            ) { Text(stringResource(R.string.logs_clear)) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.logs_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onPick: (address: String, port: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Text(
            text = stringResource(R.string.discover_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PrimaryAction(
            label = stringResource(R.string.discover_scan),
            onClick = { viewModel.scan(localIpv4(context)) },
            enabled = !state.scanning,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.scanning) {
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.discover_scanning, state.done, state.total))
        }
        if (state.noNetwork) {
            Text(stringResource(R.string.discover_no_network), color = MaterialTheme.colorScheme.error)
        }
        if (state.finished && !state.noNetwork && state.devices.isEmpty()) {
            Text(stringResource(R.string.discover_none))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            items(state.devices, key = { it.address }) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(Spacing.medium)) {
                        Text(text = device.address, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            device.ports.sorted().forEach { port ->
                                if (port == 3389) {
                                    // RDP is not supported: shown, not tappable.
                                    Text(
                                        text = "RDP · 3389",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(Spacing.small)
                                    )
                                    return@forEach
                                }
                                TextButton(onClick = { onPick(device.address, port) }) {
                                    Text(
                                        when (port) {
                                            22 -> "SSH · 22"
                                            5900 -> "VNC · 5900"
                                            3389 -> "RDP · 3389"
                                            else -> port.toString()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The phone's own IPv4 address and prefix length on the active network, or null. */
internal fun localIpv4(context: Context): Pair<String, Int>? {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    val network = manager.activeNetwork ?: return null
    val link = manager.getLinkProperties(network) ?: return null
    val address = link.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        ?: return null
    val host = address.address.hostAddress ?: return null
    return host to address.prefixLength
}
