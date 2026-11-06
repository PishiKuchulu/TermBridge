package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandValidator
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.ui.components.DiscardChangesDialog
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.CommandsViewModel

/**
 * Prompt 08 management screen: per-host command buttons.
 *
 * This screen manages buttons only — it never sends anything to
 * a shell. Execution happens in the Session tab against the live
 * terminal (see SessionScreen's buttons area); this screen's
 * "Open session" button is the bridge, and it is a real
 * navigation action, not a run action. Creating, selecting, or
 * opening this screen never runs a button.
 *
 * Form editing is textarea-based by design: steps are one per
 * line in order, variables are one per line as name or
 * name=default. That is a real editor for single-line shell
 * commands, not a placeholder — multi-line commands belong in
 * the terminal, not in a button step.
 */
@Composable
fun CommandsScreen(
    viewModel: CommandsViewModel,
    onAddHost: () -> Unit,
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hosts by viewModel.hosts.collectAsState()
    val selectedHostId by viewModel.selectedHostId.collectAsState()
    val buttons by viewModel.buttons.collectAsState()
    val error by viewModel.error.collectAsState()
    val formButton by viewModel.formButton.collectAsState()
    var pendingDelete by remember { mutableStateOf<CommandButton?>(null) }

    val form = formButton
    if (form != null) {
        // Prompt 14: the dialog now receives the save error, so
        // a failed save is shown where the user is — the dialog
        // already stayed open on failure (saveButton only clears
        // the form on success), but the error itself only showed
        // on the screen behind it.
        CommandFormDialog(
            initial = form,
            saveError = error,
            onDismiss = { viewModel.closeForm() },
            onSave = { viewModel.saveButton(it) }
        )
    }
    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.commands_delete_title)) },
            text = { Text(stringResource(R.string.commands_delete_body, deleting.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteButton(deleting.id)
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

    // Audit #3 (A-28): the whole screen is one LazyColumn —
    // host rows and button rows are items(), so a long host
    // list or button list no longer composes every row eagerly.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium)
    ) {
        if (hosts.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.session_no_hosts_title),
                    body = stringResource(R.string.session_no_hosts_body),
                    actionLabel = stringResource(R.string.action_add_host),
                    onAction = onAddHost
                )
            }
        } else {
            item {
                Text(text = stringResource(R.string.commands_select_host))
                Spacer(modifier = Modifier.height(Spacing.small))
            }
            // Global snippets (item T-02): a pseudo-entry above the
            // hosts; its buttons are offered in every session.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouch)
                        .clickable {
                            viewModel.selectHost(com.pishi.termbridge.data.GLOBAL_HOST_ID)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedHostId == com.pishi.termbridge.data.GLOBAL_HOST_ID,
                        onClick = {
                            viewModel.selectHost(com.pishi.termbridge.data.GLOBAL_HOST_ID)
                        }
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.commands_global_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.commands_global_sub),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            items(hosts, key = { it.id }) { host ->
                // Prompt 14: whole-row selection, 48dp minimum.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouch)
                        .clickable { viewModel.selectHost(host.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedHostId == host.id,
                        onClick = { viewModel.selectHost(host.id) }
                    )
                    Column {
                        Text(text = host.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${host.username}@${host.hostname}:${host.port}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            val selected = selectedHostId
            if (selected != null) {
                item {
                    Spacer(modifier = Modifier.height(Spacing.large))
                    PrimaryAction(
                        label = stringResource(R.string.commands_add_button),
                        onClick = { viewModel.startAdd() }
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.commands_action_open_session),
                        onClick = onOpenSession
                    )
                    error?.let {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (buttons.isEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(Spacing.large))
                        EmptyState(
                            title = stringResource(R.string.commands_no_buttons_title),
                            body = stringResource(R.string.commands_no_buttons_body)
                        )
                    }
                } else {
                    item {
                        Spacer(modifier = Modifier.height(Spacing.medium))
                        Text(
                            text = stringResource(R.string.commands_sequence_note),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    items(buttons, key = { it.id }) { button ->
                        Column {
                            Spacer(modifier = Modifier.height(Spacing.medium))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = button.label,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val modeText = when (button.mode) {
                                        CommandMode.INSERT_ONLY ->
                                            stringResource(R.string.commands_mode_insert_short)
                                        CommandMode.EXECUTE_ONE ->
                                            stringResource(R.string.commands_mode_execute_short)
                                        CommandMode.SEQUENCE ->
                                            stringResource(R.string.commands_mode_sequence_short)
                                    }
                                    Text(
                                        text = listOfNotNull(
                                            button.category.ifBlank { null },
                                            modeText
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { viewModel.startEdit(button.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(
                                            R.string.commands_action_edit
                                        )
                                    )
                                }
                                IconButton(onClick = { viewModel.duplicateButton(button.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = stringResource(
                                            R.string.commands_action_duplicate
                                        )
                                    )
                                }
                                IconButton(onClick = { viewModel.moveButton(button.id, true) }) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowUpward,
                                        contentDescription = stringResource(
                                            R.string.commands_action_move_up
                                        )
                                    )
                                }
                                IconButton(onClick = { viewModel.moveButton(button.id, false) }) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDownward,
                                        contentDescription = stringResource(
                                            R.string.commands_action_move_down
                                        )
                                    )
                                }
                                IconButton(onClick = { pendingDelete = button }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.action_delete)
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

private fun parseVariables(variablesText: String, requiredText: String): List<CommandVariable> {
    val required = requiredText.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    return variablesText.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val eq = line.indexOf('=')
            if (eq < 0) {
                CommandVariable(name = line, required = line in required)
            } else {
                CommandVariable(
                    name = line.substring(0, eq).trim(),
                    defaultValue = line.substring(eq + 1),
                    required = line.substring(0, eq).trim() in required
                )
            }
        }
}

@Composable
private fun CommandFormDialog(
    initial: CommandButton,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (CommandButton) -> Unit
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var mode by remember(initial.id) { mutableStateOf(initial.mode) }
    var template by remember(initial.id) { mutableStateOf(initial.template) }
    val initialStepsText = remember(initial.id) { initial.steps.joinToString("\n") }
    val initialVariablesText = remember(initial.id) {
        initial.variables.joinToString("\n") { variable ->
            if (variable.defaultValue.isNotEmpty()) {
                "${variable.name}=${variable.defaultValue}"
            } else {
                variable.name
            }
        }
    }
    val initialRequiredText = remember(initial.id) {
        initial.variables.filter { it.required }.joinToString(", ") { it.name }
    }
    var stepsText by remember(initial.id) { mutableStateOf(initialStepsText) }
    var variablesText by remember(initial.id) { mutableStateOf(initialVariablesText) }
    var requiredText by remember(initial.id) { mutableStateOf(initialRequiredText) }
    var fieldErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val isEdit = initial.id != 0L

    // Audit #3 (A-17): dismissing with edits — Back, scrim tap,
    // or the Cancel button, all of which reach onDismiss —
    // asks first. A dialog's onDismissRequest IS its back path,
    // so the guard hooks it instead of a BackHandler.
    val formDirty = label != initial.label || category != initial.category ||
        mode != initial.mode || template != initial.template ||
        stepsText != initialStepsText || variablesText != initialVariablesText ||
        requiredText != initialRequiredText
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val guardedDismiss = {
        if (formDirty) {
            showDiscardConfirm = true
        } else {
            onDismiss()
        }
    }
    if (showDiscardConfirm) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscardConfirm = false
                onDismiss()
            },
            onKeepEditing = { showDiscardConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = guardedDismiss,
        title = {
            Text(
                if (isEdit) {
                    stringResource(R.string.commands_edit_button_title)
                } else {
                    stringResource(R.string.commands_add_button_title)
                }
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = stringResource(R.string.commands_secret_note))
                Spacer(modifier = Modifier.height(Spacing.medium))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.commands_field_label)) },
                    placeholder = { Text(stringResource(R.string.commands_field_label_hint)) },
                    singleLine = true,
                    isError = fieldErrors["label"] != null,
                    modifier = Modifier.fillMaxWidth()
                )
                fieldErrors["label"]?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.commands_field_category)) },
                    placeholder = { Text(stringResource(R.string.commands_field_category_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(text = stringResource(R.string.commands_field_mode))
                // Prompt 14: mode rows are whole-row tappable.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouch)
                        .clickable { mode = CommandMode.INSERT_ONLY },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == CommandMode.INSERT_ONLY,
                        onClick = { mode = CommandMode.INSERT_ONLY }
                    )
                    Text(text = stringResource(R.string.commands_mode_insert))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouch)
                        .clickable { mode = CommandMode.EXECUTE_ONE },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == CommandMode.EXECUTE_ONE,
                        onClick = { mode = CommandMode.EXECUTE_ONE }
                    )
                    Text(text = stringResource(R.string.commands_mode_execute))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouch)
                        .clickable { mode = CommandMode.SEQUENCE },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == CommandMode.SEQUENCE,
                        onClick = { mode = CommandMode.SEQUENCE }
                    )
                    Text(text = stringResource(R.string.commands_mode_sequence))
                }
                Spacer(modifier = Modifier.height(Spacing.small))
                if (mode == CommandMode.SEQUENCE) {
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { stepsText = it },
                        label = { Text(stringResource(R.string.commands_field_steps)) },
                        placeholder = { Text(stringResource(R.string.commands_field_steps_hint)) },
                        minLines = 3,
                        isError = fieldErrors["steps"] != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    fieldErrors["steps"]?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it },
                        label = { Text(stringResource(R.string.commands_field_command)) },
                        placeholder = { Text(stringResource(R.string.commands_field_command_hint)) },
                        minLines = 2,
                        isError = fieldErrors["template"] != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    fieldErrors["template"]?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                OutlinedTextField(
                    value = variablesText,
                    onValueChange = { variablesText = it },
                    label = { Text(stringResource(R.string.commands_field_variables)) },
                    placeholder = { Text(stringResource(R.string.commands_field_variables_hint)) },
                    minLines = 2,
                    isError = fieldErrors["variables"] != null,
                    modifier = Modifier.fillMaxWidth()
                )
                fieldErrors["variables"]?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                OutlinedTextField(
                    value = requiredText,
                    onValueChange = { requiredText = it },
                    label = { Text(stringResource(R.string.commands_field_variable_required)) },
                    placeholder = { Text(stringResource(R.string.commands_field_variable_required_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Prompt 14: a failed repository save is shown
                // here, inside the dialog, not only on the
                // management screen behind it. The dialog stays
                // open (the ViewModel clears the form only on
                // success), so the user can retry without
                // retyping.
                saveError?.let {
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val button = initial.copy(
                        label = label,
                        category = category,
                        mode = mode,
                        template = if (mode == CommandMode.SEQUENCE) "" else template,
                        steps = if (mode == CommandMode.SEQUENCE) {
                            stepsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        } else {
                            emptyList()
                        },
                        variables = parseVariables(variablesText, requiredText)
                    )
                    val issues = CommandValidator.validate(button)
                    if (issues.isNotEmpty()) {
                        fieldErrors = issues.associate { it.field to it.message }
                    } else {
                        onSave(button)
                    }
                }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = guardedDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
