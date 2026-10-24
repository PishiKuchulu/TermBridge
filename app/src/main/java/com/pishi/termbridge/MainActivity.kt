package com.pishi.termbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pishi.termbridge.ui.navigation.TermBridgeApp
import com.pishi.termbridge.ui.theme.TermBridgeTheme

/**
 * Entry point, now hosting the prompt-03 navigation shell. The shell
 * is honest by construction: every unimplemented destination carries
 * its "navigation shell only" banner until its feature prompt lands.
 *
 * Security fix 16 (M1): FLAG_SECURE is set on this window, so
 * terminal content — hostnames, usernames, command output,
 * potentially secrets printed by the server — cannot appear in
 * the Recents overview or in screenshots/screen recordings.
 * Scope, stated honestly: this is the app's only activity, so
 * the flag covers the whole app, not just the terminal screen —
 * the user cannot screenshot host lists or settings either.
 * That tradeoff is disclosed in the Settings screen note.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val app = application as TermBridgeApplication
        setContent {
            TermBridgeTheme {
                TermBridgeApp(
                    repository = app.hostRepository,
                    commandRepository = app.commandRepository,
                    environmentRepository = app.environmentRepository,
                    advancedRepository = app.advancedRepository,
                    backupService = app.backupService
                )
            }
        }
    }
}
