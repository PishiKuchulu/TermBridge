package com.pishi.termbridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: the activity launches and shows the Hosts
 * shell (the navigation start destination). NOT RUN until an
 * emulator/device run exists (prompt 03/14). It must not be reported
 * as a UI-feature test: the screen it checks explicitly says
 * features are not implemented yet.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun hostsShellIsDisplayed() {
        composeRule.onNodeWithText("No hosts yet").assertIsDisplayed()
    }
}
