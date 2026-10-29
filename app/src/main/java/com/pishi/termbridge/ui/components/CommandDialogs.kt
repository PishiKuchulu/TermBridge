package com.pishi.termbridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.ControlChars
import com.pishi.termbridge.domain.PreparedCommand
import com.pishi.termbridge.ui.theme.Spacing

/**
 * Prompt 08 parameter + preview dialog. This dialog IS the explicit
 * execution action for non-risky commands: tapping Run/Insert here
 * is what submits, and the preview above it shows exactly what
 * will be inserted or sent (values are shell-quoted, see
 * CommandTemplate). The dialog never submits on open, on typing,
 * or on dismiss — only on the action button tap.
 *
 * For risky commands the ViewModel raises a second dialog
 * (RiskyConfirmDialog) after this one; this dialog itself never
 * claims a command is safe.
 */
/**
 * Parameter + preview dialog. [shell] is the connected host's
 * shell (security fix 16, H1): quoting and risky classification
 * are shell-aware, and for a cmd.exe host a button WITH
 * parameters cannot be prepared — prepare() throws, the error
 * shows here, and the action button stays disabled. A
 * parameter-free button prepares on every shell.
 *
 * Prompt 18 (audit-17 R1): [shellConfirmed] false means the
 * host's shell is still the untouched default, so a
 * parameterised button shows a warning above the preview
 * instead of silently applying POSIX quoting.
 *
 * Remediation phase 1: the warning now BLOCKS. When the
 * button has parameters and the shell is unconfirmed, the
 * action button is disabled and the warning explains why
 * and what to do. This disabled button is only the visible
 * mirror of the execution gate: SessionViewModel.actOnPrepared
 * re-checks before anything reaches the terminal, so a
 * bypassed dialog still cannot execute.
 * The secure default is false — a caller that forgets to
 * pass it warns and blocks rather than staying silent.
 */
@Composable
fun ParameterDialog(
    button: CommandButton,
    onDismiss: () -> Unit,
    onSubmit: (PreparedCommand) -> Unit,
    shell: com.pishi.termbridge.domain.RemoteShell =
        com.pishi.termbridge.domain.RemoteShell.POSIX,
    shellConfirmed: Boolean = false
) {
    var values by remember(button.id) {
        mutableStateOf(button.variables.associate { it.name to it.defaultValue })
    }
    val prepared: PreparedCommand? = try {
        CommandTemplate.prepare(button, values, shell, shellConfirmed)
    } catch (e: IllegalArgumentException) {
        null
    }
    val previewError: String? = try {
        CommandTemplate.prepare(button, values, shell, shellConfirmed)
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }
    // Remediation phase 1: the execution gate, mirrored in
    // the UI. Prepared here with the dialog's own shell, so
    // the stale-shell case cannot arise here — only the
    // unconfirmed case can — but the same function decides.
    val submissionBlock = prepared?.let {
        CommandTemplate.blockReason(it, shell, shellConfirmed)
    }
    val actionLabel = when (button.mode) {
        CommandMode.INSERT_ONLY -> stringResource(R.string.commands_action_insert)
        CommandMode.EXECUTE_ONE -> stringResource(R.string.commands_action_run)
        CommandMode.SEQUENCE -> stringResource(R.string.commands_action_run_sequence)
    }
    val noteRes = when (button.mode) {
        CommandMode.INSERT_ONLY -> R.string.commands_param_insert_note
        CommandMode.EXECUTE_ONE -> R.string.commands_param_execute_note
        CommandMode.SEQUENCE -> R.string.commands_param_sequence_note
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.commands_param_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(text = button.label, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(Spacing.small))
                for (variable in button.variables) {
                    OutlinedTextField(
                        value = values[variable.name] ?: "",
                        onValueChange = { new ->
                            values = values + (variable.name to new)
                        },
                        label = {
                            Text(
                                if (variable.required) {
                                    "${variable.name} *"
                                } else {
                                    variable.name
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.small)
                    )
                }
                Text(text = stringResource(noteRes))
                if (CommandTemplate.warnUnconfirmedShell(
                        button.variables.isNotEmpty(), shellConfirmed
                    )
                ) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(
                        text = stringResource(R.string.shell_unconfirmed_warning),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(text = stringResource(R.string.commands_param_preview_label))
                Spacer(modifier = Modifier.height(Spacing.small))
                if (prepared != null) {
                    if (button.mode == CommandMode.SEQUENCE) {
                        prepared.renderedSteps.forEachIndexed { index, step ->
                            Text(
                                text = "${index + 1}. ${ControlChars.visualize(step)}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Text(
                            text = ControlChars.visualize(prepared.rendered),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (prepared.isRisky) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource(R.string.commands_risky_reasons_label),
                            style = MaterialTheme.typography.bodySmall
                        )
                        prepared.riskReasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                previewError?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = prepared != null && submissionBlock == null,
                onClick = { prepared?.let(onSubmit) }
            ) { Text(actionLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Second confirmation for risky commands. It states plainly that
 * detection is a heuristic and can miss dangerous commands, so
 * the user must check the command themselves. Confirming here is
 * the only path to executing a risky command.
 */
@Composable
fun RiskyConfirmDialog(
    prepared: PreparedCommand,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.commands_risky_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = stringResource(R.string.commands_risky_body))
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(text = stringResource(R.string.commands_risky_reasons_label))
                prepared.riskReasons.forEach { reason ->
                    Text(text = "• $reason", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                if (prepared.button.mode == CommandMode.SEQUENCE) {
                    prepared.renderedSteps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. ${ControlChars.visualize(step)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text(
                        text = ControlChars.visualize(prepared.rendered),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.commands_risky_run_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
