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
    /** Unanswered keep-alive probes in a row before the app
     *  drops the connection itself (item T-14). */
    val keepAliveMaxMisses: Int = DEFAULT_KEEPALIVE_MAX_MISSES,
    /** Block screenshots, screen recording and the Recents preview. */
    val blockScreenshots: Boolean = true,
    /** Terminal colour scheme id (see [TerminalThemes]). */
    val terminalTheme: String = TerminalThemes.CLASSIC.id,
    /** Haptic tick when the server rings the terminal bell. */
    val bellHaptic: Boolean = true,
    /** Show the extra-keys bar (modifiers, function keys) in a session. */
    val showExtraKeysBar: Boolean = true,
    /** Audible beep when the server rings the terminal bell. */
    val bellSound: Boolean = false,
    /** Brief visual flash when the server rings the terminal bell. */
    val bellFlash: Boolean = false,
    /** Default cursor shape: "block", "underline" or "bar".
     *  A remote DECSCUSR overrides it for the session. */
    val cursorStyle: String = CURSOR_STYLE_BLOCK,
    /** Default cursor blinking (DECSCUSR can override). */
    val cursorBlink: Boolean = false,
    /** Drag-scroll sensitivity multiplier (item T-12). */
    val scrollSensitivity: Float = 1f,
    /** Volume-key action id (item T-19; see [VolumeKeyAction]).
     *  "disabled" by default: the keys keep their system
     *  behaviour. */
    val volumeKeyAction: String = VolumeKeyAction.DISABLED.id,
    /** Shake action id (item T-19; see [ShakeAction]).
     *  "disabled" by default. */
    val shakeAction: String = ShakeAction.DISABLED.id,
    /** Item T-11: command autocomplete. Off by default — while
     *  off, typed commands are not remembered at all. */
    val autocompleteEnabled: Boolean = false,
    /** Item T-16: probe the remote OS on every connect. Off by
     *  default (the off-switch); the manual Detect action in a
     *  live session works regardless. */
    val detectOsOnConnect: Boolean = false,
    /** Item Q-06: first-run onboarding has been seen (or
     *  skipped). False only on a fresh install. */
    val onboardingSeen: Boolean = false
) {
    companion object {
        const val DEFAULT_FONT_SP = 13
        const val MIN_FONT_SP = 8
        const val MAX_FONT_SP = 28
        const val DEFAULT_SCROLLBACK = 1000
        val SCROLLBACK_CHOICES = listOf(500, 1000, 2000, 5000, 10000)
        const val MIN_SCROLLBACK = 100
        const val MAX_SCROLLBACK = 50_000
        const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        val TERMINAL_TYPES = listOf("xterm-256color", "xterm", "vt100", "linux")
        const val DEFAULT_KEEPALIVE = 60
        val KEEPALIVE_CHOICES = listOf(0, 15, 30, 60, 120, 300)
        const val DEFAULT_KEEPALIVE_MAX_MISSES = 3
        val KEEPALIVE_MAX_MISS_CHOICES = listOf(1, 2, 3, 5)
        const val CURSOR_STYLE_BLOCK = "block"
        const val CURSOR_STYLE_UNDERLINE = "underline"
        const val CURSOR_STYLE_BAR = "bar"
        val CURSOR_STYLES = listOf(CURSOR_STYLE_BLOCK, CURSOR_STYLE_UNDERLINE, CURSOR_STYLE_BAR)
        val SCROLL_SENSITIVITIES = listOf(0.5f, 1f, 2f)
    }
}

/**
 * Rough memory cost of the scrollback, for the settings
 * screen (item T-23): lines × an assumed 80 columns × ~40
 * bytes per cell (a cell is a Char plus colour/bold fields;
 * the JVM object overhead is the bulk of it). An estimate for
 * orientation, not an accounting figure.
 */
fun scrollbackMemoryEstimateBytes(lines: Int): Long =
    lines.coerceAtLeast(0) * 80L * 40L

object AppSettingsRules {
    /** Clamp/replace anything outside what the app supports. */
    fun sanitize(raw: AppSettings): AppSettings = AppSettings(
        terminalFontSp = raw.terminalFontSp.coerceIn(
            AppSettings.MIN_FONT_SP, AppSettings.MAX_FONT_SP
        ),
        scrollbackLines = raw.scrollbackLines.coerceIn(
            AppSettings.MIN_SCROLLBACK, AppSettings.MAX_SCROLLBACK
        ),
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
        keepAliveMaxMisses = if (raw.keepAliveMaxMisses in AppSettings.KEEPALIVE_MAX_MISS_CHOICES) {
            raw.keepAliveMaxMisses
        } else {
            AppSettings.DEFAULT_KEEPALIVE_MAX_MISSES
        },
        blockScreenshots = raw.blockScreenshots,
        terminalTheme = if (
            TerminalThemes.all.any { it.id == raw.terminalTheme } ||
            raw.terminalTheme.startsWith("custom-")
        ) {
            // Custom ids (item T-08) live in their own store;
            // resolution falls back to Classic if one is deleted.
            raw.terminalTheme
        } else {
            TerminalThemes.CLASSIC.id
        },
        bellHaptic = raw.bellHaptic,
        showExtraKeysBar = raw.showExtraKeysBar,
        bellSound = raw.bellSound,
        bellFlash = raw.bellFlash,
        cursorStyle = if (raw.cursorStyle in AppSettings.CURSOR_STYLES) {
            raw.cursorStyle
        } else {
            AppSettings.CURSOR_STYLE_BLOCK
        },
        cursorBlink = raw.cursorBlink,
        scrollSensitivity = if (raw.scrollSensitivity in AppSettings.SCROLL_SENSITIVITIES) {
            raw.scrollSensitivity
        } else {
            1f
        },
        volumeKeyAction = if (raw.volumeKeyAction in VolumeKeyAction.IDS) {
            raw.volumeKeyAction
        } else {
            VolumeKeyAction.DISABLED.id
        },
        shakeAction = if (raw.shakeAction in ShakeAction.IDS) {
            raw.shakeAction
        } else {
            ShakeAction.DISABLED.id
        },
        autocompleteEnabled = raw.autocompleteEnabled,
        detectOsOnConnect = raw.detectOsOnConnect,
        onboardingSeen = raw.onboardingSeen
    )

    /** Next value in [choices] after [current], wrapping to the first. */
    fun <T> next(choices: List<T>, current: T): T {
        val index = choices.indexOf(current)
        return choices[(index + 1).mod(choices.size)]
    }
}
