package com.pishi.termbridge

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
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
 * App lock: when a PIN is set, a full-screen overlay hides the app on
 * a cold start and after the app has been in the background for more
 * than [LOCK_GRACE_MS]. The grace period keeps the system file picker
 * (a separate activity) from re-locking the app on every return.
 */
class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TermBridgeApplication
        locked = app.appLock.enabled.value
        // Apply the stored choice before the first frame (no window in
        // which a protected screen is briefly capturable).
        if (app.appSettings.settings.value.blockScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            val prefs by app.appSettings.settings.collectAsState()
            LaunchedEffect(prefs.blockScreenshots) {
                if (prefs.blockScreenshots) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            val lockEnabled by app.appLock.enabled.collectAsState()
            val biometricOn by app.appLock.biometric.collectAsState()
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
                setBiometric = { on -> app.appLock.setBiometric(on) }
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
                        appSettings = app.appSettings,
                        connectionLog = app.connectionLog,
                        lockSettings = lockSettings
                    )
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
            SystemClock.elapsedRealtime() - stoppedAt > LOCK_GRACE_MS
        ) {
            locked = true
        }
    }

    private companion object {
        const val LOCK_GRACE_MS = 30_000L
    }
}
