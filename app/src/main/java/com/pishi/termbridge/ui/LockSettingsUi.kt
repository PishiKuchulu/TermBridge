package com.pishi.termbridge.ui

import com.pishi.termbridge.domain.LockMethod

/**
 * What the Settings screen needs to show and change the app lock,
 * without the screen knowing about stores or the activity.
 */
data class LockSettingsUi(
    val enabled: Boolean,
    val biometricOn: Boolean,
    val biometricAvailable: Boolean,
    /** Returns false when the PIN is invalid. */
    val setPin: (String) -> Boolean,
    /** Verifies the current PIN, then turns the lock off; false if the PIN was wrong. */
    val disable: (String) -> Boolean,
    val setBiometric: (Boolean) -> Unit,
    // Item T-18 additions. Defaults keep previews and older
    // constructions working; production wires every one.
    /** The active credential method (PIN or pattern). */
    val method: LockMethod = LockMethod.PIN,
    /** Sets the lock to a pattern; false when it is not settable. */
    val setPattern: (List<Int>) -> Boolean = { false },
    /** Verifies the current pattern, then turns the lock off. */
    val disableWithPattern: (List<Int>) -> Boolean = { false },
    /** Auto-lock delay choices the screen may offer. */
    val autoLockChoicesMs: List<Long> = listOf(30_000L),
    val autoLockDelayMs: Long = 30_000L,
    val setAutoLockDelay: (Long) -> Unit = {},
    /** Blank the app's card in the recents list while locked. */
    val coverRecents: Boolean = true,
    val setCoverRecents: (Boolean) -> Unit = {},
    /** Engage the lock immediately. */
    val lockNow: () -> Unit = {}
)
