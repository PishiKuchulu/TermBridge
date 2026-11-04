package com.pishi.termbridge

import com.pishi.termbridge.domain.OsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Item T-16: OS probe output parsing. */
class OsProbeTest {

    @Test
    fun ubuntuFromOsRelease() {
        val output = """
            Linux 6.8.0-31-generic x86_64
            ---TB---
            PRETTY_NAME="Ubuntu 24.04.1 LTS"
            NAME="Ubuntu"
            VERSION_ID="24.04"
        """.trimIndent()
        val detected = OsProbe.parsePosix(output)
        assertEquals("Ubuntu 24.04.1 LTS", detected?.name)
        assertEquals("Linux 6.8.0-31-generic x86_64", detected?.detail)
        assertEquals(
            "Ubuntu 24.04.1 LTS (Linux 6.8.0-31-generic x86_64)",
            detected?.display
        )
    }

    @Test
    fun namePlusVersionWhenNoPrettyName() {
        val output = """
            Linux 5.10.0 aarch64
            ---TB---
            NAME="Debian GNU/Linux"
            VERSION_ID="12"
        """.trimIndent()
        assertEquals("Debian GNU/Linux 12", OsProbe.parsePosix(output)?.name)
    }

    @Test
    fun bareUnameFallback() {
        val output = "FreeBSD 14.0-RELEASE amd64\n---TB---\n"
        val detected = OsProbe.parsePosix(output)
        assertEquals("FreeBSD", detected?.name)
        assertEquals("14.0-RELEASE amd64", detected?.detail)
    }

    @Test
    fun emptyOutputDetectsNothing() {
        assertNull(OsProbe.parsePosix(""))
        assertNull(OsProbe.parsePosix("---TB---\n"))
    }

    @Test
    fun windowsVerLine() {
        val detected = OsProbe.parseWindows(
            "\r\nMicrosoft Windows [Version 10.0.22631.4167]\r\n"
        )
        assertEquals("Microsoft Windows", detected?.name)
        assertEquals("version 10.0.22631.4167", detected?.detail)
        assertNull(OsProbe.parseWindows("not a windows box"))
    }
}
