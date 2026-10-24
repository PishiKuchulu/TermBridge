package com.pishi.termbridge.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.HostFormViewModel

/**
 * Add/edit host form, now real (prompt 04). Save writes to Room
 * through the ViewModel/repository; validation errors appear under
 * their field. Credential fields are clearly separated by auth type:
 * password for password auth, key PEM + optional passphrase for key
 * auth. On edit, leaving credential fields blank keeps the stored
 * secret; a passphrase is never loaded back into this form and is
 * never persisted (it is asked again at connection time).
 */
@Composable
fun HostFormScreen(
    viewModel: HostFormViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val saved by viewModel.saved.collectAsState()
    LaunchedEffect(saved) {
        if (saved) onSaved()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(
            text = stringResource(R.string.host_form_note_real),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(Spacing.large))
        Field(
            value = viewModel.name,
            onChange = { viewModel.name = it },
            label = stringResource(R.string.field_host_name),
            placeholder = stringResource(R.string.field_host_name_hint),
            error = viewModel.errors["name"]
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Field(
            value = viewModel.hostname,
            onChange = { viewModel.hostname = it },
            label = stringResource(R.string.field_hostname),
            placeholder = stringResource(R.string.field_hostname_hint),
            error = viewModel.errors["hostname"]
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Field(
            value = viewModel.portText,
            onChange = { viewModel.portText = it },
            label = stringResource(R.string.field_port),
            placeholder = stringResource(R.string.field_port_hint),
            error = viewModel.errors["port"],
            keyboardType = KeyboardType.Number
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Field(
            value = viewModel.username,
            onChange = { viewModel.username = it },
            label = stringResource(R.string.field_username),
            placeholder = stringResource(R.string.field_username_hint),
            error = viewModel.errors["username"]
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(text = stringResource(R.string.field_auth_type))
        // Prompt 14: whole-row selection, 48dp minimum.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouch)
                .clickable { viewModel.authType = AuthType.PASSWORD },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = viewModel.authType == AuthType.PASSWORD,
                onClick = { viewModel.authType = AuthType.PASSWORD }
            )
            Text(text = stringResource(R.string.auth_password))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouch)
                .clickable { viewModel.authType = AuthType.PRIVATE_KEY },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = viewModel.authType == AuthType.PRIVATE_KEY,
                onClick = { viewModel.authType = AuthType.PRIVATE_KEY }
            )
            Text(text = stringResource(R.string.auth_private_key))
        }
        Spacer(modifier = Modifier.height(Spacing.small))
        if (viewModel.authType == AuthType.PASSWORD) {
            OutlinedTextField(
                value = viewModel.password,
                onValueChange = { viewModel.password = it },
                label = { Text(stringResource(R.string.field_password)) },
                placeholder = { Text(stringResource(R.string.field_password_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = viewModel.errors["credential"] != null,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = viewModel.keyPem,
                onValueChange = { viewModel.keyPem = it },
                label = { Text(stringResource(R.string.field_private_key)) },
                placeholder = { Text(stringResource(R.string.field_private_key_hint)) },
                minLines = 4,
                isError = viewModel.errors["credential"] != null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.medium))
            OutlinedTextField(
                value = viewModel.passphrase,
                onValueChange = { viewModel.passphrase = it },
                label = { Text(stringResource(R.string.field_passphrase)) },
                placeholder = { Text(stringResource(R.string.field_passphrase_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(
                text = stringResource(R.string.passphrase_not_stored_note),
                style = MaterialTheme.typography.bodySmall
            )
        }
        viewModel.errors["credential"]?.let {
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(Spacing.medium))
        Field(
            value = viewModel.notes,
            onChange = { viewModel.notes = it },
            label = stringResource(R.string.field_notes),
            placeholder = stringResource(R.string.field_notes_hint),
            error = null
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Field(
            value = viewModel.tags,
            onChange = { viewModel.tags = it },
            label = stringResource(R.string.field_tags),
            placeholder = stringResource(R.string.field_tags_hint),
            error = null
        )
        Spacer(modifier = Modifier.height(Spacing.large))
        PrimaryAction(
            label = stringResource(R.string.action_save),
            onClick = { viewModel.save() },
            enabled = !viewModel.isSaving
        )
        viewModel.saveError?.let {
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = error != null,
        modifier = Modifier.fillMaxWidth()
    )
    error?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
}
