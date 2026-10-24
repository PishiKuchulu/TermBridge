package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/**
 * Settings shell: navigation only. No toggles are shown, because a
 * toggle that does not persist or apply would be a decorative
 * control. Behaviour settings arrive with their features.
 */
@Composable
fun SettingsScreen(
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onBackup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(text = stringResource(R.string.settings_note))
        Spacer(modifier = Modifier.height(Spacing.large))
        SecondaryAction(
            label = stringResource(R.string.action_diagnostics),
            onClick = onDiagnostics
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        SecondaryAction(
            label = stringResource(R.string.action_backup),
            onClick = onBackup
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        SecondaryAction(
            label = stringResource(R.string.action_about),
            onClick = onAbout
        )
        // Security fix 16 (M1): the FLAG_SECURE tradeoff,
        // disclosed where the user will find it.
        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.settings_secure_note))
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    TermBridgeTheme { SettingsScreen(onDiagnostics = {}, onAbout = {}, onBackup = {}) }
}
