package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 18 (audit-17 R1): the unconfirmed-shell warning rule.
 * The dialog shows the warning exactly when a parameterised
 * button is prepared for a host whose shell was never
 * confirmed; the predicate is pure so the rule is pinned here
 * without a Compose test. Wiring (ViewModel flag ->
 * SessionScreen -> ParameterDialog) is verified by read in
 * the phase report, not by this file.
 */
class ShellDefaultWarningTest {

    @Test
    fun warnsForParametersWithUnconfirmedShell() {
        assertTrue(CommandTemplate.warnUnconfirmedShell(true, false))
    }

    @Test
    fun noWarningForParametersWithConfirmedShell() {
        assertFalse(CommandTemplate.warnUnconfirmedShell(true, true))
    }

    @Test
    fun noWarningForParameterFreeButtonWithUnconfirmedShell() {
        // No quoting happens, so there is nothing to warn about:
        // a parameter-free button prepares identically on every
        // shell, including an unconfirmed default.
        assertFalse(CommandTemplate.warnUnconfirmedShell(false, false))
    }

    @Test
    fun noWarningForParameterFreeButtonWithConfirmedShell() {
        assertFalse(CommandTemplate.warnUnconfirmedShell(false, true))
    }
}
