package com.pishi.termbridge.settings

import android.content.Context
import com.pishi.termbridge.domain.CustomThemeCodec
import com.pishi.termbridge.domain.TerminalTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence for user-defined terminal colour schemes
 * (item T-08): one JSON array in SharedPreferences, decoded
 * through the strict codec — a damaged entry is dropped on
 * load, never half-applied. Schemes are local-only cosmetic
 * data; they are not part of the encrypted backup format.
 */
class CustomThemeStore(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<TerminalTheme> =
        CustomThemeCodec.decodeAll(prefs.getString(K_THEMES, "") ?: "")

    fun save(themes: List<TerminalTheme>) {
        prefs.edit().putString(K_THEMES, CustomThemeCodec.encodeAll(themes)).apply()
    }

    private companion object {
        const val PREFS_NAME = "termbridge_custom_themes"
        const val K_THEMES = "themes_json"
    }
}

/** The live list of custom schemes plus its mutations. */
class CustomThemeRepository(private val store: CustomThemeStore) {
    private val _themes = MutableStateFlow(store.load())
    val themes: StateFlow<List<TerminalTheme>> = _themes

    /** Adds a new scheme, or replaces the one with the same id. */
    fun upsert(theme: TerminalTheme) {
        val next = _themes.value.filter { it.id != theme.id } + theme
        _themes.value = next
        store.save(next)
    }

    fun delete(id: String) {
        val next = _themes.value.filter { it.id != id }
        _themes.value = next
        store.save(next)
    }
}
