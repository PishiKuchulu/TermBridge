package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.HostsViewModel

/**
 * Hosts list, now real (prompt 04): rows come from Room via the
 * ViewModel. An empty database shows the empty state — no seed or
 * sample hosts. Delete always asks first (destructive confirmation);
 * duplicate copies non-secret fields only and never copies a secret.
 * Prompt 09 adds a per-host environment action (tune icon) that
 * opens the host's environment and paths screen.
 */
@Composable
fun HostsScreen(
    viewModel: HostsViewModel,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onEnvironmentHost: (Long) -> Unit,
    onAdvanced: () -> Unit,
    onSessionPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hosts by viewModel.hosts.collectAsState()
    val error by viewModel.error.collectAsState()
    var pendingDelete by remember { mutableStateOf<HostProfile?>(null) }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_host_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_host_body,
                        pendingDelete?.name ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete?.let { viewModel.deleteHost(it.id) }
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (hosts.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(Spacing.medium)
        ) {
            EmptyState(
                title = stringResource(R.string.hosts_empty_title),
                body = stringResource(R.string.hosts_empty_body),
                actionLabel = stringResource(R.string.action_add_host),
                onAction = onAddHost
            )
            Spacer(modifier = Modifier.height(Spacing.large))
            Text(text = stringResource(R.string.hosts_note))
            Spacer(modifier = Modifier.height(Spacing.medium))
            SecondaryAction(
                label = stringResource(R.string.action_session_preview),
                onClick = onSessionPreview
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            SecondaryAction(
                label = stringResource(R.string.action_advanced),
                onClick = onAdvanced
            )
            error?.let {
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(Spacing.medium)) {
                    PrimaryAction(
                        label = stringResource(R.string.action_add_host),
                        onClick = onAddHost
                    )
                    error?.let {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(hosts, key = { it.id }) { host ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = host.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${host.username}@${host.hostname}:${host.port}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (host.authType == AuthType.PASSWORD) {
                                stringResource(R.string.auth_password)
                            } else {
                                stringResource(R.string.auth_private_key)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onEditHost(host.id) }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.action_edit_host)
                        )
                    }
                    IconButton(onClick = { onEnvironmentHost(host.id) }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.action_environment_host)
                        )
                    }
                    IconButton(onClick = { viewModel.duplicateHost(host.id) }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.action_duplicate_host)
                        )
                    }
                    IconButton(onClick = { pendingDelete = host }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete_host)
                        )
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(Spacing.medium)) {
                    SecondaryAction(
                        label = stringResource(R.string.action_session_preview),
                        onClick = onSessionPreview
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.action_advanced),
                        onClick = onAdvanced
                    )
                }
            }
        }
    }
}
