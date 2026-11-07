package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostSort
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.HostsViewModel

/**
 * Hosts list (prompt 04; organization added by item T-22):
 * rows come from Room via the ViewModel. An empty database
 * shows the empty state — no seed or sample hosts. Delete
 * always asks first (destructive confirmation); duplicate
 * copies non-secret fields only and never copies a secret.
 * Prompt 09 adds a per-host environment action (tune icon).
 *
 * Item T-22: search, tag filter chips, three sort orders,
 * group sections, a favorite star per row, the last-connected
 * time, and a multi-select mode with bulk delete. All rules
 * live in domain/HostListLogic; this screen only renders the
 * sections the ViewModel derives from them.
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
    val sections by viewModel.sections.collectAsState()
    val query by viewModel.query.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val tagFilter by viewModel.tagFilter.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val error by viewModel.error.collectAsState()
    var pendingDelete by remember { mutableStateOf<HostProfile?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    var pendingDeleteSelected by remember { mutableStateOf(false) }

    LaunchedEffect(selectMode) {
        if (!selectMode) viewModel.clearSelection()
    }

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

    if (pendingDeleteSelected) {
        AlertDialog(
            onDismissRequest = { pendingDeleteSelected = false },
            title = { Text(stringResource(R.string.hosts_delete_selected_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.hosts_delete_selected_body,
                        selection.size,
                        selection.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        pendingDeleteSelected = false
                        selectMode = false
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelected = false }) {
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
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.setQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.hosts_search)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.hosts_search_clear
                                        )
                                    )
                                }
                            }
                        },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = sort == HostSort.NAME,
                            onClick = { viewModel.setSort(HostSort.NAME) },
                            label = { Text(stringResource(R.string.hosts_sort_name)) }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = Spacing.small / 2))
                        FilterChip(
                            selected = sort == HostSort.LAST_CONNECTED,
                            onClick = { viewModel.setSort(HostSort.LAST_CONNECTED) },
                            label = {
                                Text(stringResource(R.string.hosts_sort_last_connected))
                            }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = Spacing.small / 2))
                        FilterChip(
                            selected = sort == HostSort.RECENTLY_ADDED,
                            onClick = { viewModel.setSort(HostSort.RECENTLY_ADDED) },
                            label = {
                                Text(stringResource(R.string.hosts_sort_recently_added))
                            }
                        )
                    }
                    if (tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = tagFilter == null,
                                onClick = { viewModel.setTagFilter(null) },
                                label = {
                                    Text(stringResource(R.string.hosts_filter_all_tags))
                                }
                            )
                            for (tag in tags) {
                                Spacer(
                                    modifier = Modifier.padding(
                                        horizontal = Spacing.small / 2
                                    )
                                )
                                FilterChip(
                                    selected = tagFilter == tag,
                                    onClick = {
                                        viewModel.setTagFilter(
                                            if (tagFilter == tag) null else tag
                                        )
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.small))
                    if (selectMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.hosts_selected_count,
                                    selection.size,
                                    selection.size
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { pendingDeleteSelected = true },
                                enabled = selection.isNotEmpty()
                            ) { Text(stringResource(R.string.action_delete)) }
                            TextButton(onClick = { selectMode = false }) {
                                Text(stringResource(R.string.action_done))
                            }
                        }
                    } else {
                        SecondaryAction(
                            label = stringResource(R.string.hosts_action_select),
                            onClick = { selectMode = true }
                        )
                    }
                }
            }
            if (sections.all { it.second.isEmpty() }) {
                item {
                    Text(
                        text = stringResource(R.string.hosts_no_match),
                        modifier = Modifier.padding(Spacing.medium),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val showHeaders = sections.size > 1 ||
                sections.singleOrNull()?.first?.isNotEmpty() == true
            for ((groupName, members) in sections) {
                if (showHeaders) {
                    item(key = "group-$groupName") {
                        Text(
                            text = groupName.ifEmpty {
                                stringResource(R.string.hosts_group_ungrouped)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                horizontal = Spacing.medium,
                                vertical = Spacing.small
                            )
                        )
                    }
                }
                items(members, key = { it.id }) { host ->
                    HostRow(
                        host = host,
                        selectMode = selectMode,
                        selected = host.id in selection,
                        onToggleSelected = { viewModel.toggleSelected(host.id) },
                        onToggleFavorite = { viewModel.toggleFavorite(host) },
                        onEdit = { onEditHost(host.id) },
                        onEnvironment = { onEnvironmentHost(host.id) },
                        onDuplicate = { viewModel.duplicateHost(host.id) },
                        onDelete = { pendingDelete = host }
                    )
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

@Composable
private fun HostRow(
    host: HostProfile,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onEnvironment: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
        } else {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (host.favorite) {
                        Icons.Filled.Star
                    } else {
                        Icons.Filled.StarBorder
                    },
                    contentDescription = stringResource(
                        if (host.favorite) {
                            R.string.hosts_unfavorite
                        } else {
                            R.string.hosts_favorite
                        }
                    ),
                    tint = if (host.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
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
            if (host.groupName.isNotBlank()) {
                Text(
                    text = host.groupName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            host.lastConnectedAt?.let { at ->
                Text(
                    text = stringResource(
                        R.string.hosts_last_connected,
                        formatTimestamp(at)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!selectMode) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.action_edit_host)
                )
            }
            IconButton(onClick = onEnvironment) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.action_environment_host)
                )
            }
            IconButton(onClick = onDuplicate) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_duplicate_host)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete_host)
                )
            }
        }
    }
}

/** Short locale date-time for the last-connected line. Locale
 *  formatting is deliberate: the app ships English and Persian,
 *  and a hard-coded pattern would be wrong in one of them. */
private fun formatTimestamp(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(epochMs))
