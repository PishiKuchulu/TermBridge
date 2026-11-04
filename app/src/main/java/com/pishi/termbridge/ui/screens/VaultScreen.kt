package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.theme.Spacing

/**
 * The vault hub: one place for everything that is stored on the
 * device. Each row is a real destination that already exists in the
 * app; nothing here is a placeholder.
 */
@Composable
fun VaultScreen(
    onHosts: () -> Unit,
    onPortForwarding: () -> Unit,
    onSnippets: () -> Unit,
    onKnownHosts: () -> Unit,
    onLogs: () -> Unit,
    onKeychain: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        NavCard(Icons.Filled.Storage, stringResource(R.string.vault_hosts), stringResource(R.string.vault_hosts_sub), onHosts)
        NavCard(Icons.Filled.VpnKey, stringResource(R.string.vault_keychain), stringResource(R.string.vault_keychain_sub), onKeychain)
        NavCard(Icons.Filled.SwapHoriz, stringResource(R.string.vault_port_forwarding), stringResource(R.string.vault_port_forwarding_sub), onPortForwarding)
        NavCard(Icons.Filled.Code, stringResource(R.string.vault_snippets), stringResource(R.string.vault_snippets_sub), onSnippets)
        NavCard(Icons.Filled.Fingerprint, stringResource(R.string.vault_known_hosts), stringResource(R.string.vault_known_hosts_sub), onKnownHosts)
        NavCard(Icons.Filled.History, stringResource(R.string.vault_logs), stringResource(R.string.vault_logs_sub), onLogs)
    }
}

/** A tappable card with an icon, a title, a one-line description and a chevron. */
@Composable
fun NavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.size(Spacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Spacing.medium, bottom = Spacing.small)
    )
}

@Composable
internal fun GapSmall() = Spacer(modifier = Modifier.height(Spacing.small))
