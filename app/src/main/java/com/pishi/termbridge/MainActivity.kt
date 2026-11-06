package com.pishi.termbridge

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.pishi.termbridge.settings.UnlockResult
import com.pishi.termbridge.ui.BiometricHelper
import com.pishi.termbridge.ui.LockSettingsUi
import com.pishi.termbridge.ui.navigation.TermBridgeApp
import com.pishi.termbridge.ui.screens.LockScreen
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/**
 * Single activity. It extends FragmentActivity (a ComponentActivity
 * subclass) only because BiometricPrompt needs one.
 *
 * Screenshots: FLAG_SECURE follows the "block screenshots" setting
 * (default on) and covers the whole app, because this is the app's
 * only activity.
 *
 * App lock: when a lock is set (PIN or pattern, item T-18), a
 * full-screen overlay hides the app on a cold start and after the
 * app has been in the background for longer than the user's
 * auto-lock delay (AppLockStore; 30 seconds by default). The
 * delay keeps the system file picker (a separate activity) from
 * re-locking the app on every return.
 */
class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L

    /** Volume-key handler registered by the app while the
     *  session route is showing (item T-19); null everywhere
     *  else, where the keys keep their system behaviour. */
    private var volumeKeyHandler: ((com.pishi.termbridge.domain.VolumeKey, Boolean) -> Boolean)? = null

    // Audit #3 (A-26): one consumed-flag PER KEY. A single
    // shared flag let one key's consumed keydown swallow the
    // other key's keyup.
    private var volumeUpConsumed = false
    private var volumeDownConsumed = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val key = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP ->
                com.pishi.termbridge.domain.VolumeKey.UP
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                com.pishi.termbridge.domain.VolumeKey.DOWN
            else -> null
        }
        if (key != null) {
            val consumed = volumeKeyHandler?.invoke(key, (event?.repeatCount ?: 0) > 0) == true
            if (consumed) {
                when (key) {
                    com.pishi.termbridge.domain.VolumeKey.UP -> volumeUpConsumed = true
                    com.pishi.termbridge.domain.VolumeKey.DOWN -> volumeDownConsumed = true
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (volumeUpConsumed) {
                volumeUpConsumed = false
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeDownConsumed) {
                volumeDownConsumed = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TermBridgeApplication
        locked = app.appLock.enabled.value
        // Apply the stored choice before the first frame (no window in
        // which a protected screen is briefly capturable).
        if (app.appSettings.settings.value.blockScreenshots ||
            (app.appLock.enabled.value && app.appLock.coverRecents.value)
        ) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            val prefs by app.appSettings.settings.collectAsState()
            val lockEnabled by app.appLock.enabled.collectAsState()
            val biometricOn by app.appLock.biometric.collectAsState()
            val lockMethod by app.appLock.method.collectAsState()
            val autoLockDelay by app.appLock.autoLockDelayMs.collectAsState()
            val coverRecents by app.appLock.coverRecents.collectAsState()
            // FLAG_SECURE covers screenshots AND the recents card:
            // either the screenshots setting, or the app lock with
            // its cover-in-recents preference (item T-18).
            LaunchedEffect(prefs.blockScreenshots, lockEnabled, coverRecents) {
                if (prefs.blockScreenshots || (lockEnabled && coverRecents)) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            val biometricAvailable = remember { BiometricHelper.isAvailable(this@MainActivity) }
            val lockSettings = LockSettingsUi(
                enabled = lockEnabled,
                biometricOn = biometricOn,
                biometricAvailable = biometricAvailable,
                setPin = { pin -> app.appLock.setPin(pin) },
                disable = { pin ->
                    if (app.appLock.verify(pin) == UnlockResult.Success) {
                        app.appLock.disable()
                        true
                    } else {
                        false
                    }
                },
                setBiometric = { on -> app.appLock.setBiometric(on) },
                method = lockMethod,
                setPattern = { points -> app.appLock.setPattern(points) },
                disableWithPattern = { points ->
                    if (app.appLock.verifyPattern(points) == UnlockResult.Success) {
                        app.appLock.disable()
                        true
                    } else {
                        false
                    }
                },
                autoLockChoicesMs = com.pishi.termbridge.settings.AppLockStore.AUTO_LOCK_CHOICES,
                autoLockDelayMs = autoLockDelay,
                setAutoLockDelay = { ms -> app.appLock.setAutoLockDelay(ms) },
                coverRecents = coverRecents,
                setCoverRecents = { on -> app.appLock.setCoverRecents(on) },
                lockNow = { locked = true }
            )
            val promptTitle = getString(R.string.lock_biometric_title)
            val promptCancel = getString(R.string.action_cancel)
            TermBridgeTheme {
                Box {
                    TermBridgeApp(
                        repository = app.hostRepository,
                        commandRepository = app.commandRepository,
                        environmentRepository = app.environmentRepository,
                        advancedRepository = app.advancedRepository,
                        backupService = app.backupService,
                        keychainRepository = app.keychainRepository,
                        snippetRepository = app.snippetRepository,
                        customThemeRepository = app.customThemeRepository,
                        desktopProfileRepository = app.desktopProfileRepository,
                        transcriptStore = app.transcriptStore,
                        commandHistoryStore = app.commandHistoryStore,
                        appSettings = app.appSettings,
                        connectionLog = app.connectionLog,
                        lockSettings = lockSettings,
                        onRegisterVolumeKeyHandler = { handler ->
                            volumeKeyHandler = handler
                        }
                    )
                    // Audit #3 (A-11): while the lock overlay is
                    // shown, Back is consumed here (deliberately
                    // empty) so it cannot pop or navigate the
                    // graph underneath the overlay. Enabled only
                    // in exactly the state that shows the overlay.
                    BackHandler(enabled = locked && lockEnabled) {
                        // Consumed: the lock overlay owns Back.
                    }
                    if (locked && lockEnabled) {
                        LockScreen(
                            store = app.appLock,
                            biometricEnabled = biometricOn && biometricAvailable,
                            onUnlocked = { locked = false },
                            onBiometric = {
                                BiometricHelper.prompt(
                                    this@MainActivity, promptTitle, promptCancel
                                ) { locked = false }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val app = application as TermBridgeApplication
        if (stoppedAt > 0L && app.appLock.enabled.value &&
            SystemClock.elapsedRealtime() - stoppedAt > app.appLock.autoLockDelayMs.value
        ) {
            locked = true
        }
    }
}
