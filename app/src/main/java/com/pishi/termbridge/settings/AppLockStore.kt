package com.pishi.termbridge.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
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
    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences("termbridge_lock", Context.MODE_PRIVATE)

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

    // Audit #6 TB-005: process-lifetime lock session state. This
    // store lives in the Application, so these survive activity
    // recreation (rotation, theme / locale / font-scale change)
    // that used to drop the user back on the lock screen, ignoring
    // the auto-lock delay entirely. A fresh PROCESS still starts
    // locked (both reset with the process), and the background
    // delay check in MainActivity.onStart is unchanged.
    @Volatile
    var unlockedThisProcess: Boolean = false

    @Volatile
    var backgroundedAtElapsed: Long = 0L

    fun markUnlocked() {
        unlockedThisProcess = true
    }

    fun markLocked() {
        unlockedThisProcess = false
    }

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
        // Audit #6 TB-005 corollary: setting or replacing the
        // lock re-arms it for this process as well.
        unlockedThisProcess = false
        val salt = PinLock.newSalt()
        prefs.edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, PinLock.hash(secret, salt))
            .putString(K_METHOD, method.name)
            .putInt(K_FAILS, 0)
            .putLong(K_UNTIL, 0L)
            .putLong(K_SET_WALL, 0L)
            .putLong(K_SET_ELAPSED, 0L)
            .putLong(K_WAIT, 0L)
            .putLong(K_SET_BOOT, -1L)
            .apply()
        _enabled.value = true
        _method.value = method
    }

    @Synchronized
    fun verify(
        pin: String,
        now: Long = System.currentTimeMillis(),
        elapsedNow: Long = SystemClock.elapsedRealtime()
    ): UnlockResult = verifySecret(pin, now, elapsedNow)

    /** Item T-18: verify a drawn pattern. The drawn points are
     *  normalized and canonicalized exactly as at set time, so
     *  an equivalent stroke verifies. */
    @Synchronized
    fun verifyPattern(
        points: List<Int>,
        now: Long = System.currentTimeMillis(),
        elapsedNow: Long = SystemClock.elapsedRealtime()
    ): UnlockResult = verifySecret(PatternLock.canonical(points), now, elapsedNow)

    /** Settings.Global.BOOT_COUNT, or -1 when unavailable. */
    private fun currentBootCount(): Long = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
            .toLong()
    } catch (e: Exception) {
        -1L
    }

    /** True when the monotonic clock still belongs to the boot
     *  that imposed the pending throttle (audit #7 AUD-005).
     *  BOOT_COUNT is the exact signal; when it is unavailable
     *  on either side, the elapsed readings are the fallback
     *  (a post-reboot elapsed clock restarts near zero). */
    private fun sameBootAsThrottle(elapsedNow: Long): Boolean {
        val setBoot = prefs.getLong(K_SET_BOOT, -1L)
        val boot = currentBootCount()
        if (setBoot >= 0 && boot >= 0) return setBoot == boot
        return elapsedNow >= prefs.getLong(K_SET_ELAPSED, 0L)
    }

    @Synchronized
    private fun verifySecret(secret: String, now: Long, elapsedNow: Long): UnlockResult {
        // Audit #7 AUD-005: the throttle is measured on the
        // monotonic clock within a boot (see PinLock), so a
        // wall-clock change can neither stretch nor erase it.
        val remaining = PinLock.throttleRemainingMs(
            nowWall = now,
            nowElapsed = elapsedNow,
            untilWall = prefs.getLong(K_UNTIL, 0L),
            setWall = prefs.getLong(K_SET_WALL, 0L),
            setElapsed = prefs.getLong(K_SET_ELAPSED, 0L),
            waitMs = prefs.getLong(K_WAIT, 0L),
            sameBoot = sameBootAsThrottle(elapsedNow)
        )
        if (remaining > 0) return UnlockResult.Throttled(remaining)
        val salt = prefs.getString(K_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val hash = prefs.getString(K_HASH, null)
        // Audit #3 (A-22): fail CLOSED. A missing salt or hash
        // means the stored lock material is damaged; this used
        // to return Success, so a lost salt silently opened
        // the app. (When the hash is gone entirely the lock
        // also reports itself disabled, so this branch is the
        // salt-loss case in practice.)
        if (salt == null || hash == null) return UnlockResult.Wrong
        if (PinLock.verify(secret, salt, hash)) {
            val edit = prefs.edit()
            clearThrottle(edit)
            edit.apply()
            return UnlockResult.Success
        }
        // Audit #3 (A-23): hashes stored before the KDF cost
        // was raised to 600,000 were computed at the legacy
        // 120,000, and the count is not stored with the hash —
        // retry at the legacy cost. On a legacy success,
        // transparently re-hash at the current cost (same salt)
        // so later unlocks take the primary path above.
        if (PinLock.verify(secret, salt, hash, PinLock.LEGACY_ITERATIONS)) {
            val edit = prefs.edit()
                .putString(K_HASH, PinLock.hash(secret, salt))
            clearThrottle(edit)
            edit.apply()
            return UnlockResult.Success
        }
        val fails = prefs.getInt(K_FAILS, 0) + 1
        val wait = PinLock.backoffMs(fails)
        val edit = prefs.edit().putInt(K_FAILS, fails)
        if (wait > 0) {
            // Audit #7 AUD-005: persist the monotonic anchors
            // beside the wall deadline, so the wait is measured
            // on a clock the user cannot set.
            edit.putLong(K_UNTIL, now + wait)
                .putLong(K_SET_WALL, now)
                .putLong(K_SET_ELAPSED, elapsedNow)
                .putLong(K_WAIT, wait)
                .putLong(K_SET_BOOT, currentBootCount())
        } else {
            edit.putLong(K_UNTIL, 0L)
        }
        edit.apply()
        return if (wait > 0) UnlockResult.Throttled(wait) else UnlockResult.Wrong
    }

    /** Clears the fails counter and every throttle field
     *  (deadline, anchors, imposed wait, boot stamp). */
    private fun clearThrottle(edit: SharedPreferences.Editor) {
        edit.putInt(K_FAILS, 0)
            .putLong(K_UNTIL, 0L)
            .putLong(K_SET_WALL, 0L)
            .putLong(K_SET_ELAPSED, 0L)
            .putLong(K_WAIT, 0L)
            .putLong(K_SET_BOOT, -1L)
    }

    /** Removes the lock; the caller must have verified the
     *  current credential first. The delay and recents
     *  preferences survive: they are not secrets. */
    @Synchronized
    fun disable() {
        unlockedThisProcess = false
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
        private const val K_SET_WALL = "set_wall"
        private const val K_SET_ELAPSED = "set_elapsed"
        private const val K_WAIT = "wait_ms"
        private const val K_SET_BOOT = "set_boot"
        private const val K_METHOD = "method"
        private const val K_DELAY = "auto_lock_ms"
        private const val K_COVER = "cover_recents"
    }
}
