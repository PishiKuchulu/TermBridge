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
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/** About / licenses shell: only the foundation libraries are listed. */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(text = stringResource(R.string.about_body))
        Spacer(modifier = Modifier.height(Spacing.large))
        Text(text = stringResource(R.string.about_licenses_title))
        Spacer(modifier = Modifier.height(Spacing.small))
        Text(text = stringResource(R.string.about_licenses_body))
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    TermBridgeTheme { AboutScreen() }
}
