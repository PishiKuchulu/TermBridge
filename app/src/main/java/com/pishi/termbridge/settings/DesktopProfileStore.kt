package com.pishi.termbridge.settings

import android.content.Context
import com.pishi.termbridge.domain.DesktopProfile
import com.pishi.termbridge.domain.DesktopProfileCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence for saved remote-desktop profiles (item
 * F-03b): one JSON array in SharedPreferences, decoded
 * through the codec — a damaged entry drops out on load.
 * Profiles hold endpoints only, never credentials, and are
 * local-only (not part of the backup format).
 */
class DesktopProfileStore(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<DesktopProfile> =
        DesktopProfileCodec.decode(prefs.getString(K_PROFILES, "") ?: "")

    fun save(profiles: List<DesktopProfile>) {
        prefs.edit()
            .putString(K_PROFILES, DesktopProfileCodec.encode(profiles))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "termbridge_desktop_profiles"
        const val K_PROFILES = "profiles_json"
    }
}

/** The live profile list plus its mutations. */
class DesktopProfileRepository(private val store: DesktopProfileStore) {
    private val _profiles = MutableStateFlow(store.load())
    val profiles: StateFlow<List<DesktopProfile>> = _profiles

    /** Adds a profile (id assigned here) or replaces by id. */
    fun save(profile: DesktopProfile): DesktopProfile {
        val assigned = if (profile.id == 0L) {
            profile.copy(id = (_profiles.value.maxOfOrNull { it.id } ?: 0L) + 1L)
        } else {
            profile
        }
        val next = _profiles.value.filter { it.id != assigned.id } + assigned
        _profiles.value = next
        store.save(next)
        return assigned
    }

    fun delete(id: Long) {
        val next = _profiles.value.filter { it.id != id }
        _profiles.value = next
        store.save(next)
    }
}
