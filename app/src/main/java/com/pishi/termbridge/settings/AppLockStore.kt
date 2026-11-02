package com.pishi.termbridge.settings

import android.content.Context
import android.util.Base64
import com.pishi.termbridge.domain.PinLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of a PIN attempt. */
sealed class UnlockResult {
    object Success : UnlockResult()
    object Wrong : UnlockResult()
    data class Throttled(val retryInMs: Long) : UnlockResult()
}

/**
 * Stores the app-lock PIN hash, the biometric preference and the
 * failed-attempt counter. Only a salted hash is persisted.
 */
class AppLockStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("termbridge_lock", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.contains(K_HASH))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _biometric = MutableStateFlow(prefs.getBoolean(K_BIO, false))
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    @Synchronized
    fun setPin(pin: String): Boolean {
        if (!PinLock.isValidPin(pin)) return false
        val salt = PinLock.newSalt()
        prefs.edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, PinLock.hash(pin, salt))
            .putInt(K_FAILS, 0)
            .putLong(K_UNTIL, 0L)
            .apply()
        _enabled.value = true
        return true
    }

    @Synchronized
    fun verify(pin: String, now: Long = System.currentTimeMillis()): UnlockResult {
        val until = prefs.getLong(K_UNTIL, 0L)
        if (now < until) return UnlockResult.Throttled(until - now)
        val salt = prefs.getString(K_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val hash = prefs.getString(K_HASH, null)
        if (salt == null || hash == null) return UnlockResult.Success
        if (PinLock.verify(pin, salt, hash)) {
            prefs.edit().putInt(K_FAILS, 0).putLong(K_UNTIL, 0L).apply()
            return UnlockResult.Success
        }
        val fails = prefs.getInt(K_FAILS, 0) + 1
        val wait = PinLock.backoffMs(fails)
        prefs.edit().putInt(K_FAILS, fails).putLong(K_UNTIL, if (wait > 0) now + wait else 0L).apply()
        return if (wait > 0) UnlockResult.Throttled(wait) else UnlockResult.Wrong
    }

    /** Removes the lock; the caller must have verified the PIN first. */
    @Synchronized
    fun disable() {
        prefs.edit().clear().apply()
        _enabled.value = false
        _biometric.value = false
    }

    @Synchronized
    fun setBiometric(on: Boolean) {
        prefs.edit().putBoolean(K_BIO, on).apply()
        _biometric.value = on
    }

    private companion object {
        const val K_SALT = "salt"
        const val K_HASH = "hash"
        const val K_BIO = "biometric"
        const val K_FAILS = "fails"
        const val K_UNTIL = "until"
    }
}
