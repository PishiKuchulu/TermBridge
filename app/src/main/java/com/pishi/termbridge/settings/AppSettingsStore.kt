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
                blockScreenshots = prefs.getBoolean(K_BLOCK, d.blockScreenshots)
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
    }
}
