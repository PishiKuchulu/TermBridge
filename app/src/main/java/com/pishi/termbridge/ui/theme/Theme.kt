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

// TermBridge design tokens — ported 2026-11-08 (owner's order)
// from his Penpot template "04 FAQ Blue Cards" (UI Studies —
// 14 Pages): deep-navy cards on a pale-blue page, one bright
// blue for headers, pills and actions. Every colour below is
// the template's own hex; the mapping is:
//   page #A9C6E2 · header navy #24344E · card #2B3D5A
//   row #31445F · icon disc #3D5878 · blue #2F7DE1
//   text #FFFFFF · muted #A9BAD3 · glyph tint #BFD6F2
// The dark scheme is the template at night (navy page); the
// light scheme is the template board itself (pale page, navy
// cards). A matching terminal palette ships as the built-in
// "Blue Cards" terminal theme.
private val BlueCardsBlue = Color(0xFF2F7DE1)
private val BlueCardsNavy = Color(0xFF24344E)
private val BlueCardsCard = Color(0xFF2B3D5A)
private val BlueCardsRow = Color(0xFF31445F)
private val BlueCardsDisc = Color(0xFF3D5878)
private val BlueCardsMuted = Color(0xFFA9BAD3)
private val BlueCardsPage = Color(0xFFA9C6E2)
private val BlueCardsGlyph = Color(0xFFBFD6F2)
private val BlueCardsHeaderTint = Color(0xFFD6E6FA)

private val DarkScheme = darkColorScheme(
    primary = BlueCardsBlue,
    onPrimary = Color.White,
    primaryContainer = BlueCardsDisc,
    onPrimaryContainer = BlueCardsHeaderTint,
    secondary = BlueCardsMuted,
    onSecondary = BlueCardsNavy,
    secondaryContainer = BlueCardsRow,
    onSecondaryContainer = Color.White,
    tertiary = BlueCardsGlyph,
    onTertiary = BlueCardsNavy,
    tertiaryContainer = BlueCardsRow,
    onTertiaryContainer = BlueCardsHeaderTint,
    background = BlueCardsNavy,
    onBackground = Color.White,
    surface = BlueCardsCard,
    onSurface = Color.White,
    surfaceVariant = BlueCardsRow,
    onSurfaceVariant = BlueCardsMuted,
    surfaceContainerLowest = BlueCardsNavy,
    surfaceContainerLow = BlueCardsCard,
    surfaceContainer = BlueCardsCard,
    surfaceContainerHigh = BlueCardsRow,
    surfaceContainerHighest = BlueCardsDisc,
    surfaceBright = BlueCardsDisc,
    surfaceDim = BlueCardsNavy,
    inverseSurface = BlueCardsPage,
    inverseOnSurface = BlueCardsNavy,
    inversePrimary = BlueCardsNavy,
    outline = BlueCardsDisc,
    outlineVariant = BlueCardsRow,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000)
)

private val LightScheme = lightColorScheme(
    primary = BlueCardsBlue,
    onPrimary = Color.White,
    primaryContainer = BlueCardsDisc,
    onPrimaryContainer = Color.White,
    secondary = BlueCardsRow,
    onSecondary = Color.White,
    secondaryContainer = BlueCardsCard,
    onSecondaryContainer = Color.White,
    tertiary = BlueCardsNavy,
    onTertiary = Color.White,
    tertiaryContainer = BlueCardsRow,
    onTertiaryContainer = BlueCardsHeaderTint,
    background = BlueCardsPage,
    onBackground = BlueCardsNavy,
    surface = BlueCardsCard,
    onSurface = Color.White,
    surfaceVariant = BlueCardsRow,
    onSurfaceVariant = BlueCardsMuted,
    surfaceContainerLowest = BlueCardsPage,
    surfaceContainerLow = BlueCardsCard,
    surfaceContainer = BlueCardsCard,
    surfaceContainerHigh = BlueCardsRow,
    surfaceContainerHighest = BlueCardsDisc,
    surfaceBright = BlueCardsRow,
    surfaceDim = BlueCardsPage,
    inverseSurface = BlueCardsNavy,
    inverseOnSurface = Color.White,
    inversePrimary = BlueCardsPage,
    outline = BlueCardsDisc,
    outlineVariant = BlueCardsMuted,
    error = Color(0xFFB3261E),
    onError = Color.White
)

// Corner radii follow the template: rows 12, cards 16.
private val TermBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
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
