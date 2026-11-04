package com.pishi.termbridge.settings

import android.content.Context
import android.util.Base64
import com.pishi.termbridge.domain.LockMethod
import com.pishi.termbridge.domain.PatternLock
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
 * Stores the app-lock credential hash, the biometric preference
 * and the failed-attempt counter. Only a salted hash is persisted.
 *
 * Item T-18: the credential is ONE secret with one method —
 * a PIN, or a pattern whose canonical string is hashed through
 * the same salted PBKDF2 path (see domain/PatternLock). Setting
 * one replaces the other. Verification and throttling are shared:
 * both go through [verifySecret], so the backoff rules cannot
 * drift apart. The store also holds the auto-lock delay and the
 * cover-in-recents preference; those are plain preferences (not
 * secrets) and survive [disable].
 */
class AppLockStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("termbridge_lock", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.contains(K_HASH))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _biometric = MutableStateFlow(prefs.getBoolean(K_BIO, false))
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    private val _method = MutableStateFlow(readMethod())
    val method: StateFlow<LockMethod> = _method.asStateFlow()

    private val _autoLockDelayMs = MutableStateFlow(
        prefs.getLong(K_DELAY, DEFAULT_AUTO_LOCK_MS)
    )
    val autoLockDelayMs: StateFlow<Long> = _autoLockDelayMs.asStateFlow()

    private val _coverRecents = MutableStateFlow(prefs.getBoolean(K_COVER, true))
    val coverRecents: StateFlow<Boolean> = _coverRecents.asStateFlow()

    private fun readMethod(): LockMethod =
        try {
            LockMethod.valueOf(prefs.getString(K_METHOD, null) ?: LockMethod.PIN.name)
        } catch (e: Exception) {
            LockMethod.PIN
        }

    @Synchronized
    fun setPin(pin: String): Boolean {
        if (!PinLock.isValidPin(pin)) return false
        storeSecret(pin, LockMethod.PIN)
        return true
    }

    /** Item T-18: set (or replace) the lock with a pattern.
     *  Returns false when the pattern is not settable. */
    @Synchronized
    fun setPattern(points: List<Int>): Boolean {
        if (!PatternLock.isValid(points)) return false
        storeSecret(PatternLock.canonical(points), LockMethod.PATTERN)
        return true
    }

    private fun storeSecret(secret: String, method: LockMethod) {
        val salt = PinLock.newSalt()
        prefs.edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, PinLock.hash(secret, salt))
            .putString(K_METHOD, method.name)
            .putInt(K_FAILS, 0)
            .putLong(K_UNTIL, 0L)
            .apply()
        _enabled.value = true
        _method.value = method
    }

    @Synchronized
    fun verify(pin: String, now: Long = System.currentTimeMillis()): UnlockResult =
        verifySecret(pin, now)

    /** Item T-18: verify a drawn pattern. The drawn points are
     *  normalized and canonicalized exactly as at set time, so
     *  an equivalent stroke verifies. */
    @Synchronized
    fun verifyPattern(
        points: List<Int>,
        now: Long = System.currentTimeMillis()
    ): UnlockResult = verifySecret(PatternLock.canonical(points), now)

    @Synchronized
    private fun verifySecret(secret: String, now: Long): UnlockResult {
        val until = prefs.getLong(K_UNTIL, 0L)
        if (now < until) return UnlockResult.Throttled(until - now)
        val salt = prefs.getString(K_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val hash = prefs.getString(K_HASH, null)
        if (salt == null || hash == null) return UnlockResult.Success
        if (PinLock.verify(secret, salt, hash)) {
            prefs.edit().putInt(K_FAILS, 0).putLong(K_UNTIL, 0L).apply()
            return UnlockResult.Success
        }
        val fails = prefs.getInt(K_FAILS, 0) + 1
        val wait = PinLock.backoffMs(fails)
        prefs.edit().putInt(K_FAILS, fails).putLong(K_UNTIL, if (wait > 0) now + wait else 0L).apply()
        return if (wait > 0) UnlockResult.Throttled(wait) else UnlockResult.Wrong
    }

    /** Removes the lock; the caller must have verified the
     *  current credential first. The delay and recents
     *  preferences survive: they are not secrets. */
    @Synchronized
    fun disable() {
        val delay = prefs.getLong(K_DELAY, DEFAULT_AUTO_LOCK_MS)
        val cover = prefs.getBoolean(K_COVER, true)
        prefs.edit().clear()
            .putLong(K_DELAY, delay)
            .putBoolean(K_COVER, cover)
            .apply()
        _enabled.value = false
        _biometric.value = false
        _method.value = LockMethod.PIN
    }

    @Synchronized
    fun setBiometric(on: Boolean) {
        prefs.edit().putBoolean(K_BIO, on).apply()
        _biometric.value = on
    }

    /** Item T-18: how long the app may stay in the background
     *  before the lock engages. Values outside the offered
     *  choices fall back to the default. */
    @Synchronized
    fun setAutoLockDelay(ms: Long) {
        val value = if (ms in AUTO_LOCK_CHOICES) ms else DEFAULT_AUTO_LOCK_MS
        prefs.edit().putLong(K_DELAY, value).apply()
        _autoLockDelayMs.value = value
    }

    /** Item T-18: while on (and the lock enabled), the app's
     *  recents card is blanked via FLAG_SECURE. */
    @Synchronized
    fun setCoverRecents(on: Boolean) {
        prefs.edit().putBoolean(K_COVER, on).apply()
        _coverRecents.value = on
    }

    companion object {
        /** The delay the app shipped with before T-18 made it a
         *  choice; still the default. */
        const val DEFAULT_AUTO_LOCK_MS = 30_000L

        /** Offered auto-lock delays: immediately, 30 seconds
         *  (default), 1 minute, 5 minutes. */
        val AUTO_LOCK_CHOICES = listOf(0L, 30_000L, 60_000L, 300_000L)

        private const val K_SALT = "salt"
        private const val K_HASH = "hash"
        private const val K_BIO = "biometric"
        private const val K_FAILS = "fails"
        private const val K_UNTIL = "until"
        private const val K_METHOD = "method"
        private const val K_DELAY = "auto_lock_ms"
        private const val K_COVER = "cover_recents"
    }
}
