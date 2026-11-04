package com.pishi.termbridge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.KeyFileParser
import com.pishi.termbridge.domain.KeychainEntry
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.KeychainViewModel
import java.io.ByteArrayOutputStream
import java.io.InputStream

private enum class AddMode { PASSWORD, PASTE_KEY, IMPORT, GENERATE }

/**
 * The keychain (item T-01): named passwords and private keys,
 * stored Keystore-encrypted and shared between hosts. Entries
 * are added by typing, pasting, generating, or importing a
 * credential file (item F-01) — a key is only offered for
 * saving after a real load has proven it and its fingerprint
 * is shown.
 */
@Composable
fun KeychainScreen(viewModel: KeychainViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.entries.collectAsState()
    val message by viewModel.message.collectAsState()
    var pendingDelete by remember { mutableStateOf<KeychainEntry?>(null) }

    // One scrolling column for everything: on a short screen
    // the add panel must not push the list (or its own action
    // button) off-screen.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        if (entries.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.keychain_empty_title),
                    body = stringResource(R.string.keychain_empty_body)
                )
            }
        } else {
            items(entries, key = { it.id }) { entry ->
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
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(
                                        if (entry.kind == AuthType.PASSWORD) {
                                            R.string.auth_password
                                        } else {
                                            R.string.auth_private_key
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                entry.fingerprint?.let { fingerprint ->
                                    SelectionContainer {
                                        Text(
                                            text = fingerprint,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.keychain_delete)
                                )
                            }
                        }
                    }
                }
            }
        item {
            HorizontalDivider()
            AddEntryPanel(viewModel = viewModel)
        }
        message?.let { text ->
            item {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.keychain_delete_title)) },
            text = { Text(stringResource(R.string.keychain_delete_body, target.label)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text(stringResource(R.string.keychain_delete)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.delete(target, force = true)
                        pendingDelete = null
                    }) { Text(stringResource(R.string.keychain_delete_force)) }
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }
}

@Composable
private fun AddEntryPanel(viewModel: KeychainViewModel, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(AddMode.PASSWORD) }
    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    val importState by viewModel.importState.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    readBounded(input, KeyFileParser.MAX_FILE_BYTES)
                }
            } catch (e: Exception) {
                null
            }
            if (bytes != null) {
                viewModel.onFilePicked(bytes)
            } else {
                viewModel.onFileReadFailed()
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.keychain_section_add),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(R.string.keychain_field_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        ModeRow(
            selected = mode == AddMode.PASSWORD,
            text = stringResource(R.string.auth_password),
            onClick = { mode = AddMode.PASSWORD }
        )
        ModeRow(
            selected = mode == AddMode.PASTE_KEY,
            text = stringResource(R.string.keychain_mode_paste),
            onClick = { mode = AddMode.PASTE_KEY }
        )
        ModeRow(
            selected = mode == AddMode.IMPORT,
            text = stringResource(R.string.keychain_mode_import),
            onClick = { mode = AddMode.IMPORT }
        )
        ModeRow(
            selected = mode == AddMode.GENERATE,
            text = stringResource(R.string.keychain_mode_generate),
            onClick = { mode = AddMode.GENERATE }
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        when (mode) {
            AddMode.PASSWORD -> {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.field_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                PrimaryAction(
                    label = stringResource(R.string.keychain_add),
                    onClick = {
                        viewModel.addPassword(label, password.toCharArray())
                        password = ""
                    },
                    enabled = label.isNotBlank() && password.isNotEmpty()
                )
            }
            AddMode.PASTE_KEY -> {
                OutlinedTextField(
                    value = pem,
                    onValueChange = { pem = it },
                    label = { Text(stringResource(R.string.field_private_key)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.field_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                PrimaryAction(
                    label = stringResource(R.string.keychain_add),
                    onClick = {
                        viewModel.addKey(
                            label, pem,
                            if (passphrase.isEmpty()) null else passphrase.toCharArray()
                        )
                        pem = ""
                        passphrase = ""
                    },
                    enabled = label.isNotBlank() && pem.isNotBlank()
                )
            }
            AddMode.IMPORT -> {
                PrimaryAction(
                    label = stringResource(R.string.keychain_import_pick),
                    onClick = { picker.launch(arrayOf("*/*")) }
                )
                when (val state = importState) {
                    is KeychainViewModel.ImportState.NeedsPassphrase -> {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource(R.string.keychain_import_passphrase_needed),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        OutlinedTextField(
                            value = importPassphrase,
                            onValueChange = { importPassphrase = it },
                            label = { Text(stringResource(R.string.field_passphrase)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        PrimaryAction(
                            label = stringResource(R.string.keychain_import_unlock),
                            onClick = {
                                viewModel.submitImportPassphrase(importPassphrase.toCharArray())
                                importPassphrase = ""
                            },
                            enabled = importPassphrase.isNotEmpty()
                        )
                    }
                    is KeychainViewModel.ImportState.Ready -> {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource(
                                if (state.isPasswordFile) {
                                    R.string.keychain_import_ready_password
                                } else {
                                    R.string.keychain_import_ready_key
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        state.fingerprint?.let { fingerprint ->
                            SelectionContainer {
                                Text(
                                    text = fingerprint,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(Spacing.small))
                        PrimaryAction(
                            label = stringResource(R.string.keychain_import_save),
                            onClick = { viewModel.saveImport(label) },
                            enabled = label.isNotBlank()
                        )
                        TextButton(onClick = { viewModel.cancelImport() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    is KeychainViewModel.ImportState.Failed -> {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = state.reason,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    KeychainViewModel.ImportState.None -> Unit
                }
            }
            AddMode.GENERATE -> {
                PrimaryAction(
                    label = stringResource(R.string.keychain_generate_ed25519),
                    onClick = { viewModel.generateEd25519(label) },
                    enabled = label.isNotBlank()
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                PrimaryAction(
                    label = stringResource(R.string.keychain_generate_rsa),
                    onClick = { viewModel.generateRsa(label) },
                    enabled = label.isNotBlank()
                )
            }
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouch)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = text)
    }
}

/** Read at most [limit] + 1 bytes: the parser rejects anything
 *  over the limit, so there is no reason to pull a huge file
 *  into memory just to refuse it. */
private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total <= limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - total))
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}
