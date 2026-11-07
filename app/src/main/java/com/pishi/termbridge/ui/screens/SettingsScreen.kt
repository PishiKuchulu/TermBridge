package com.pishi.termbridge.ui.screens

import android.content.Intent
import java.util.Locale
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.AppSettings
import com.pishi.termbridge.domain.AppSettingsRules
import com.pishi.termbridge.domain.AppUpdateChecker
import com.pishi.termbridge.domain.GitHubLatestReleaseFetcher
import com.pishi.termbridge.domain.LockMethod
import com.pishi.termbridge.domain.PatternLock
import com.pishi.termbridge.domain.scrollbackMemoryEstimateBytes
import com.pishi.termbridge.domain.TerminalThemes
import com.pishi.termbridge.domain.UpdateCheckResult
import com.pishi.termbridge.ui.LockSettingsUi
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.theme.TermBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, grouped like the screens people already know from other
 * terminal apps: Terminal, Session, Security & privacy, then data and
 * support. The groups are an accordion — exactly one section is
 * expanded at a time — but that is a pure container change: every
 * control here persists AND changes behaviour; nothing is
 * decorative. [onChange] receives a transform so two quick taps
 * can never overwrite each other with a stale copy.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    lock: LockSettingsUi,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onBackup: () -> Unit = {},
    customThemes: List<com.pishi.termbridge.domain.TerminalTheme> = emptyList(),
    onEditCustomThemes: () -> Unit = {},
    onClearCommandHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scrollbackDialogOpen by remember { mutableStateOf(false) }
    var clearHistoryConfirmOpen by remember { mutableStateOf(false) }
    var historyJustCleared by remember { mutableStateOf(false) }
    // Accordion state: the index of the one expanded section
    // (0 Terminal, 1 Session, 2 Lock, 3 Security, 4 Data,
    // 5 App update). Tapping a collapsed header expands it and
    // collapses the others; the expanded header is inert, so
    // exactly one section is open at all times.
    var expandedSection by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(historyJustCleared) {
        if (historyJustCleared) {
            delay(3_000)
            historyJustCleared = false
        }
    }
    if (clearHistoryConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearHistoryConfirmOpen = false },
            title = { Text(stringResource(R.string.settings_clear_history_title)) },
            text = { Text(stringResource(R.string.settings_clear_history_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCommandHistory()
                        clearHistoryConfirmOpen = false
                        historyJustCleared = true
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { clearHistoryConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.small)
    ) {
        AccordionSection(
            title = stringResource(R.string.settings_section_terminal),
            expanded = expandedSection == 0,
            onToggle = { expandedSection = 0 }
        ) {
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
                value = pluralStringResource(
                    R.plurals.settings_scrollback_value,
                    settings.scrollbackLines,
                    settings.scrollbackLines
                ) + " · " + stringResource(
                    R.string.settings_scrollback_estimate,
                    formatBytesShort(scrollbackMemoryEstimateBytes(settings.scrollbackLines))
                ),
                hint = stringResource(R.string.settings_scrollback_hint),
                onClick = { scrollbackDialogOpen = true }
            )
            if (scrollbackDialogOpen) {
                ScrollbackDialog(
                    current = settings.scrollbackLines,
                    onDismiss = { scrollbackDialogOpen = false },
                    onSave = { lines ->
                        onChange { it.copy(scrollbackLines = lines) }
                        scrollbackDialogOpen = false
                    }
                )
            }
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
            ChoiceRow(
                title = stringResource(R.string.settings_terminal_theme),
                value = TerminalThemes.resolve(settings.terminalTheme, customThemes).label,
                onClick = {
                    onChange {
                        val ids = TerminalThemes.all.map { t -> t.id } +
                            customThemes.map { t -> t.id }
                        it.copy(terminalTheme = AppSettingsRules.next(ids, it.terminalTheme))
                    }
                }
            )
            LinkRow(stringResource(R.string.settings_custom_themes), onEditCustomThemes)
            ChoiceRow(
                title = stringResource(R.string.settings_cursor_style),
                value = stringResource(
                    when (settings.cursorStyle) {
                        AppSettings.CURSOR_STYLE_UNDERLINE -> R.string.settings_cursor_underline
                        AppSettings.CURSOR_STYLE_BAR -> R.string.settings_cursor_bar
                        else -> R.string.settings_cursor_block
                    }
                ),
                hint = stringResource(R.string.settings_cursor_hint),
                onClick = {
                    onChange {
                        it.copy(
                            cursorStyle = AppSettingsRules.next(
                                AppSettings.CURSOR_STYLES, it.cursorStyle
                            )
                        )
                    }
                }
            )
            SwitchRow(
                title = stringResource(R.string.settings_cursor_blink),
                hint = stringResource(R.string.settings_cursor_blink_hint),
                checked = settings.cursorBlink,
                onCheckedChange = { checked -> onChange { it.copy(cursorBlink = checked) } }
            )
            ChoiceRow(
                title = stringResource(R.string.settings_scroll_sensitivity),
                value = stringResource(
                    R.string.settings_scroll_sensitivity_value,
                    settings.scrollSensitivity.toString()
                ),
                onClick = {
                    onChange {
                        it.copy(
                            scrollSensitivity = AppSettingsRules.next(
                                AppSettings.SCROLL_SENSITIVITIES, it.scrollSensitivity
                            )
                        )
                    }
                }
            )
            // Hardware actions (item T-19), both default to Disabled.
            ChoiceRow(
                title = stringResource(R.string.settings_volume_keys),
                value = stringResource(
                    when (settings.volumeKeyAction) {
                        com.pishi.termbridge.domain.VolumeKeyAction.FONT_SIZE.id ->
                            R.string.volume_action_font
                        com.pishi.termbridge.domain.VolumeKeyAction.CTRL_MODIFIER.id ->
                            R.string.volume_action_ctrl
                        com.pishi.termbridge.domain.VolumeKeyAction.SCROLL.id ->
                            R.string.volume_action_scroll
                        com.pishi.termbridge.domain.VolumeKeyAction.SEND_KEY.id ->
                            R.string.volume_action_key
                        else -> R.string.volume_action_disabled
                    }
                ),
                hint = stringResource(R.string.settings_volume_keys_hint),
                onClick = {
                    onChange {
                        it.copy(
                            volumeKeyAction = AppSettingsRules.next(
                                com.pishi.termbridge.domain.VolumeKeyAction.IDS,
                                it.volumeKeyAction
                            )
                        )
                    }
                }
            )
            ChoiceRow(
                title = stringResource(R.string.settings_shake),
                value = stringResource(
                    when (settings.shakeAction) {
                        com.pishi.termbridge.domain.ShakeAction.CLEAR_SCREEN.id ->
                            R.string.shake_action_clear
                        com.pishi.termbridge.domain.ShakeAction.DISCONNECT.id ->
                            R.string.shake_action_disconnect
                        else -> R.string.shake_action_disabled
                    }
                ),
                hint = stringResource(R.string.settings_shake_hint),
                onClick = {
                    onChange {
                        it.copy(
                            shakeAction = AppSettingsRules.next(
                                com.pishi.termbridge.domain.ShakeAction.IDS,
                                it.shakeAction
                            )
                        )
                    }
                }
            )
            SwitchRow(
                title = stringResource(R.string.settings_bell_haptic),
                hint = stringResource(R.string.settings_bell_haptic_hint),
                checked = settings.bellHaptic,
                onCheckedChange = { checked -> onChange { it.copy(bellHaptic = checked) } }
            )
            SwitchRow(
                title = stringResource(R.string.settings_extra_keys_bar),
                hint = stringResource(R.string.settings_extra_keys_bar_hint),
                checked = settings.showExtraKeysBar,
                onCheckedChange = { checked -> onChange { it.copy(showExtraKeysBar = checked) } }
            )
            SwitchRow(
                title = stringResource(R.string.settings_autocomplete),
                hint = stringResource(R.string.settings_autocomplete_hint),
                checked = settings.autocompleteEnabled,
                onCheckedChange = { checked ->
                    // Audit #3 (A-12): turning suggestions off
                    // also purges the remembered commands — off
                    // must mean nothing is kept, not just that
                    // nothing new is learned.
                    if (!checked) onClearCommandHistory()
                    onChange { it.copy(autocompleteEnabled = checked) }
                }
            )
            LinkRow(
                stringResource(R.string.settings_clear_history),
                onClick = { clearHistoryConfirmOpen = true }
            )
            if (historyJustCleared) {
                Text(
                    text = stringResource(R.string.settings_history_cleared),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = Spacing.medium, vertical = 8.dp
                    )
                )
            }
            SwitchRow(
                title = stringResource(R.string.settings_detect_os),
                hint = stringResource(R.string.settings_detect_os_hint),
                checked = settings.detectOsOnConnect,
                onCheckedChange = { checked -> onChange { it.copy(detectOsOnConnect = checked) } }
            )
            SwitchRow(
                title = stringResource(R.string.settings_bell_sound),
                hint = stringResource(R.string.settings_bell_sound_hint),
                checked = settings.bellSound,
                onCheckedChange = { checked -> onChange { it.copy(bellSound = checked) } }
            )
            SwitchRow(
                title = stringResource(R.string.settings_bell_flash),
                hint = stringResource(R.string.settings_bell_flash_hint),
                checked = settings.bellFlash,
                onCheckedChange = { checked -> onChange { it.copy(bellFlash = checked) } }
            )
            SwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                hint = stringResource(R.string.settings_keep_screen_on_hint),
                checked = settings.keepScreenOn,
                onCheckedChange = { checked -> onChange { it.copy(keepScreenOn = checked) } }
            )
        }

        AccordionSection(
            title = stringResource(R.string.settings_section_session),
            expanded = expandedSection == 1,
            onToggle = { expandedSection = 1 }
        ) {
            ChoiceRow(
                title = stringResource(R.string.settings_keepalive),
                value = if (settings.keepAliveSeconds == 0) {
                    stringResource(R.string.settings_keepalive_off)
                } else {
                    pluralStringResource(
                        R.plurals.settings_keepalive_value,
                        settings.keepAliveSeconds,
                        settings.keepAliveSeconds
                    )
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
            ChoiceRow(
                title = stringResource(R.string.settings_keepalive_misses),
                value = stringResource(
                    R.string.settings_keepalive_misses_value, settings.keepAliveMaxMisses
                ),
                hint = stringResource(R.string.settings_keepalive_misses_hint),
                onClick = {
                    onChange {
                        it.copy(
                            keepAliveMaxMisses = AppSettingsRules.next(
                                AppSettings.KEEPALIVE_MAX_MISS_CHOICES, it.keepAliveMaxMisses
                            )
                        )
                    }
                }
            )
        }

        AccordionSection(
            title = stringResource(R.string.settings_section_lock),
            expanded = expandedSection == 2,
            onToggle = { expandedSection = 2 }
        ) {
            LockSection(lock)
        }

        AccordionSection(
            title = stringResource(R.string.settings_section_security),
            expanded = expandedSection == 3,
            onToggle = { expandedSection = 3 }
        ) {
            SwitchRow(
                title = stringResource(R.string.settings_block_screenshots),
                hint = stringResource(R.string.settings_block_screenshots_hint),
                checked = settings.blockScreenshots,
                onCheckedChange = { checked -> onChange { it.copy(blockScreenshots = checked) } }
            )
        }

        AccordionSection(
            title = stringResource(R.string.settings_section_data),
            expanded = expandedSection == 4,
            onToggle = { expandedSection = 4 }
        ) {
            LinkRow(stringResource(R.string.action_backup), onBackup)
            LinkRow(stringResource(R.string.action_diagnostics), onDiagnostics)
            LinkRow(stringResource(R.string.action_about), onAbout)
            Spacer(modifier = Modifier.height(Spacing.large))
        }

        AccordionSection(
            title = stringResource(R.string.settings_section_update),
            expanded = expandedSection == 5,
            onToggle = { expandedSection = 5 }
        ) {
            AppUpdateBlock()
        }
    }
}

/** "1.5 MB" / "320 KB" for the scrollback estimate.
 *  Locale.ROOT (audit #7 AUD-007): the decimal separator and
 *  digits must not follow the device locale. */
private fun formatBytesShort(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.ROOT, "%d KB", bytes / 1_000L)
    else -> "$bytes B"
}

/**
 * Custom scrollback editor (item T-23): any value inside the
 * safe bounds, the memory estimate updating as you type, and
 * the old presets as one-tap shortcuts.
 */
@Composable
private fun ScrollbackDialog(
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null &&
        parsed in AppSettings.MIN_SCROLLBACK..AppSettings.MAX_SCROLLBACK
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_scrollback)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(6)
                    },
                    label = { Text(stringResource(R.string.settings_scrollback)) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_scrollback_bounds,
                        AppSettings.MAX_SCROLLBACK,
                        AppSettings.MIN_SCROLLBACK,
                        AppSettings.MAX_SCROLLBACK
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (parsed != null) {
                    Text(
                        text = stringResource(
                            R.string.settings_scrollback_estimate,
                            formatBytesShort(scrollbackMemoryEstimateBytes(parsed))
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row {
                    for (preset in AppSettings.SCROLLBACK_CHOICES) {
                        TextButton(onClick = { text = preset.toString() }) {
                            Text(preset.toString())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { parsed?.let(onSave) }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * One accordion panel: a tappable header (section title +
 * chevron) and, only while [expanded], the section content.
 * The parent owns which section is expanded, so exactly one
 * panel is open at a time.
 */
@Composable
private fun AccordionSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) {
                    R.string.settings_collapse_section
                } else {
                    R.string.settings_expand_section
                }
            ),
            tint = MaterialTheme.colorScheme.primary
        )
    }
    if (expanded) {
        Column(content = content)
    }
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Done(val result: UpdateCheckResult) : UpdateUiState
}

/**
 * The App update section body: the running version (read from
 * PackageManager, never hardcoded), a manual check button and
 * its result. Manual only — no background polling — and an
 * available update is offered as a browser download link; the
 * app holds no install permission and never fetches the APK.
 */
@Composable
private fun AppUpdateBlock() {
    val context = LocalContext.current
    val currentVersion = remember {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    val scope = rememberCoroutineScope()
    SettingRow {
        TitleAndHint(
            stringResource(R.string.update_current_version), null, Modifier.weight(1f)
        )
        Text(
            text = currentVersion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    LinkRow(
        title = stringResource(R.string.update_check_action),
        onClick = {
            if (updateState != UpdateUiState.Checking) {
                updateState = UpdateUiState.Checking
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AppUpdateChecker.check(currentVersion, GitHubLatestReleaseFetcher())
                    }
                    updateState = UpdateUiState.Done(result)
                }
            }
        }
    )
    when (val state = updateState) {
        UpdateUiState.Idle -> Unit
        UpdateUiState.Checking ->
            UpdateStatusText(stringResource(R.string.update_checking))
        is UpdateUiState.Done -> when (val result = state.result) {
            UpdateCheckResult.UpToDate ->
                UpdateStatusText(stringResource(R.string.update_up_to_date))
            UpdateCheckResult.CheckFailed ->
                UpdateStatusText(stringResource(R.string.update_check_failed))
            is UpdateCheckResult.UpdateAvailable -> {
                UpdateStatusText(
                    stringResource(R.string.update_available, result.tag)
                )
                // Audit #6 TB-012: the download is a plain
                // browser hand-off with no hash or signature
                // check on our side; say so where the user
                // acts on it.
                UpdateStatusText(
                    stringResource(R.string.update_unverified_note)
                )
                LinkRow(
                    title = stringResource(R.string.update_download_action),
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                            )
                        } catch (_: Exception) {
                            // No browser to open it with — the
                            // version line above still tells
                            // the user an update exists.
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun UpdateStatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Spacing.medium, vertical = 12.dp)
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
            lock = LockSettingsUi(false, false, false, { true }, { true }, {}),
            onChange = {},
            onDiagnostics = {},
            onAbout = {},
            onBackup = {}
        )
    }
}

@Composable
private fun LockSection(lock: LockSettingsUi) {
    var showSet by remember { mutableStateOf(false) }
    var showSetPattern by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    SwitchRow(
        title = stringResource(R.string.settings_pin_lock),
        hint = if (lock.enabled) {
            stringResource(R.string.settings_pin_lock_on)
        } else {
            stringResource(R.string.settings_pin_lock_note)
        },
        checked = lock.enabled,
        onCheckedChange = { wantOn -> if (wantOn) showSet = true else showDisable = true }
    )
    if (lock.enabled) {
        SwitchRow(
            title = stringResource(R.string.settings_biometric),
            hint = if (lock.biometricAvailable) null else stringResource(R.string.settings_biometric_unavailable),
            checked = lock.biometricOn && lock.biometricAvailable,
            onCheckedChange = { on -> if (lock.biometricAvailable) lock.setBiometric(on) }
        )
        // Item T-18: the other credential method, the delay,
        // the recents cover and lock-now.
        if (lock.method == LockMethod.PIN) {
            LinkRow(
                title = stringResource(R.string.lock_change_pin),
                onClick = { showSet = true }
            )
            LinkRow(
                title = stringResource(R.string.lock_set_pattern_action),
                onClick = { showSetPattern = true }
            )
        } else {
            LinkRow(
                title = stringResource(R.string.lock_change_pattern),
                onClick = { showSetPattern = true }
            )
            LinkRow(
                title = stringResource(R.string.lock_set_pin_action),
                onClick = { showSet = true }
            )
        }
        ChoiceRow(
            title = stringResource(R.string.settings_auto_lock),
            value = stringResource(
                when (lock.autoLockDelayMs) {
                    0L -> R.string.lock_delay_0
                    60_000L -> R.string.lock_delay_60
                    300_000L -> R.string.lock_delay_300
                    else -> R.string.lock_delay_30
                }
            ),
            onClick = {
                val choices = lock.autoLockChoicesMs
                if (choices.isNotEmpty()) {
                    val idx = choices.indexOf(lock.autoLockDelayMs)
                    lock.setAutoLockDelay(choices[(idx + 1).mod(choices.size)])
                }
            }
        )
        SwitchRow(
            title = stringResource(R.string.settings_cover_recents),
            hint = stringResource(R.string.settings_cover_recents_hint),
            checked = lock.coverRecents,
            onCheckedChange = { on -> lock.setCoverRecents(on) }
        )
        LinkRow(
            title = stringResource(R.string.lock_now),
            onClick = { lock.lockNow() }
        )
    }
    if (showSet) {
        var pin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val invalidText = stringResource(R.string.lock_pin_invalid)
        val differText = stringResource(R.string.lock_pins_differ)
        AlertDialog(
            onDismissRequest = { showSet = false },
            title = { Text(stringResource(R.string.lock_set_pin_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c in '0'..'9' }.take(12) },
                        label = { Text(stringResource(R.string.lock_set_pin_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.filter { c -> c in '0'..'9' }.take(12) },
                        label = { Text(stringResource(R.string.lock_confirm_pin_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (error != null) {
                        Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pin != confirm -> error = differText
                        !lock.setPin(pin) -> error = invalidText
                        else -> showSet = false
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSet = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (showSetPattern) {
        var first by remember { mutableStateOf<List<Int>?>(null) }
        var drawn by remember { mutableStateOf<List<Int>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        val tooShortText = stringResource(R.string.lock_pattern_too_short)
        val differText2 = stringResource(R.string.lock_patterns_differ)
        AlertDialog(
            onDismissRequest = { showSetPattern = false },
            title = { Text(stringResource(R.string.lock_set_pattern_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            if (first == null) {
                                R.string.lock_set_pattern_first
                            } else {
                                R.string.lock_set_pattern_confirm
                            }
                        )
                    )
                    PatternGrid(
                        points = drawn,
                        onPointsChange = { drawn = it },
                        onComplete = { completed ->
                            when {
                                !PatternLock.isValid(completed) -> {
                                    error = tooShortText
                                    drawn = emptyList()
                                }
                                first == null -> {
                                    first = completed
                                    drawn = emptyList()
                                    error = null
                                }
                                completed == first -> {
                                    if (lock.setPattern(completed)) {
                                        showSetPattern = false
                                    } else {
                                        error = tooShortText
                                        first = null
                                        drawn = emptyList()
                                    }
                                }
                                else -> {
                                    error = differText2
                                    first = null
                                    drawn = emptyList()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSetPattern = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showDisable) {
        var current by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        val wrongText = stringResource(R.string.lock_wrong)
        AlertDialog(
            onDismissRequest = { showDisable = false },
            title = { Text(stringResource(R.string.lock_disable_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.lock_disable_body))
                    if (lock.method == LockMethod.PATTERN) {
                        var drawn by remember { mutableStateOf<List<Int>>(emptyList()) }
                        val wrongPatternText = stringResource(R.string.lock_wrong_pattern)
                        PatternGrid(
                            points = drawn,
                            onPointsChange = {
                                drawn = it
                                wrong = false
                            },
                            onComplete = { completed ->
                                if (completed.isNotEmpty()) {
                                    if (lock.disableWithPattern(completed)) {
                                        showDisable = false
                                    } else {
                                        wrong = true
                                        drawn = emptyList()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (wrong) {
                            Text(
                                text = wrongPatternText,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = current,
                            onValueChange = {
                                current = it.filter { c -> c in '0'..'9' }.take(12)
                                wrong = false
                            },
                            label = { Text(stringResource(R.string.lock_pin_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                        if (wrong) Text(text = wrongText, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                // With a pattern, drawing the correct pattern in
                // the grid above turns the lock off directly; the
                // confirm button only exists for the PIN path.
                if (lock.method != LockMethod.PATTERN) {
                    TextButton(onClick = {
                        if (lock.disable(current)) showDisable = false else wrong = true
                    }) { Text(stringResource(R.string.action_confirm)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisable = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
