package com.pishi.termbridge.ui.screens

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
import com.pishi.termbridge.ui.components.ShellBanner
import com.pishi.termbridge.ui.components.UnavailableRow
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/**
 * Remote desktop shell, updated by prompt 12.
 *
 * RDP is no longer a generic "later phase" row: the prompt-12
 * proof-of-concept gate FAILED in this environment (no NDK, no CI
 * build, no Windows endpoint — see docs/RDP_NOTES.md), so the
 * screen states the blocker and what would unblock it instead of
 * implying RDP is merely unbuilt. No connect button exists here:
 * a button that cannot connect would be a decorative control.
 * VNC is untouched by prompt 12; its own POC gate runs in
 * prompt 13 — and its verdict is split, stated exactly below
 * rather than as a single "available / blocked" label:
 * - The pure-Kotlin RFB protocol core IS implemented in
 *   prompt 13 (handshake, None + VNC Authentication, Raw /
 *   CopyRect / RRE / CoRRE / Hextile decoding, pointer/key
 *   input messages). It has never talked to a real server.
 * - A VNC SESSION is still blocked: the pack requires proof
 *   against a controlled compatible server before full
 *   integration, and this environment has no such server, no
 *   device, and no CI run. So no connect button exists here:
 *   a button that has never connected would be a decorative
 *   control, and a session UI around an unproven connection
 *   would be the fake this gate exists to prevent.
 */
@Composable
fun RemoteDesktopScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        ShellBanner(detail = stringResource(R.string.desktop_body))
        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.desktop_body))
        Spacer(modifier = Modifier.height(Spacing.large))
        UnavailableRow(label = stringResource(R.string.desktop_rdp))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = stringResource(R.string.desktop_rdp_blocked))
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(text = stringResource(R.string.desktop_rdp_library))
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(text = stringResource(R.string.desktop_rdp_unblock))
        Spacer(modifier = Modifier.height(Spacing.large))
        UnavailableRow(label = stringResource(R.string.desktop_vnc))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = stringResource(R.string.desktop_vnc_core))
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(text = stringResource(R.string.desktop_vnc_blocked))
        Spacer(modifier = Modifier.height(Spacing.medium))
        Text(text = stringResource(R.string.desktop_vnc_unblock))
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteDesktopScreenPreview() {
    TermBridgeTheme { RemoteDesktopScreen() }
}
