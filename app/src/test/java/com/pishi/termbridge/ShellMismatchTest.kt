package com.pishi.termbridge

import com.pishi.termbridge.domain.DetectedOs
import com.pishi.termbridge.domain.OsFamily
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.ShellMismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit #3 (A-20): probed-OS family vs declared-shell family. */
class ShellMismatchTest {

    private val windows = DetectedOs(name = "Microsoft Windows", detail = "version 10.0.20348")
    private val ubuntu = DetectedOs(name = "Ubuntu 24.04.1 LTS", detail = "Linux 6.8.0 x86_64")
    private val freeBsd = DetectedOs(name = "FreeBSD", detail = "14.0-RELEASE amd64")

    @Test
    fun familiesOfProbeResults() {
        assertEquals(OsFamily.WINDOWS, ShellMismatch.familyOf(windows))
        assertEquals(OsFamily.POSIX, ShellMismatch.familyOf(ubuntu))
        assertEquals(OsFamily.POSIX, ShellMismatch.familyOf(freeBsd))
    }

    @Test
    fun familiesOfDeclaredShells() {
        assertEquals(OsFamily.POSIX, ShellMismatch.familyOf(RemoteShell.POSIX))
        assertEquals(OsFamily.WINDOWS, ShellMismatch.familyOf(RemoteShell.POWERSHELL))
        assertEquals(OsFamily.WINDOWS, ShellMismatch.familyOf(RemoteShell.CMD))
    }

    @Test
    fun windowsProbeContradictsPosixDeclaration() {
        assertTrue(ShellMismatch.isMismatch(windows, RemoteShell.POSIX))
    }

    @Test
    fun posixProbeContradictsWindowsDeclarations() {
        assertTrue(ShellMismatch.isMismatch(ubuntu, RemoteShell.POWERSHELL))
        assertTrue(ShellMismatch.isMismatch(ubuntu, RemoteShell.CMD))
        assertTrue(ShellMismatch.isMismatch(freeBsd, RemoteShell.POWERSHELL))
    }

    @Test
    fun matchingFamiliesDoNotWarn() {
        assertFalse(ShellMismatch.isMismatch(windows, RemoteShell.POWERSHELL))
        assertFalse(ShellMismatch.isMismatch(windows, RemoteShell.CMD))
        assertFalse(ShellMismatch.isMismatch(ubuntu, RemoteShell.POSIX))
        assertFalse(ShellMismatch.isMismatch(freeBsd, RemoteShell.POSIX))
    }

    @Test
    fun failedProbeNeverWarns() {
        // No signal is not a mismatch, whatever was declared.
        assertFalse(ShellMismatch.isMismatch(null, RemoteShell.POSIX))
        assertFalse(ShellMismatch.isMismatch(null, RemoteShell.POWERSHELL))
        assertFalse(ShellMismatch.isMismatch(null, RemoteShell.CMD))
    }
}
