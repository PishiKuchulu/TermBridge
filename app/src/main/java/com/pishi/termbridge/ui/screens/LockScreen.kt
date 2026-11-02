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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
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
    var message by remember { mutableStateOf<String?>(null) }
    var throttledSeconds by remember { mutableStateOf(0) }
    val wrong = stringResource(R.string.lock_wrong)
    val throttledText = stringResource(R.string.lock_throttled, throttledSeconds)

    val attempt: () -> Unit = {
        when (val result = store.verify(pin)) {
            UnlockResult.Success -> {
                pin = ""
                onUnlocked()
            }
            UnlockResult.Wrong -> {
                pin = ""
                message = wrong
            }
            is UnlockResult.Throttled -> {
                pin = ""
                throttledSeconds = (result.retryInMs / 1000).toInt() + 1
                message = null
            }
        }
    }
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
            val shown = message ?: if (throttledSeconds > 0) throttledText else null
            if (shown != null) {
                Text(
                    text = shown,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.small)
                )
            }
            PrimaryAction(
                label = stringResource(R.string.lock_unlock),
                onClick = { attempt() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.medium)
            )
            if (biometricEnabled) {
                TextButton(onClick = onBiometric) {
                    Text(stringResource(R.string.lock_use_biometric))
                }
            }
        }
    }
}
