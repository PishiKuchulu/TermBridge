package com.pishi.termbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Foundation colour scheme: neutral Material 3 defaults. The product
// design system (tokens, terminal palette, brand) is prompt 03's job;
// inventing a brand palette here would pre-empt it.
private val LightScheme = lightColorScheme()
private val DarkScheme = darkColorScheme()

@Composable
fun TermBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TermBridgeTypography,
        content = content
    )
}
