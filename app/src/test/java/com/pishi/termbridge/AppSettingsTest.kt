package com.pishi.termbridge

import com.pishi.termbridge.domain.AppSettings
import com.pishi.termbridge.domain.AppSettingsRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultsAreAlreadyValid() {
        assertEquals(AppSettings(), AppSettingsRules.sanitize(AppSettings()))
    }

    @Test
    fun fontSizeIsClampedToSupportedRange() {
        assertEquals(
            AppSettings.MIN_FONT_SP,
            AppSettingsRules.sanitize(AppSettings(terminalFontSp = 1)).terminalFontSp
        )
        assertEquals(
            AppSettings.MAX_FONT_SP,
            AppSettingsRules.sanitize(AppSettings(terminalFontSp = 400)).terminalFontSp
        )
    }

    @Test
    fun unsupportedChoicesFallBackToDefaults() {
        val cleaned = AppSettingsRules.sanitize(
            AppSettings(scrollbackLines = -5, terminalType = "", keepAliveSeconds = 7)
        )
        assertEquals(AppSettings.DEFAULT_SCROLLBACK, cleaned.scrollbackLines)
        assertEquals(AppSettings.DEFAULT_TERMINAL_TYPE, cleaned.terminalType)
        assertEquals(AppSettings.DEFAULT_KEEPALIVE, cleaned.keepAliveSeconds)
    }

    @Test
    fun nextCyclesAndWraps() {
        val choices = AppSettings.KEEPALIVE_CHOICES
        assertEquals(15, AppSettingsRules.next(choices, 0))
        assertEquals(0, AppSettingsRules.next(choices, choices.last()))
        // An unknown current value starts the cycle from the beginning.
        assertEquals(choices.first(), AppSettingsRules.next(choices, 9999))
    }

    @Test
    fun screenshotBlockingDefaultsToOn() {
        assertTrue(AppSettings().blockScreenshots)
    }
}
