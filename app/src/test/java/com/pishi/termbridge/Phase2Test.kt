package com.pishi.termbridge

import com.pishi.termbridge.domain.ConnectionLogCodec
import com.pishi.termbridge.domain.ConnectionLogEntry
import com.pishi.termbridge.domain.ConnectionOutcome
import com.pishi.termbridge.domain.QuickConnect
import com.pishi.termbridge.domain.SubnetPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectTest {
    @Test
    fun parsesCommonForms() {
        assertEquals(Triple("example.com", 22, null), QuickConnect.parse("example.com")!!.let { Triple(it.host, it.port, it.username) })
        assertEquals(Triple("10.0.0.5", 2222, "root"), QuickConnect.parse("root@10.0.0.5:2222")!!.let { Triple(it.host, it.port, it.username) })
        assertEquals(Triple("h.example", 2200, "bob"), QuickConnect.parse("ssh bob@h.example -p 2200")!!.let { Triple(it.host, it.port, it.username) })
        assertEquals(Triple("h.example", 2200, "bob"), QuickConnect.parse("ssh -p 2200 bob@h.example")!!.let { Triple(it.host, it.port, it.username) })
        assertEquals(Triple("h.example", 22, "bob"), QuickConnect.parse("ssh -l bob h.example")!!.let { Triple(it.host, it.port, it.username) })
        assertEquals(2222, QuickConnect.parse("ssh -p2222 a@b")!!.port)
    }

    @Test
    fun rejectsAnythingAmbiguousOrUnsafe() {
        for (bad in listOf("", "   ", "ssh", "a b c", "user@", "@host", "host:0", "host:70000", "host:abc",
            "ssh -o StrictHostKeyChecking=no h", "h;rm -rf /", "h$(id)", "us er@host", "host:22 -p 2200")) {
            assertNull("should reject: $bad", QuickConnect.parse(bad))
        }
    }
}

class SubnetPlannerTest {
    @Test
    fun slash24SkipsSelfNetworkAndBroadcast() {
        val hosts = SubnetPlanner.hostsToScan("192.168.1.20", 24)
        assertEquals(253, hosts.size)
        assertFalse(hosts.contains("192.168.1.20"))
        assertFalse(hosts.contains("192.168.1.0"))
        assertFalse(hosts.contains("192.168.1.255"))
        assertEquals("192.168.1.1", hosts.first())
    }

    @Test
    fun neverScansWiderThanSlash24() {
        assertEquals(253, SubnetPlanner.hostsToScan("10.1.2.3", 8).size)
    }

    @Test
    fun smallerSubnetsAreRespected() {
        assertEquals(1, SubnetPlanner.hostsToScan("10.0.0.1", 30).size)
    }

    @Test
    fun garbageInputGivesNothing() {
        assertTrue(SubnetPlanner.hostsToScan("not-an-ip", 24).isEmpty())
        assertTrue(SubnetPlanner.hostsToScan("300.1.1.1", 24).isEmpty())
    }
}

class ConnectionLogCodecTest {
    @Test
    fun roundTripKeepsEverything() {
        val list = listOf(
            ConnectionLogEntry(2L, "b", ConnectionOutcome.CLOSED, 5000, ""),
            ConnectionLogEntry(1L, "a", ConnectionOutcome.FAILED, 0, "Network error.")
        )
        assertEquals(list, ConnectionLogCodec.decode(ConnectionLogCodec.encode(list)))
    }

    @Test
    fun damagedOrEmptyInputDegradesToEmpty() {
        assertTrue(ConnectionLogCodec.decode(null).isEmpty())
        assertTrue(ConnectionLogCodec.decode("{not json").isEmpty())
        assertTrue(ConnectionLogCodec.decode("[{\"o\":\"NOPE\"}]").isEmpty())
    }

    @Test
    fun historyIsCappedNewestFirst() {
        var list = emptyList<ConnectionLogEntry>()
        repeat(ConnectionLogCodec.MAX_ENTRIES + 20) { i ->
            list = ConnectionLogCodec.prepend(list, ConnectionLogEntry(i.toLong(), "h$i", ConnectionOutcome.CONNECTED))
        }
        assertEquals(ConnectionLogCodec.MAX_ENTRIES, list.size)
        assertEquals("h${ConnectionLogCodec.MAX_ENTRIES + 19}", list.first().hostName)
        assertNotNull(list.last())
    }
}
