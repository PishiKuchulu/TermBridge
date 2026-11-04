package com.pishi.termbridge.settings

import android.content.Context
import android.content.SharedPreferences
import com.pishi.termbridge.domain.AppSettings
import com.pishi.termbridge.domain.AppSettingsRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [AppSettings] in SharedPreferences (no secrets live here:
 * only display and behaviour preferences). Every read goes through
 * [AppSettingsRules.sanitize], so a damaged file degrades to defaults
 * instead of crashing or producing an unusable UI.
 */
class AppSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = AppSettingsRules.sanitize(transform(_settings.value))
        prefs.edit()
            .putInt(K_FONT, next.terminalFontSp)
            .putInt(K_SCROLLBACK, next.scrollbackLines)
            .putString(K_TERM, next.terminalType)
            .putBoolean(K_KEEP_ON, next.keepScreenOn)
            .putInt(K_KEEPALIVE, next.keepAliveSeconds)
            .putBoolean(K_BLOCK, next.blockScreenshots)
            .putString(K_THEME, next.terminalTheme)
            .putBoolean(K_BELL, next.bellHaptic)
            .putBoolean(K_EXTRA_KEYS, next.showExtraKeysBar)
            .putInt(K_KA_MISSES, next.keepAliveMaxMisses)
            .putBoolean(K_BELL_SOUND, next.bellSound)
            .putBoolean(K_BELL_FLASH, next.bellFlash)
            .putString(K_CURSOR_STYLE, next.cursorStyle)
            .putBoolean(K_CURSOR_BLINK, next.cursorBlink)
            .putFloat(K_SCROLL_SENS, next.scrollSensitivity)
            .putString(K_VOLUME_ACTION, next.volumeKeyAction)
            .putString(K_SHAKE_ACTION, next.shakeAction)
            .putBoolean(K_AUTOCOMPLETE, next.autocompleteEnabled)
            .putBoolean(K_DETECT_OS, next.detectOsOnConnect)
            .putBoolean(K_ONBOARDING, next.onboardingSeen)
            .apply()
        _settings.value = next
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettingsRules.sanitize(
            AppSettings(
                terminalFontSp = prefs.getInt(K_FONT, d.terminalFontSp),
                scrollbackLines = prefs.getInt(K_SCROLLBACK, d.scrollbackLines),
                terminalType = prefs.getString(K_TERM, d.terminalType) ?: d.terminalType,
                keepScreenOn = prefs.getBoolean(K_KEEP_ON, d.keepScreenOn),
                keepAliveSeconds = prefs.getInt(K_KEEPALIVE, d.keepAliveSeconds),
                blockScreenshots = prefs.getBoolean(K_BLOCK, d.blockScreenshots),
                terminalTheme = prefs.getString(K_THEME, d.terminalTheme) ?: d.terminalTheme,
                bellHaptic = prefs.getBoolean(K_BELL, d.bellHaptic),
                showExtraKeysBar = prefs.getBoolean(K_EXTRA_KEYS, d.showExtraKeysBar),
                keepAliveMaxMisses = prefs.getInt(K_KA_MISSES, d.keepAliveMaxMisses),
                bellSound = prefs.getBoolean(K_BELL_SOUND, d.bellSound),
                bellFlash = prefs.getBoolean(K_BELL_FLASH, d.bellFlash),
                cursorStyle = prefs.getString(K_CURSOR_STYLE, d.cursorStyle) ?: d.cursorStyle,
                cursorBlink = prefs.getBoolean(K_CURSOR_BLINK, d.cursorBlink),
                scrollSensitivity = prefs.getFloat(K_SCROLL_SENS, d.scrollSensitivity),
                volumeKeyAction = prefs.getString(K_VOLUME_ACTION, d.volumeKeyAction)
                    ?: d.volumeKeyAction,
                shakeAction = prefs.getString(K_SHAKE_ACTION, d.shakeAction) ?: d.shakeAction,
                autocompleteEnabled = prefs.getBoolean(K_AUTOCOMPLETE, d.autocompleteEnabled),
                detectOsOnConnect = prefs.getBoolean(K_DETECT_OS, d.detectOsOnConnect),
                onboardingSeen = prefs.getBoolean(K_ONBOARDING, d.onboardingSeen)
            )
        )
    }

    private companion object {
        const val FILE = "termbridge_settings"
        const val K_FONT = "terminal_font_sp"
        const val K_SCROLLBACK = "scrollback_lines"
        const val K_TERM = "terminal_type"
        const val K_KEEP_ON = "keep_screen_on"
        const val K_KEEPALIVE = "keepalive_seconds"
        const val K_BLOCK = "block_screenshots"
        const val K_THEME = "terminal_theme"
        const val K_BELL = "bell_haptic"
        const val K_EXTRA_KEYS = "show_extra_keys_bar"
        const val K_KA_MISSES = "keepalive_max_misses"
        const val K_BELL_SOUND = "bell_sound"
        const val K_BELL_FLASH = "bell_flash"
        const val K_CURSOR_STYLE = "cursor_style"
        const val K_CURSOR_BLINK = "cursor_blink"
        const val K_SCROLL_SENS = "scroll_sensitivity"
        const val K_VOLUME_ACTION = "volume_key_action"
        const val K_SHAKE_ACTION = "shake_action"
        const val K_AUTOCOMPLETE = "autocomplete_enabled"
        const val K_DETECT_OS = "detect_os_on_connect"
        const val K_ONBOARDING = "onboarding_seen"
    }
}
