package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.ConnectionLogEntry
import com.pishi.termbridge.domain.ConnectionOutcome
import com.pishi.termbridge.domain.QuickConnect
import com.pishi.termbridge.domain.QuickConnectTarget
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * Entry point for starting a connection: a quick-connect line that
 * understands `ssh user@host -p 2222`, plus every way to reach a
 * server. Quick connect (item T-04) dials directly WITHOUT saving
 * the host; the credential is asked in its own field below the
 * line — never inside the free-text line itself. "Save as a host
 * instead" keeps the old pre-fill path for a permanent entry.
 */
@Composable
fun ConnectionsScreen(
    recent: List<ConnectionLogEntry>,
    liveSession: Boolean,
    onQuickConnectPassword: (QuickConnectTarget, String, CharArray) -> Unit,
    onQuickConnectKeychain: (QuickConnectTarget, String, Long, String) -> Unit,
    onSaveAsHost: (QuickConnectTarget) -> Unit,
    keychainEntries: List<com.pishi.termbridge.domain.KeychainEntry> = emptyList(),
    onAddHost: () -> Unit,
    onSftp: () -> Unit,
    onDiscover: () -> Unit,
    onDesktop: () -> Unit,
    onTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    var line by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<QuickConnectTarget?>(null) }
    var username by remember { mutableStateOf("") }
    var useKeychain by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    var selectedEntryId by remember { mutableStateOf<Long?>(null) }
    val submit: () -> Unit = {
        val target = QuickConnect.parse(line)
        if (target == null) {
            invalid = true
        } else {
            invalid = false
            parsed = target
            username = target.username ?: ""
            selectedEntryId = keychainEntries.firstOrNull()?.id
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line,
                onValueChange = {
                    line = it
                    invalid = false
                },
                singleLine = true,
                isError = invalid,
                placeholder = { Text(stringResource(R.string.connections_quick_hint)) },
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.connections_quick_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.weight(1f)
            )
        }
        PrimaryAction(
            label = stringResource(R.string.connections_quick_action),
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth()
        )
        val target = parsed
        if (target != null) {
            Text(
                text = stringResource(
                    R.string.connections_quick_target,
                    target.host, target.port
                ),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text(stringResource(R.string.field_username)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (keychainEntries.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = !useKeychain,
                        onClick = { useKeychain = false }
                    )
                    Text(stringResource(R.string.auth_password))
                    androidx.compose.material3.RadioButton(
                        selected = useKeychain,
                        onClick = { useKeychain = true }
                    )
                    Text(stringResource(R.string.keychain_title))
                }
            }
            if (!useKeychain) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.auth_password)) },
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                for (entry in keychainEntries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedEntryId == entry.id,
                            onClick = { selectedEntryId = entry.id }
                        )
                        Text(entry.label)
                    }
                }
                val selected = keychainEntries.firstOrNull { it.id == selectedEntryId }
                if (selected?.kind == com.pishi.termbridge.domain.AuthType.PRIVATE_KEY) {
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_passphrase)) },
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            PrimaryAction(
                label = stringResource(R.string.session_connect),
                enabled = username.isNotBlank() &&
                    (if (useKeychain) selectedEntryId != null else password.isNotEmpty()),
                onClick = {
                    if (useKeychain) {
                        selectedEntryId?.let { entryId ->
                            onQuickConnectKeychain(target, username, entryId, keyPassphrase)
                        }
                    } else {
                        onQuickConnectPassword(target, username, password.toCharArray())
                    }
                    password = ""
                    keyPassphrase = ""
                    parsed = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row {
                androidx.compose.material3.TextButton(
                    onClick = { onSaveAsHost(target) }
                ) { Text(stringResource(R.string.connections_quick_save_as_host)) }
                androidx.compose.material3.TextButton(
                    onClick = { parsed = null }
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }

        SectionLabel(stringResource(R.string.connections_ways_title))
        if (liveSession) {
            NavCard(Icons.Filled.Terminal, stringResource(R.string.connections_way_terminal), "", onTerminal)
        }
        NavCard(Icons.Filled.Add, stringResource(R.string.connections_way_add_host), "", onAddHost)
        NavCard(Icons.Filled.Folder, stringResource(R.string.connections_way_sftp), "", onSftp)
        NavCard(Icons.Filled.Wifi, stringResource(R.string.connections_way_discover), "", onDiscover)
        NavCard(Icons.Filled.DesktopWindows, stringResource(R.string.connections_way_desktop), "", onDesktop)

        SectionLabel(stringResource(R.string.connections_recent_title))
        if (recent.isEmpty()) {
            Text(
                text = stringResource(R.string.connections_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            recent.take(5).forEach { entry -> LogRow(entry) }
        }
    }
}

@Composable
internal fun LogRow(entry: ConnectionLogEntry) {
    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(entry.timestampMs))
    val outcome = when (entry.outcome) {
        ConnectionOutcome.CONNECTED -> stringResource(R.string.logs_outcome_connected)
        ConnectionOutcome.FAILED -> stringResource(R.string.logs_outcome_failed)
        ConnectionOutcome.CLOSED -> stringResource(
            R.string.logs_outcome_closed,
            formatDuration(entry.durationMs)
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.small)) {
        Text(text = entry.hostName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "$outcome · $whenText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (entry.detail.isNotBlank()) {
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
