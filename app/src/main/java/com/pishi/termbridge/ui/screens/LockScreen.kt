package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.LockMethod
import com.pishi.termbridge.settings.AppLockStore
import com.pishi.termbridge.settings.UnlockResult
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.theme.Spacing

/**
 * Full-screen overlay shown above the app while it is locked. It is
 * an overlay (not a replacement for the app content) so the
 * navigation state and any live SSH session underneath are untouched.
 * It swallows every touch so nothing beneath can be used.
 */
@Composable
fun LockScreen(
    store: AppLockStore,
    biometricEnabled: Boolean,
    onUnlocked: () -> Unit,
    onBiometric: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var throttledSeconds by remember { mutableStateOf(0) }
    val method by store.method.collectAsState()
    val wrong = stringResource(R.string.lock_wrong)
    val wrongPattern = stringResource(R.string.lock_wrong_pattern)
    val throttledText = pluralStringResource(
        R.plurals.lock_throttled, throttledSeconds, throttledSeconds
    )

    fun onResult(result: UnlockResult, wrongText: String) {
        when (result) {
            UnlockResult.Success -> {
                pin = ""
                pattern = emptyList()
                onUnlocked()
            }
            UnlockResult.Wrong -> {
                pin = ""
                pattern = emptyList()
                message = wrongText
            }
            is UnlockResult.Throttled -> {
                pin = ""
                pattern = emptyList()
                throttledSeconds = (result.retryInMs / 1000).toInt() + 1
                message = null
            }
        }
    }

    val attempt: () -> Unit = { onResult(store.verify(pin), wrong) }
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) onBiometric()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall
            )
            if (method == LockMethod.PATTERN) {
                Text(
                    text = stringResource(R.string.lock_pattern_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.medium)
                )
                PatternGrid(
                    points = pattern,
                    onPointsChange = {
                        pattern = it
                        message = null
                    },
                    onComplete = { drawn ->
                        if (drawn.isNotEmpty()) {
                            onResult(store.verifyPattern(drawn), wrongPattern)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.medium)
                )
            } else {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter { it in '0'..'9' }.take(12)
                        message = null
                    },
                    label = { Text(stringResource(R.string.lock_pin_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { attempt() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.large)
                )
            }
            val shown = message ?: if (throttledSeconds > 0) throttledText else null
            if (shown != null) {
                Text(
                    text = shown,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.small)
                )
            }
            if (method == LockMethod.PIN) {
                PrimaryAction(
                    label = stringResource(R.string.lock_unlock),
                    onClick = { attempt() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.medium)
                )
            }
            if (biometricEnabled) {
                TextButton(onClick = onBiometric) {
                    Text(stringResource(R.string.lock_use_biometric))
                }
            }
        }
    }
}
