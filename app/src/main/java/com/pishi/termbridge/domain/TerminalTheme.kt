package com.pishi.termbridge.domain

/**
 * A terminal colour scheme as plain ARGB values (kept out of Compose
 * types so it is unit-testable). [ansi] holds the 16 standard colours.
 */
data class TerminalTheme(
    val id: String,
    val label: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val ansi: List<Long>
)

object TerminalThemes {
    val CLASSIC = TerminalTheme(
        id = "classic", label = "Classic dark",
        background = 0xFF000000, foreground = 0xFFE6E6E6, cursor = 0xFFE6E6E6,
        ansi = listOf(
            0xFF000000, 0xFFCC0000, 0xFF00AA00, 0xFFCCAA00, 0xFF3465A4, 0xFFCC00CC, 0xFF00AAAA, 0xFFE6E6E6,
            0xFF7F7F7F, 0xFFFF5555, 0xFF55FF55, 0xFFFFFF55, 0xFF6699FF, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFFFFF
        )
    )
    val SOLARIZED = TerminalTheme(
        id = "solarized", label = "Solarized dark",
        background = 0xFF002B36, foreground = 0xFF93A1A1, cursor = 0xFF93A1A1,
        ansi = listOf(
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF586E75, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3
        )
    )
    val LIGHT = TerminalTheme(
        id = "light", label = "Light",
        background = 0xFFFAFAFA, foreground = 0xFF222222, cursor = 0xFF222222,
        ansi = listOf(
            0xFF222222, 0xFFB3261E, 0xFF1B7F3A, 0xFF8A6D00, 0xFF1A56C4, 0xFF9C27B0, 0xFF00796B, 0xFFBDBDBD,
            0xFF616161, 0xFFD32F2F, 0xFF2E7D32, 0xFFA67C00, 0xFF1976D2, 0xFFAB47BC, 0xFF00897B, 0xFF424242
        )
    )
    val all: List<TerminalTheme> = listOf(CLASSIC, SOLARIZED, LIGHT)
    fun byId(id: String): TerminalTheme = all.firstOrNull { it.id == id } ?: CLASSIC
}
