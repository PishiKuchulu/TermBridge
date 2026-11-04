package com.pishi.termbridge

import com.pishi.termbridge.domain.DesktopProfile
import com.pishi.termbridge.domain.DesktopProfileCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item F-03b: desktop profile codec. */
class DesktopProfileCodecTest {

    @Test
    fun roundTripKeepsEverything() {
        val profiles = listOf(
            DesktopProfile(1, "Home PC", "192.168.1.10", 5900, null),
            DesktopProfile(2, "Office via SSH", "10.0.0.5", 5901, 42L)
        )
        assertEquals(profiles, DesktopProfileCodec.decode(DesktopProfileCodec.encode(profiles)))
    }

    @Test
    fun damagedEntriesDropAndGarbageIsEmpty() {
        assertTrue(DesktopProfileCodec.decode("not json").isEmpty())
        assertTrue(DesktopProfileCodec.decode("").isEmpty())
        assertTrue(DesktopProfileCodec.decode(null).isEmpty())
        val mixed = """[{"id":3,"name":"Ok","host":"h","port":5900},""" +
            """{"id":4,"name":"","host":"h","port":5900},""" +
            """{"id":5,"name":"Bad port","host":"h","port":99999}]"""
        val decoded = DesktopProfileCodec.decode(mixed)
        assertEquals(listOf("Ok"), decoded.map { it.name })
    }
}
