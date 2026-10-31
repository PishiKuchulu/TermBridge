package com.pishi.termbridge.domain

/**
 * User preferences that change how the app behaves. Pure data plus a
 * sanitiser, so every bound is unit-tested on the JVM and a corrupt
 * or hand-edited preference file can never produce an unusable value
 * (a 1 sp font, a negative scrollback, a blank terminal type).
 */
data class AppSettings(
    /** Terminal text size in sp. */
    val terminalFontSp: Int = DEFAULT_FONT_SP,
    /** Lines kept above the visible screen. */
    val scrollbackLines: Int = DEFAULT_SCROLLBACK,
    /** TERM value requested for the remote PTY. */
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    /** Keep the display awake while the terminal is on screen. */
    val keepScreenOn: Boolean = false,
    /** SSH keep-alive interval in seconds; 0 turns it off. */
    val keepAliveSeconds: Int = DEFAULT_KEEPALIVE,
    /** Block screenshots, screen recording and the Recents preview. */
    val blockScreenshots: Boolean = true
) {
    companion object {
        const val DEFAULT_FONT_SP = 13
        const val MIN_FONT_SP = 8
        const val MAX_FONT_SP = 28
        const val DEFAULT_SCROLLBACK = 1000
        val SCROLLBACK_CHOICES = listOf(500, 1000, 2000, 5000, 10000)
        const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        val TERMINAL_TYPES = listOf("xterm-256color", "xterm", "vt100", "linux")
        const val DEFAULT_KEEPALIVE = 60
        val KEEPALIVE_CHOICES = listOf(0, 15, 30, 60, 120, 300)
    }
}

object AppSettingsRules {
    /** Clamp/replace anything outside what the app supports. */
    fun sanitize(raw: AppSettings): AppSettings = AppSettings(
        terminalFontSp = raw.terminalFontSp.coerceIn(
            AppSettings.MIN_FONT_SP, AppSettings.MAX_FONT_SP
        ),
        scrollbackLines = if (raw.scrollbackLines in AppSettings.SCROLLBACK_CHOICES) {
            raw.scrollbackLines
        } else {
            AppSettings.DEFAULT_SCROLLBACK
        },
        terminalType = if (raw.terminalType in AppSettings.TERMINAL_TYPES) {
            raw.terminalType
        } else {
            AppSettings.DEFAULT_TERMINAL_TYPE
        },
        keepScreenOn = raw.keepScreenOn,
        keepAliveSeconds = if (raw.keepAliveSeconds in AppSettings.KEEPALIVE_CHOICES) {
            raw.keepAliveSeconds
        } else {
            AppSettings.DEFAULT_KEEPALIVE
        },
        blockScreenshots = raw.blockScreenshots
    )

    /** Next value in [choices] after [current], wrapping to the first. */
    fun <T> next(choices: List<T>, current: T): T {
        val index = choices.indexOf(current)
        return choices[(index + 1).mod(choices.size)]
    }
}
