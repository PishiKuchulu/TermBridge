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
            AppSettings(terminalType = "", keepAliveSeconds = 7)
        )
        assertEquals(AppSettings.DEFAULT_TERMINAL_TYPE, cleaned.terminalType)
        assertEquals(AppSettings.DEFAULT_KEEPALIVE, cleaned.keepAliveSeconds)
    }

    @Test
    fun scrollbackIsABoundedRangeNotAChoiceList() {
        // Item T-23 changed the contract: any value inside the
        // safe bounds is kept (custom values), values outside
        // clamp to the bound instead of falling back.
        assertEquals(
            1234,
            AppSettingsRules.sanitize(AppSettings(scrollbackLines = 1234)).scrollbackLines
        )
        assertEquals(
            AppSettings.MIN_SCROLLBACK,
            AppSettingsRules.sanitize(AppSettings(scrollbackLines = -5)).scrollbackLines
        )
        assertEquals(
            AppSettings.MAX_SCROLLBACK,
            AppSettingsRules.sanitize(AppSettings(scrollbackLines = 999_999)).scrollbackLines
        )
    }

    @Test
    fun keepAliveMaxMissesSanitizesToChoices() {
        assertEquals(
            5,
            AppSettingsRules.sanitize(AppSettings(keepAliveMaxMisses = 5)).keepAliveMaxMisses
        )
        assertEquals(
            AppSettings.DEFAULT_KEEPALIVE_MAX_MISSES,
            AppSettingsRules.sanitize(AppSettings(keepAliveMaxMisses = 4)).keepAliveMaxMisses
        )
    }

    @Test
    fun scrollbackEstimateScalesWithLines() {
        assertEquals(0L, com.pishi.termbridge.domain.scrollbackMemoryEstimateBytes(0))
        assertTrue(com.pishi.termbridge.domain.scrollbackMemoryEstimateBytes(1000) > 0L)
        assertEquals(
            2 * com.pishi.termbridge.domain.scrollbackMemoryEstimateBytes(1000),
            com.pishi.termbridge.domain.scrollbackMemoryEstimateBytes(2000)
        )
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
