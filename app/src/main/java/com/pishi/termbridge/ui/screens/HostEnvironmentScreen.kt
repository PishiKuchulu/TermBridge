package com.pishi.termbridge.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.EnvVarInfo
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.HostEnvironmentViewModel
import kotlinx.coroutines.launch

/**
 * Prompt 09 per-host environment and paths screen. Everything here
 * does real work: settings save to Room, vars save encrypted, the
 * folder picker is the system SAF picker, and delete asks first.
 *
 * Values are write-only: the list shows a masked marker, never a
 * value, and editing a var requires typing its value again.
 * The folder permission step lives here (not in the ViewModel,
 * which has no ContentResolver): the picked tree URI is
 * persistently permitted BEFORE it is stored, so a stored folder
 * is always a usable folder. If permission cannot be kept, the
 * URI is not stored at all.
 */
@Composable
fun HostEnvironmentScreen(
    viewModel: HostEnvironmentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val envVars by viewModel.envVars.collectAsState()
    var folderError by remember { mutableStateOf<String?>(null) }
    var varDialog by remember { mutableStateOf<EnvVarInfo?>(null) }
    var showAddVar by remember { mutableStateOf(false) }
    var pendingDeleteVar by remember { mutableStateOf<EnvVarInfo?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            folderError = null
            viewModel.setLocalDirectory(uri.toString())
        } catch (_: SecurityException) {
            folderError = context.getString(R.string.env_folder_permission_error)
        }
    }

    if (showAddVar || varDialog != null) {
        EnvVarDialog(
            existing = varDialog,
            onDismiss = {
                showAddVar = false
                varDialog = null
            },
            onSave = { name, value ->
                viewModel.saveEnvVar(varDialog?.id ?: 0L, name, value)
            },
            onSaved = {
                showAddVar = false
                varDialog = null
            }
        )
    }
    val deleteVar = pendingDeleteVar
    if (deleteVar != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteVar = null },
            title = { Text(stringResource(R.string.env_delete_title)) },
            text = { Text(stringResource(R.string.env_delete_body, deleteVar.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEnvVar(deleteVar.id)
                        pendingDeleteVar = null
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteVar = null }) {
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
        if (viewModel.hostName.isNotBlank()) {
            Text(
                text = stringResource(R.string.env_for_host, viewModel.hostName),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(Spacing.small))
        }
        Text(text = stringResource(R.string.env_screen_note))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = stringResource(R.string.env_values_encrypted_note))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = stringResource(R.string.env_apply_next_connection_note))
        Spacer(modifier = Modifier.height(Spacing.large))

        Text(
            text = stringResource(R.string.env_remote_shell_title),
            style = MaterialTheme.typography.titleMedium
        )
        RemoteShell.entries.forEach { shell ->
            // Prompt 14: whole-row selection, 48dp minimum.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.minTouch)
                    .clickable { viewModel.remoteShell = shell },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = viewModel.remoteShell == shell,
                    onClick = { viewModel.remoteShell = shell }
                )
                Text(
                    text = when (shell) {
                        RemoteShell.POSIX -> stringResource(R.string.env_shell_posix)
                        RemoteShell.POWERSHELL -> stringResource(R.string.env_shell_powershell)
                        RemoteShell.CMD -> stringResource(R.string.env_shell_cmd)
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.medium))
        OutlinedTextField(
            value = viewModel.remoteWorkingDirectory,
            onValueChange = { viewModel.remoteWorkingDirectory = it },
            label = { Text(stringResource(R.string.env_remote_dir)) },
            placeholder = { Text(stringResource(R.string.env_remote_dir_hint)) },
            singleLine = true,
            isError = viewModel.settingsErrors["remoteWorkingDirectory"] != null,
            modifier = Modifier.fillMaxWidth()
        )
        viewModel.settingsErrors["remoteWorkingDirectory"]?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(R.string.env_remote_dir_note),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(Spacing.large))

        Text(
            text = stringResource(R.string.env_local_folder_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = viewModel.localDirectoryUri
                ?: stringResource(R.string.env_local_folder_none),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(R.string.env_local_folder_note),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Row {
            SecondaryAction(
                label = stringResource(R.string.env_choose_folder),
                onClick = { folderPicker.launch(null) }
            )
            Spacer(modifier = Modifier.padding(end = Spacing.small))
            if (viewModel.localDirectoryUri != null) {
                SecondaryAction(
                    label = stringResource(R.string.env_clear_folder),
                    onClick = {
                        viewModel.localDirectoryUri?.let { stored ->
                            try {
                                context.contentResolver.releasePersistableUriPermission(
                                    android.net.Uri.parse(stored),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            } catch (_: Exception) {
                            }
                        }
                        viewModel.clearLocalDirectory()
                    }
                )
            }
        }
        folderError?.let {
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        viewModel.settingsErrors["localDirectoryUri"]?.let {
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(Spacing.large))
        PrimaryAction(
            label = stringResource(R.string.action_save),
            onClick = { viewModel.saveSettings() },
            enabled = !viewModel.isSaving
        )
        if (viewModel.settingsSaved) {
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(text = stringResource(R.string.env_settings_saved))
        }
        viewModel.saveError?.let {
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(Spacing.large))
        Text(
            text = stringResource(R.string.env_vars_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(R.string.env_vars_note),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        if (envVars.isEmpty()) {
            Text(
                text = stringResource(R.string.env_vars_empty),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            envVars.forEach { variable ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = variable.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.env_value_masked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { varDialog = variable }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.env_edit_var)
                        )
                    }
                    IconButton(onClick = { pendingDeleteVar = variable }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.env_delete_var)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.medium))
        SecondaryAction(
            label = stringResource(R.string.env_add_var),
            onClick = { showAddVar = true }
        )
    }
}

@Composable
private fun EnvVarDialog(
    existing: EnvVarInfo?,
    onDismiss: () -> Unit,
    onSave: suspend (name: String, value: String) -> String?,
    onSaved: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val blankNameError = stringResource(R.string.env_var_name_blank)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.env_add_var_title
                    else R.string.env_edit_var_title
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.env_var_name)) },
                    placeholder = { Text(stringResource(R.string.env_var_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.env_var_value)) },
                    placeholder = {
                        Text(
                            stringResource(
                                if (existing == null) R.string.env_var_value_hint
                                else R.string.env_var_value_hint_edit
                            )
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The repository validates name/value and
                    // returns the message; an empty value here is
                    // still sent (an empty value is a legitimate
                    // setting), while a blank name is rejected.
                    if (name.isBlank()) {
                        error = blankNameError
                    } else {
                        scope.launch {
                            val saveError = onSave(name, value)
                            if (saveError == null) {
                                onSaved()
                            } else {
                                error = saveError
                            }
                        }
                    }
                }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
