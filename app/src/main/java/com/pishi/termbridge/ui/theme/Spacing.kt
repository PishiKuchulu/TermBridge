package com.pishi.termbridge.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Prompt 03 design tokens: one spacing scale used by every screen,
 * so spacing does not drift per screen. Terminal/file workflows stay
 * plain on purpose — no decorative spacing system beyond this.
 */
data class TermBridgeSpacing(
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val minTouch: Dp = 48.dp
)

val Spacing = TermBridgeSpacing()
