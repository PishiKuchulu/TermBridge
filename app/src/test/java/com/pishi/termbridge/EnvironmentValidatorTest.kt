package com.pishi.termbridge

import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.EnvironmentValidator
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.WindowsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentValidatorTest {
    // --- Env names ---

    @Test
    fun validEnvNamesPass() {
        assertTrue(EnvironmentValidator.isValidEnvName("EDITOR"))
        assertTrue(EnvironmentValidator.isValidEnvName("_private1"))
        assertTrue(EnvironmentValidator.isValidEnvName("LC_ALL"))
    }

    @Test
    fun invalidEnvNamesFail() {
        assertFalse(EnvironmentValidator.isValidEnvName(""))
        assertFalse(EnvironmentValidator.isValidEnvName("1ABC"))
        assertFalse(EnvironmentValidator.isValidEnvName("MY-VAR"))
        assertFalse(EnvironmentValidator.isValidEnvName("MY VAR"))
        assertFalse(EnvironmentValidator.isValidEnvName("MY.VAR"))
    }

    @Test
    fun loaderInjectionNamesAreBlocked() {
        assertFalse(EnvironmentValidator.isValidEnvName("LD_PRELOAD"))
        assertFalse(EnvironmentValidator.isValidEnvName("LD_LIBRARY_PATH"))
        assertFalse(EnvironmentValidator.isValidEnvName("DYLD_INSERT_LIBRARIES"))
        assertTrue(EnvironmentValidator.validateEnvName("LD_PRELOAD") != null)
    }

    @Test
    fun envValueRejectsNulAndOversize() {
        assertNull(EnvironmentValidator.validateEnvValue("normal value"))
        assertNull(EnvironmentValidator.validateEnvValue(""))
        assertTrue(EnvironmentValidator.validateEnvValue("a\u0000b") != null)
        assertTrue(
            EnvironmentValidator.validateEnvValue("x".repeat(4097)) != null
        )
    }

    // --- POSIX directories ---

    @Test
    fun posixDirectoryNormalises() {
        assertEquals(
            "/home/user",
            EnvironmentValidator.canonicalDirectory("/home/user/../user", RemoteShell.POSIX)
        )
        assertEquals(
            "/",
            EnvironmentValidator.canonicalDirectory("/../..", RemoteShell.POSIX)
        )
        assertEquals("", EnvironmentValidator.canonicalDirectory("", RemoteShell.POSIX))
        assertNull(
            EnvironmentValidator.canonicalDirectory("relative/path", RemoteShell.POSIX)
        )
        assertNull(
            EnvironmentValidator.canonicalDirectory("/has\u0000nul", RemoteShell.POSIX)
        )
    }

    // --- Windows paths ---

    @Test
    fun windowsDrivePathsNormalise() {
        assertEquals("C:\\Users\\name", WindowsPath.normalize("C:/Users/name"))
        assertEquals("C:\\Users\\name", WindowsPath.normalize("C:\\Users\\.\\name"))
        assertEquals("C:\\", WindowsPath.normalize("C:\\a\\.."))
        assertEquals("C:\\Users", WindowsPath.normalize("c:\\Users\\sub\\.."))
    }

    @Test
    fun windowsUncPathsNormalise() {
        assertEquals(
            "\\\\server\\share\\dir",
            WindowsPath.normalize("\\\\server\\share\\dir")
        )
        assertEquals(
            "\\\\server\\share",
            WindowsPath.normalize("\\\\server\\share\\a\\..")
        )
    }

    @Test
    fun windowsPathsRejectRelativeAndIllegal() {
        assertNull(WindowsPath.normalize("Users\\name"))
        assertNull(WindowsPath.normalize("\\Users\\name"))
        assertNull(WindowsPath.normalize("C:\\bad\"name"))
        assertNull(WindowsPath.normalize("C:\\bad|name"))
        assertNull(WindowsPath.normalize("C:\\bad*name"))
        assertNull(WindowsPath.normalize(""))
    }

    @Test
    fun windowsDirectoryValidationUsesShell() {
        assertNull(
            EnvironmentValidator.validateRemoteDirectory(
                "C:\\Users\\name",
                RemoteShell.POWERSHELL
            )
        )
        assertTrue(
            EnvironmentValidator.validateRemoteDirectory(
                "/home/user",
                RemoteShell.CMD
            ) != null
        )
        assertTrue(
            EnvironmentValidator.validateRemoteDirectory(
                "C:\\Users\\name",
                RemoteShell.POSIX
            ) != null
        )
    }

    // --- Local SAF URIs ---

    @Test
    fun localDirectoryMustBeContentUri() {
        assertNull(EnvironmentValidator.validateLocalDirectoryUri(null))
        assertNull(EnvironmentValidator.validateLocalDirectoryUri(""))
        assertNull(
            EnvironmentValidator.validateLocalDirectoryUri("content://com.example/tree/1")
        )
        assertTrue(
            EnvironmentValidator.validateLocalDirectoryUri("/storage/emulated/0/Docs") != null
        )
        assertTrue(
            EnvironmentValidator.validateLocalDirectoryUri("file:///storage/x") != null
        )
    }

    // --- cd rendering ---

    @Test
    fun cdCommandQuotesPosixAndPowershell() {
        assertEquals("cd '/home/my dir'", CdCommand.render("/home/my dir", RemoteShell.POSIX))
        assertEquals(
            "cd '/home/it'\\''s'",
            CdCommand.render("/home/it's", RemoteShell.POSIX)
        )
        assertEquals(
            "cd 'C:\\Users\\name'",
            CdCommand.render("C:\\Users\\name", RemoteShell.POWERSHELL)
        )
    }

    @Test
    fun cdCommandUsesDriveSwitchForCmd() {
        assertEquals(
            "cd /d \"C:\\Users\\name\"",
            CdCommand.render("C:\\Users\\name", RemoteShell.CMD)
        )
    }
}
