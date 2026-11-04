package com.pishi.termbridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 *
 * Run #67: on a fresh emulator the app shows OnboardingScreen
 * first (TermBridgeNavGraph gates on onboardingSeen, default
 * false), so "Hosts" is absent until onboarding is dismissed.
 * The test therefore taps Skip — present on every onboarding
 * page — when it is shown, and only then asserts "Hosts".
 * The Skip tap is conditional so the test also passes on a
 * device where onboarding was already seen.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun hostsShellIsDisplayed() {
        // Dismiss first-run onboarding when it is shown; on a
        // device where it was already seen there is no Skip node
        // and this step is a no-op.
        val skipNodes = composeRule.onAllNodesWithText("Skip")
        if (skipNodes.fetchSemanticsNodes().isNotEmpty()) {
            skipNodes[0].performClick()
            composeRule.waitForIdle()
        }
        // The app now starts on the Vault hub; "Hosts" is its first entry.
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
    }
}
