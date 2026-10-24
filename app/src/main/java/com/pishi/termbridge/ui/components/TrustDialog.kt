package com.pishi.termbridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pishi.termbridge.R
import com.pishi.termbridge.domain.HostKeyVerification
import com.pishi.termbridge.transport.PendingTrust
import com.pishi.termbridge.ui.theme.Spacing

/**
 * Shared host-key trust dialog (prompt 07 extraction; previously
 * inline in SessionScreen). Unknown vs changed wording, endpoint,
 * fingerprint, key type, and previously-trusted fingerprint for
 * CHANGED. Trust is always an explicit tap — dismissing the
 * dialog answers Reject.
 */
@Composable
fun TrustDialog(
    pending: PendingTrust,
    onTrust: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                if (pending.verification == HostKeyVerification.CHANGED) {
                    stringResource(R.string.trust_dialog_title_changed)
                } else {
                    stringResource(R.string.trust_dialog_title_unknown)
                }
            )
        },
        text = {
            Column {
                Text(
                    if (pending.verification == HostKeyVerification.CHANGED) {
                        stringResource(R.string.trust_dialog_body_changed)
                    } else {
                        stringResource(R.string.trust_dialog_body_unknown)
                    }
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(stringResource(R.string.trust_endpoint_label, pending.endpoint))
                Text(stringResource(R.string.trust_fingerprint_label, pending.fingerprint))
                Text(stringResource(R.string.trust_key_type_label, pending.keyType))
                pending.storedFingerprint?.let {
                    Text(stringResource(R.string.trust_stored_label, it))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(stringResource(R.string.action_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.action_reject))
            }
        }
    )
}
