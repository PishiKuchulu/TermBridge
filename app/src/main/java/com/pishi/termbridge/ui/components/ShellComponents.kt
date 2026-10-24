package com.pishi.termbridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.theme.Spacing

/**
 * The honesty banner for every shell screen. It must stay on a screen
 * until that screen's feature is actually implemented; removing it
 * early is how a placeholder starts pretending to be a feature.
 */
@Composable
fun ShellBanner(
    detail: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                text = stringResource(R.string.shell_banner),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Empty state: title + body + optional primary action, all wrapping text. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(Spacing.medium))
            PrimaryAction(label = actionLabel, onClick = onAction)
        }
    }
}

/** Primary action button with a guaranteed 48dp minimum touch target. */
@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = Spacing.minTouch)
            .semantics { contentDescription = label }
    ) {
        Text(text = label)
    }
}

/** Secondary action button, same touch-target rule as [PrimaryAction]. */
@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = Spacing.minTouch)
            .semantics { contentDescription = label }
    ) {
        Text(text = label)
    }
}

/** A labelled row for an unavailable capability: name + Unavailable chip text. */
@Composable
fun UnavailableRow(
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.shell_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
