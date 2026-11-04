package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.CustomThemeCodec
import com.pishi.termbridge.domain.TerminalTheme
import com.pishi.termbridge.domain.TerminalThemes
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.theme.Spacing

/**
 * Custom colour schemes (item T-08): create and edit schemes
 * field by field (name, background, foreground, cursor and
 * the 16 ANSI colours as hex), import a scheme from JSON,
 * export one to JSON, and delete schemes. The id of a scheme
 * never changes once created, so renaming is safe. Applying a
 * scheme happens in Settings, where custom schemes join the
 * theme cycle.
 */
@Composable
fun ThemeEditorScreen(
    customThemes: List<TerminalTheme>,
    onUpsert: (TerminalTheme) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf(fieldsOf(TerminalThemes.CLASSIC)) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val invalidMessage = stringResource(R.string.themes_invalid)

    fun load(theme: TerminalTheme, id: String?) {
        editingId = id
        name = theme.label
        fields = fieldsOf(theme)
        message = null
        exportText = null
    }

    fun formTheme(): TerminalTheme? {
        val label = name.trim()
        if (label.isEmpty() || label.length > CustomThemeCodec.MAX_NAME_LENGTH) return null
        if (fields.size != 19) return null
        val colors = fields.map { CustomThemeCodec.parseColor(it) ?: return null }
        return TerminalTheme(
            id = editingId ?: CustomThemeCodec.idFor(label, colors[0], colors.subList(3, 19)),
            label = label,
            background = colors[0],
            foreground = colors[1],
            cursor = colors[2],
            ansi = colors.subList(3, 19)
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.medium)
    ) {
        if (customThemes.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.themes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Spacing.medium)
                )
            }
        }
        items(customThemes, key = { it.id }) { theme ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.padding(Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SwatchStrip(theme)
                    Text(
                        text = theme.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = Spacing.medium).weight(1f)
                    )
                    IconButton(onClick = { load(theme, theme.id) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.themes_edit)
                        )
                    }
                    IconButton(onClick = { onDelete(theme.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.themes_delete)
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(Spacing.medium))
            Text(
                text = stringResource(
                    if (editingId == null) R.string.themes_new else R.string.themes_edit
                ),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.themes_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val labels = fieldLabels()
            fields.forEachIndexed { index, value ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val parsed = CustomThemeCodec.parseColor(value)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (parsed != null) Color(parsed) else Color.Transparent
                            )
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { updated ->
                            fields = fields.toMutableList().also { it[index] = updated }
                        },
                        label = { Text(labels[index]) },
                        singleLine = true,
                        isError = parsed == null,
                        modifier = Modifier
                            .padding(start = Spacing.small)
                            .weight(1f)
                    )
                }
            }
            Text(
                text = stringResource(R.string.themes_ansi_hint),
                style = MaterialTheme.typography.bodySmall
            )
            val form = formTheme()
            if (form != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = Spacing.small)
                ) {
                    SwatchStrip(form)
                    Text(
                        text = "Aa",
                        color = Color(form.foreground),
                        modifier = Modifier
                            .background(Color(form.background))
                            .padding(horizontal = Spacing.small)
                    )
                }
            }
            PrimaryAction(
                label = stringResource(R.string.action_save),
                enabled = form != null,
                onClick = {
                    form?.let {
                        onUpsert(it)
                        load(TerminalThemes.CLASSIC, null)
                        name = ""
                    }
                }
            )
            PrimaryAction(
                label = stringResource(R.string.themes_export),
                enabled = form != null,
                onClick = { form?.let { exportText = CustomThemeCodec.encode(it) } }
            )
            if (exportText != null) {
                OutlinedTextField(
                    value = exportText ?: "",
                    onValueChange = {},
                    readOnly = true,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                PrimaryAction(
                    label = stringResource(R.string.action_copy),
                    onClick = {
                        exportText?.let { clipboard.setText(AnnotatedString(it)) }
                    }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
            Text(
                text = stringResource(R.string.themes_import),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text(stringResource(R.string.themes_import_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryAction(
                label = stringResource(R.string.themes_import),
                enabled = importText.isNotBlank(),
                onClick = {
                    val decoded = CustomThemeCodec.decode(importText)
                    if (decoded == null) {
                        message = invalidMessage
                    } else {
                        load(decoded, decoded.id)
                        importText = ""
                    }
                }
            )
            if (message != null) {
                Text(
                    text = message ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(Spacing.large))
        }
    }
}

private fun fieldsOf(theme: TerminalTheme): List<String> =
    listOf(theme.background, theme.foreground, theme.cursor)
        .plus(theme.ansi)
        .map { CustomThemeCodec.formatColor(it) }

@Composable
private fun fieldLabels(): List<String> {
    val base = listOf(
        stringResource(R.string.themes_background),
        stringResource(R.string.themes_foreground),
        stringResource(R.string.themes_cursor)
    )
    return base + (1..16).map { "ANSI $it" }
}

@Composable
private fun SwatchStrip(theme: TerminalTheme) {
    Row {
        for (color in listOf(theme.background, theme.foreground) + theme.ansi.take(6)) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(Color(color))
            )
        }
    }
}
