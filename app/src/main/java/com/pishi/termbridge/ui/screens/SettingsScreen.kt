package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AppSettings
import com.pishi.termbridge.domain.AppSettingsRules
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/**
 * Settings, grouped like the screens people already know from other
 * terminal apps: Terminal, Session, Security & privacy, then data and
 * support. Every control here persists AND changes behaviour; nothing
 * is decorative. [onChange] receives a transform so two quick taps
 * can never overwrite each other with a stale copy.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onBackup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.small)
    ) {
        SectionHeader(stringResource(R.string.settings_section_terminal))
        StepperRow(
            title = stringResource(R.string.settings_font_size),
            value = stringResource(R.string.settings_font_size_value, settings.terminalFontSp),
            onMinus = { onChange { it.copy(terminalFontSp = it.terminalFontSp - 1) } },
            onPlus = { onChange { it.copy(terminalFontSp = it.terminalFontSp + 1) } },
            minusLabel = stringResource(R.string.settings_font_smaller),
            plusLabel = stringResource(R.string.settings_font_larger)
        )
        ChoiceRow(
            title = stringResource(R.string.settings_scrollback),
            value = stringResource(R.string.settings_scrollback_value, settings.scrollbackLines),
            onClick = {
                onChange {
                    it.copy(
                        scrollbackLines = AppSettingsRules.next(
                            AppSettings.SCROLLBACK_CHOICES, it.scrollbackLines
                        )
                    )
                }
            }
        )
        ChoiceRow(
            title = stringResource(R.string.settings_terminal_type),
            value = settings.terminalType,
            hint = stringResource(R.string.settings_terminal_type_hint),
            onClick = {
                onChange {
                    it.copy(
                        terminalType = AppSettingsRules.next(
                            AppSettings.TERMINAL_TYPES, it.terminalType
                        )
                    )
                }
            }
        )
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            hint = stringResource(R.string.settings_keep_screen_on_hint),
            checked = settings.keepScreenOn,
            onCheckedChange = { checked -> onChange { it.copy(keepScreenOn = checked) } }
        )

        SectionHeader(stringResource(R.string.settings_section_session))
        ChoiceRow(
            title = stringResource(R.string.settings_keepalive),
            value = if (settings.keepAliveSeconds == 0) {
                stringResource(R.string.settings_keepalive_off)
            } else {
                stringResource(R.string.settings_keepalive_value, settings.keepAliveSeconds)
            },
            hint = stringResource(R.string.settings_keepalive_hint),
            onClick = {
                onChange {
                    it.copy(
                        keepAliveSeconds = AppSettingsRules.next(
                            AppSettings.KEEPALIVE_CHOICES, it.keepAliveSeconds
                        )
                    )
                }
            }
        )

        SectionHeader(stringResource(R.string.settings_section_security))
        SwitchRow(
            title = stringResource(R.string.settings_block_screenshots),
            hint = stringResource(R.string.settings_block_screenshots_hint),
            checked = settings.blockScreenshots,
            onCheckedChange = { checked -> onChange { it.copy(blockScreenshots = checked) } }
        )

        SectionHeader(stringResource(R.string.settings_section_data))
        LinkRow(stringResource(R.string.action_backup), onBackup)
        LinkRow(stringResource(R.string.action_diagnostics), onDiagnostics)
        LinkRow(stringResource(R.string.action_about), onAbout)
        Spacer(modifier = Modifier.height(Spacing.large))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Spacing.medium, end = Spacing.medium,
            top = Spacing.large, bottom = Spacing.small
        )
    )
}

@Composable
private fun SettingRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TitleAndHint(title: String, hint: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingRow(modifier = Modifier.clickable { onCheckedChange(!checked) }) {
        TitleAndHint(title, hint, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    hint: String? = null
) {
    SettingRow(modifier = Modifier.clickable(onClick = onClick)) {
        TitleAndHint(title, hint, Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusLabel: String,
    plusLabel: String
) {
    SettingRow {
        TitleAndHint(title, null, Modifier.weight(1f))
        TextButton(
            onClick = onMinus,
            modifier = Modifier.semantics { contentDescription = minusLabel }
        ) { Text(text = "\u2212") }
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = onPlus,
            modifier = Modifier.semantics { contentDescription = plusLabel }
        ) { Text(text = "+") }
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    SettingRow(modifier = Modifier.clickable(onClick = onClick)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    TermBridgeTheme {
        SettingsScreen(
            settings = AppSettings(),
            onChange = {},
            onDiagnostics = {},
            onAbout = {},
            onBackup = {}
        )
    }
}
