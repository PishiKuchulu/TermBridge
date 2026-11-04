package com.pishi.termbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// TermBridge design tokens. A calm deep-slate surface family with a
// single teal accent: low glare for long terminal sessions, enough
// contrast (primary on background is above 7:1 in the dark scheme) and
// a clear hierarchy (background < surface < surfaceVariant) so lists,
// cards and settings rows read as layers instead of a flat grey.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4DD0C4),
    onPrimary = Color(0xFF00302C),
    primaryContainer = Color(0xFF0F4A45),
    onPrimaryContainer = Color(0xFFB2F2EB),
    secondary = Color(0xFF8FA3C7),
    onSecondary = Color(0xFF0F1A2E),
    secondaryContainer = Color(0xFF26324A),
    onSecondaryContainer = Color(0xFFD6E0F5),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF3A2400),
    background = Color(0xFF0F1420),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF151B2B),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E2638),
    onSurfaceVariant = Color(0xFFA9B3C7),
    // Item Q-04: raised from 0xFF4A5570 (2.31:1 on the
    // dark surface) to clear the 3:1 non-text boundary
    // guidance — 3.46:1 on surface, 3.71:1 on background.
    outline = Color(0xFF64708C),
    outlineVariant = Color(0xFF2A3348),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F2EB),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A5E85),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E0F5),
    onSecondaryContainer = Color(0xFF0F1A2E),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF151B2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151B2B),
    surfaceVariant = Color(0xFFE3E8F1),
    onSurfaceVariant = Color(0xFF475069),
    outline = Color(0xFF7A859C),
    outlineVariant = Color(0xFFD3DAE8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val TermBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TermBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TermBridgeTypography,
        shapes = TermBridgeShapes,
        content = content
    )
}
