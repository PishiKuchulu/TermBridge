package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.theme.Spacing

/**
 * Item Q-06: first-run onboarding. Three short pages —
 * what the app does, how secrets are handled, and a start
 * action. Fully skippable from every page; shown exactly
 * once (AppSettings.onboardingSeen, set by both Skip and
 * Finish). No account, no network, nothing leaves the
 * device — which is also page two's whole message.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onAddHost: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(0) }
    val titles = listOf(
        R.string.onboarding_welcome_title,
        R.string.onboarding_privacy_title,
        R.string.onboarding_start_title
    )
    val bodies = listOf(
        R.string.onboarding_welcome_body,
        R.string.onboarding_privacy_body,
        R.string.onboarding_start_body
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.large)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onFinish) {
                Text(text = stringResource(R.string.onboarding_skip))
            }
        }
        Spacer(modifier = Modifier.height(Spacing.large))
        Text(
            text = stringResource(titles[page]),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(
            text = stringResource(bodies[page]),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(
                R.string.onboarding_page_indicator, page + 1, titles.size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.medium))
        if (page < titles.size - 1) {
            PrimaryAction(
                label = stringResource(R.string.onboarding_next),
                onClick = { page += 1 },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            PrimaryAction(
                label = stringResource(R.string.onboarding_add_host),
                onClick = onAddHost,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            SecondaryAction(
                label = stringResource(R.string.onboarding_finish),
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
