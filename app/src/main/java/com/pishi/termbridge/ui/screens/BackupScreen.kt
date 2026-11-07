package com.pishi.termbridge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.resolve
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.BackupViewModel

/**
 * Prompt 11 backup screen. Every control does real work:
 * - Export writes a versioned JSON file through a SAF
 *   CreateDocument picker (no storage permission, no fixed path).
 * - Import reads through a SAF OpenDocument picker, shows a
 *   preview (names and counts only — never a secret value), and
 *   applies only on an explicit tap.
 * - Conflicting hosts are skipped by default; replacing them
 *   requires the checkbox AND a confirmation dialog, and even
 *   then a credential is replaced only when the backup itself
 *   carries one (encrypted mode).
 * - Passphrases are never persisted and the fields are cleared
 *   after use by the ViewModel.
 */
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    var showReplaceConfirm by remember { mutableStateOf(false) }
    val pendingExport = viewModel.pendingExportText

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.writeExport(uri) else viewModel.clearPendingExport()
    }
    LaunchedEffect(pendingExport) {
        if (pendingExport != null) {
            exportLauncher.launch("termbridge-backup.json")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.previewFrom(uri)
    }

    val preview = viewModel.preview
    if (showReplaceConfirm && preview != null) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.backup_replace_body,
                        preview.conflicts.size,
                        preview.conflicts.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        viewModel.applyPreview()
                    }
                ) { Text(stringResource(R.string.backup_replace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(text = stringResource(R.string.backup_intro))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(R.string.backup_excluded_note),
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleMedium)
        // Prompt 14: whole-row selection, 48dp minimum.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouch)
                .clickable { viewModel.exportEncrypted = false },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = !viewModel.exportEncrypted,
                onClick = { viewModel.exportEncrypted = false }
            )
            Text(text = stringResource(R.string.backup_export_plain))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouch)
                .clickable { viewModel.exportEncrypted = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = viewModel.exportEncrypted,
                onClick = { viewModel.exportEncrypted = true }
            )
            Text(text = stringResource(R.string.backup_export_encrypted))
        }
        Text(
            text = stringResource(R.string.backup_export_plain_note),
            style = MaterialTheme.typography.bodySmall
        )
        if (viewModel.exportEncrypted) {
            Spacer(modifier = Modifier.height(Spacing.medium))
            OutlinedTextField(
                value = viewModel.exportPassphrase,
                onValueChange = { viewModel.exportPassphrase = it },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.medium))
            OutlinedTextField(
                value = viewModel.exportConfirmPassphrase,
                onValueChange = { viewModel.exportConfirmPassphrase = it },
                label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(
                text = stringResource(R.string.backup_passphrase_note),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(Spacing.medium))
        PrimaryAction(
            label = stringResource(R.string.backup_export_action),
            onClick = { viewModel.prepareExport() },
            enabled = !viewModel.isWorking
        )

        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.backup_import_title), style = MaterialTheme.typography.titleMedium)
        SecondaryAction(
            label = stringResource(R.string.backup_import_pick),
            onClick = { importLauncher.launch(arrayOf("application/json")) },
            enabled = !viewModel.isWorking
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        OutlinedTextField(
            value = viewModel.importPassphrase,
            onValueChange = { viewModel.importPassphrase = it },
            label = { Text(stringResource(R.string.backup_import_passphrase)) },
            placeholder = { Text(stringResource(R.string.backup_import_passphrase_hint)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        if (preview != null) {
            Spacer(modifier = Modifier.height(Spacing.large))
            Text(text = stringResource(R.string.backup_preview_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.backup_preview_counts,
                    preview.hostCount,
                    preview.buttonCount,
                    preview.tunnelCount,
                    preview.envVarCount
                )
            )
            if (preview.encrypted) {
                Text(text = stringResource(R.string.backup_preview_encrypted))
            }
            if (preview.hasCredentials) {
                Text(text = stringResource(R.string.backup_preview_has_credentials))
            }
            if (preview.keychainCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.backup_preview_keychain,
                        preview.keychainCount,
                        preview.keychainCount
                    )
                )
            }
            if (preview.snippetCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.backup_preview_snippets,
                        preview.snippetCount,
                        preview.snippetCount
                    )
                )
            }
            if (preview.transcriptCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.backup_preview_transcripts,
                        preview.transcriptCount,
                        preview.transcriptCount
                    )
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
            for (host in preview.document.hosts) {
                Text(text = "${host.name} — ${host.username}@${host.hostname}:${host.port}")
            }
            if (preview.conflicts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(text = stringResource(R.string.backup_preview_conflicts))
                for (conflict in preview.conflicts) {
                    Text(
                        text = stringResource(
                            R.string.backup_conflict_line,
                            conflict.importedName,
                            conflict.existingName
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = viewModel.replaceExisting,
                        onCheckedChange = { viewModel.replaceExisting = it }
                    )
                    Text(text = stringResource(R.string.backup_replace_checkbox))
                }
                Text(
                    text = stringResource(R.string.backup_replace_note),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (preview.warnings.isNotEmpty() || preview.advisories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(text = stringResource(R.string.backup_preview_warnings))
                for (warning in preview.warnings + preview.advisories) {
                    Text(text = warning)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
            PrimaryAction(
                label = stringResource(R.string.backup_import_apply),
                onClick = {
                    if (viewModel.replaceExisting && preview.conflicts.isNotEmpty()) {
                        showReplaceConfirm = true
                    } else {
                        viewModel.applyPreview()
                    }
                },
                enabled = !viewModel.isWorking
            )
        }

        viewModel.result?.let { result ->
            Spacer(modifier = Modifier.height(Spacing.large))
            Text(text = stringResource(R.string.backup_result_title), style = MaterialTheme.typography.titleMedium)
            if (result.importedNames.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.backup_result_imported,
                        result.importedNames.joinToString(", ")
                    )
                )
            }
            if (result.replacedNames.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.backup_result_replaced,
                        result.replacedNames.joinToString(", ")
                    )
                )
            }
            if (result.skippedNames.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.backup_result_skipped,
                        result.skippedNames.joinToString(", ")
                    )
                )
            }
            for (warning in result.warnings) {
                Text(text = warning)
            }
        }

        viewModel.error?.let {
            Spacer(modifier = Modifier.height(Spacing.medium))
            Text(text = it.resolve(), color = MaterialTheme.colorScheme.error)
        }
        viewModel.info?.let {
            Spacer(modifier = Modifier.height(Spacing.medium))
            Text(text = it.resolve())
        }
    }
}
