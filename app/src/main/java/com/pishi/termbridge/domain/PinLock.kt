package com.pishi.termbridge.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN rules and hashing for the app lock. The PIN is never stored:
 * only a salted PBKDF2-HMAC-SHA256 hash, compared in constant time.
 * A 4-12 digit PIN is weak against an attacker with the stored hash,
 * which is why this is a convenience/privacy lock for casual access
 * (someone holding the unlocked phone), NOT protection for the stored
 * credentials — those stay encrypted by the Android Keystore
 * regardless. The UI says so.
 */
object PinLock {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    /**
     * Current PBKDF2 cost for the app lock — audit #3 (A-23)
     * raised it to match the backup export's 600,000
     * (security/ExportCrypto). Hashes stored by older builds
     * were computed at [LEGACY_ITERATIONS]; because the count
     * is not stored with the hash, verification tries the
     * current cost first and falls back to the legacy one,
     * re-hashing on a legacy success (see AppLockStore).
     */
    const val ITERATIONS = 600_000

    /** The cost used before audit #3 (A-23); verify-only. */
    const val LEGACY_ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(bytes)
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(pin: String, salt: ByteArray, expectedHash: String, iterations: Int = ITERATIONS): Boolean =
        MessageDigest.isEqual(
            hash(pin, salt, iterations).toByteArray(),
            expectedHash.toByteArray()
        )

    /** Wait imposed after [failures] consecutive wrong PINs (none for the first 4). */
    fun backoffMs(failures: Int): Long = when {
        failures < 5 -> 0L
        failures < 8 -> 30_000L
        failures < 10 -> 120_000L
        else -> 600_000L
    }

    /**
     * Remaining app-lock throttle in ms (audit #7 AUD-005),
     * computed from BOTH clocks. The wall clock alone is
     * user-settable: moving it backward used to stretch a
     * 30 s wait by however far the clock moved, and forward
     * could erase the wait entirely.
     *
     * Anchors persisted when the throttle was imposed:
     * [untilWall] (the wall deadline), [setWall] / [setElapsed]
     * (wall / monotonic readings at impose time) and [waitMs]
     * (the imposed wait). [sameBoot] says the monotonic clock
     * still belongs to the boot that imposed the throttle.
     *
     * - Same boot: the remaining time derives from the
     *   monotonic clock only — wall changes in either
     *   direction are ignored.
     * - After a reboot: the monotonic anchor is meaningless,
     *   so the wall deadline rules, CLAMPED to [waitMs] — a
     *   backward jump can never stretch the wait past what
     *   was imposed (a forward jump can still end it early;
     *   the PBKDF2 cost, not the throttle, is the real barrier
     *   against guessing).
     * - Legacy state (written before the anchors existed):
     *   the historical wall-only behaviour, for that one
     *   already-running throttle only.
     */
    fun throttleRemainingMs(
        nowWall: Long,
        nowElapsed: Long,
        untilWall: Long,
        setWall: Long,
        setElapsed: Long,
        waitMs: Long,
        sameBoot: Boolean
    ): Long {
        if (untilWall <= 0L) return 0L
        if (setWall <= 0L || waitMs <= 0L) {
            return (untilWall - nowWall).coerceAtLeast(0L)
        }
        if (sameBoot) {
            return (waitMs - (nowElapsed - setElapsed)).coerceAtLeast(0L)
        }
        return (untilWall - nowWall).coerceIn(0L, waitMs)
    }
}
