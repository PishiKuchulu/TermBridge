package com.pishi.termbridge

import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.ControlChars
import com.pishi.termbridge.domain.EnvironmentValidator
import com.pishi.termbridge.domain.RemoteShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Review #2 F-03 / F-22 / F-24: control bytes must never be typed into a shell. */
class ControlCharsTest {
    private val ctrlC = "\u0003"
    private val esc = "\u001B"

    @Test
    fun classifiesControlCharacters() {
        for (c in listOf("\u0000", "\u0003", "\u001B", "\n", "\r", "\t", "\u007F", "\u0085")) {
            assertTrue(ControlChars.isControl(c[0]))
        }
        assertFalse(ControlChars.isControl('a'))
        assertFalse(ControlChars.isControl(' '))
        assertFalse(ControlChars.isControl('é'))
        assertTrue(ControlChars.containsControlExceptNewline("a\u0003b"))
        assertFalse(ControlChars.containsControlExceptNewline("a\nb"))
    }

    @Test
    fun shellQuoteRefusesControlBytesInPosixAndPowershell() {
        for (shell in listOf(RemoteShell.POSIX, RemoteShell.POWERSHELL)) {
            for (bad in listOf("x${ctrlC}\nrm -rf ~\n", "x${esc}[A", "a\rb", "a\nb", "a\tb")) {
                try {
                    CommandTemplate.shellQuote(bad, shell)
                    fail("control characters must be refused for $shell")
                } catch (e: IllegalArgumentException) {
                    assertTrue(e.message!!.contains("control characters"))
                }
            }
        }
        // Ordinary text, including quotes and non-ASCII, still quotes.
        assertEquals("'it'\\''s é'", CommandTemplate.shellQuote("it's é", RemoteShell.POSIX))
    }

    @Test
    fun renderRefusesValueWithControlBytes() {
        try {
            CommandTemplate.render("echo {{x}}", mapOf("x" to "hi${ctrlC}\nid\n"))
            fail("render must refuse")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun posixWorkingDirectoryWithControlBytesIsRejected() {
        val bad = "/tmp/${ctrlC}\nrm -rf ~\n"
        assertNull(EnvironmentValidator.canonicalDirectory(bad, RemoteShell.POSIX))
        assertTrue(EnvironmentValidator.validateRemoteDirectory(bad, RemoteShell.POSIX) != null)
        assertEquals("/home/user", EnvironmentValidator.canonicalDirectory("/home/user", RemoteShell.POSIX))
    }

    @Test
    fun cmdWorkingDirectoryRejectsPercentExpansion() {
        assertNull(EnvironmentValidator.canonicalDirectory("C:\\Users\\%USERNAME%", RemoteShell.CMD))
        assertEquals(
            "C:\\Users\\name",
            EnvironmentValidator.canonicalDirectory("C:\\Users\\name", RemoteShell.CMD)
        )
        try {
            CdCommand.render("C:\\x\\%PATH%", RemoteShell.CMD)
            fail("cmd cd must refuse %")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun visualizeShowsControlBytesAndKeepsNewlines() {
        assertEquals("a<0x03>b\nc<0x1B>", ControlChars.visualize("a\u0003b\nc\u001B"))
        assertEquals("plain text", ControlChars.visualize("plain text"))
    }
}
