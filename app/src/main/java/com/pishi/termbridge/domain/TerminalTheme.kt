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
    val DRACULA = TerminalTheme(
        id = "dracula", label = "Dracula",
        background = 0xFF282A36, foreground = 0xFFF8F8F2, cursor = 0xFFF8F8F2,
        ansi = listOf(
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF
        )
    )
    val NORD = TerminalTheme(
        id = "nord", label = "Nord",
        background = 0xFF2E3440, foreground = 0xFFD8DEE9, cursor = 0xFFD8DEE9,
        ansi = listOf(
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4
        )
    )
    val GRUVBOX = TerminalTheme(
        id = "gruvbox", label = "Gruvbox dark",
        background = 0xFF282828, foreground = 0xFFEBDBB2, cursor = 0xFFEBDBB2,
        ansi = listOf(
            0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2
        )
    )
    val MONOKAI = TerminalTheme(
        id = "monokai", label = "Monokai",
        background = 0xFF272822, foreground = 0xFFF8F8F2, cursor = 0xFFF8F8F2,
        ansi = listOf(
            0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
            0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5
        )
    )
    // The terminal companion of the app's "04 FAQ Blue Cards"
    // UI theme (owner's Penpot template, 2026-11-08): navy
    // ground, white text, the template's bright blue family.
    val BLUE_CARDS = TerminalTheme(
        id = "blue-cards", label = "Blue Cards",
        background = 0xFF24344E, foreground = 0xFFFFFFFF, cursor = 0xFF2F7DE1,
        ansi = listOf(
            0xFF31445F, 0xFFFF6B6B, 0xFF4CC38A, 0xFFF5C15C, 0xFF2F7DE1, 0xFFC792EA, 0xFF4FC3D9, 0xFFFFFFFF,
            0xFF3D5878, 0xFFFF8A80, 0xFF69DBA0, 0xFFFFD97A, 0xFF6FA8EC, 0xFFDDB4F5, 0xFF7ADBE8, 0xFFFFFFFF
        )
    )
    val HIGH_CONTRAST = TerminalTheme(
        id = "high-contrast", label = "High contrast",
        background = 0xFF000000, foreground = 0xFFFFFFFF, cursor = 0xFFFFFF00,
        ansi = listOf(
            0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF00A0FF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
            0xFF808080, 0xFFFF4040, 0xFF40FF40, 0xFFFFFF40, 0xFF40A0FF, 0xFFFF40FF, 0xFF40FFFF, 0xFFFFFFFF
        )
    )
    val all: List<TerminalTheme> = listOf(
        CLASSIC, SOLARIZED, LIGHT, DRACULA, NORD, GRUVBOX, MONOKAI, BLUE_CARDS, HIGH_CONTRAST
    )
    fun byId(id: String): TerminalTheme = all.firstOrNull { it.id == id } ?: CLASSIC

    /** Built-ins first-class, custom schemes (item T-08) win on id. */
    fun resolve(id: String, customs: List<TerminalTheme>): TerminalTheme =
        customs.firstOrNull { it.id == id } ?: byId(id)
}
