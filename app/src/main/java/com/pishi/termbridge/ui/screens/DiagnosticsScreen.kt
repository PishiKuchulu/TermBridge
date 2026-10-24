package com.pishi.termbridge.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/**
 * Diagnostics: only real, device-readable values are shown (model,
 * Android version, SDK level, app version string, live-session
 * state passed in from the nav graph). No hostname, credential,
 * or command text is ever shown here.
 *
 * Prompt 14 fix: this screen previously hardcoded "no session
 * feature exists yet", which stopped being true in prompt 06.
 * The live-session flag now comes from the activity-scoped
 * session ViewModel, so the line can never go stale again; the
 * default (false) keeps the preview honest for the no-session
 * case.
 */
@Composable
fun DiagnosticsScreen(
    hasLiveSession: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(text = stringResource(R.string.diagnostics_app_version))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(
                R.string.diagnostics_device,
                Build.MANUFACTURER,
                Build.MODEL
            )
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = stringResource(
                R.string.diagnostics_android,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT
            )
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(
            text = if (hasLiveSession) {
                stringResource(R.string.diagnostics_live_session)
            } else {
                stringResource(R.string.diagnostics_no_sessions)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DiagnosticsScreenPreview() {
    TermBridgeTheme { DiagnosticsScreen() }
}
