package com.pishi.termbridge.ui

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
    val setBiometric: (Boolean) -> Unit
)
