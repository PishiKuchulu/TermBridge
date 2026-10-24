package com.pishi.termbridge

import com.pishi.termbridge.domain.WindowsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prompt 15 gap closure: WindowsPath (prompt 09) had no test.
 * Lexical normalisation only — it never touches a filesystem —
 * so every rule (drive/UNC roots, dot handling, root clamping,
 * illegal characters) is pinned here.
 */
class WindowsPathTest {
    @Test
    fun drivePathNormalisesForwardSlashes() {
        assertEquals("C:\\Users\\name", WindowsPath.normalize("C:/Users/name"))
    }

    @Test
    fun driveLetterIsUppercased() {
        assertEquals("D:\\data", WindowsPath.normalize("d:\\data"))
    }

    @Test
    fun dotSegmentsAreDropped() {
        assertEquals("C:\\a\\b", WindowsPath.normalize("C:\\a\\.\\b"))
    }

    @Test
    fun dotDotPopsAndClampsAtDriveRoot() {
        assertEquals("C:\\b", WindowsPath.normalize("C:\\a\\..\\b"))
        assertEquals("C:\\", WindowsPath.normalize("C:\\..\\.."))
    }

    @Test
    fun uncPathKeepsServerAndShareAsRoot() {
        assertEquals("\\\\srv\\share\\dir", WindowsPath.normalize("\\\\srv\\share\\dir"))
        assertEquals("\\\\srv\\share", WindowsPath.normalize("\\\\srv\\share\\.."))
    }

    @Test
    fun trailingSeparatorIsRemovedExceptAtRoot() {
        assertEquals("C:\\a", WindowsPath.normalize("C:\\a\\"))
        assertEquals("C:\\", WindowsPath.normalize("C:\\"))
    }

    @Test
    fun relativePathsAreRejected() {
        assertNull(WindowsPath.normalize("Users\\name"))
        assertNull(WindowsPath.normalize("relative/path"))
    }

    @Test
    fun illegalCharactersAndNulAreRejected() {
        assertNull(WindowsPath.normalize("C:\\a<b"))
        assertNull(WindowsPath.normalize("C:\\a|b"))
        assertNull(WindowsPath.normalize("C:\\a?b"))
        assertNull(WindowsPath.normalize("C:\\a*b"))
        assertNull(WindowsPath.normalize("C:\\a\"b"))
        assertNull(WindowsPath.normalize("C:\\a\u0000b"))
        assertNull(WindowsPath.normalize(""))
    }

    @Test
    fun emptySegmentsAreIgnored() {
        assertEquals("C:\\a\\b", WindowsPath.normalize("C:\\\\a\\\\b"))
    }
}
