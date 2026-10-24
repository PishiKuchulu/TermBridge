package com.pishi.termbridge

import com.pishi.termbridge.domain.JumpChain
import com.pishi.termbridge.domain.JumpChainValidator
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.ProxySpecParser
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import com.pishi.termbridge.domain.TunnelValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedValidationTest {
    private fun tunnel(
        bindAddress: String = "127.0.0.1",
        bindPort: Int = 8080,
        targetHost: String = "localhost",
        targetPort: Int = 80,
        allowNonLoopback: Boolean = false,
        kind: TunnelKind = TunnelKind.LOCAL
    ) = TunnelConfig(
        hostId = 1L,
        kind = kind,
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
        allowNonLoopback = allowNonLoopback
    )

    @Test
    fun validLocalLoopbackTunnelPasses() {
        assertTrue(TunnelValidator.validate(tunnel()).isEmpty())
    }

    @Test
    fun validRemoteLoopbackTunnelPasses() {
        assertTrue(TunnelValidator.validate(tunnel(kind = TunnelKind.REMOTE)).isEmpty())
    }

    @Test
    fun ipv6LoopbackCountsAsLoopback() {
        assertTrue(TunnelValidator.validate(tunnel(bindAddress = "::1")).isEmpty())
    }

    @Test
    fun nonLoopbackBindWithoutFlagIsRejected() {
        val issues = TunnelValidator.validate(tunnel(bindAddress = "192.168.1.5"))
        assertTrue(issues.any { it.field == "bindAddress" })
    }

    @Test
    fun nonLoopbackBindWithFlagIsAccepted() {
        assertTrue(
            TunnelValidator.validate(
                tunnel(bindAddress = "192.168.1.5", allowNonLoopback = true)
            ).isEmpty()
        )
    }

    @Test
    fun wildcardBindWithoutFlagIsRejected() {
        val issues = TunnelValidator.validate(tunnel(bindAddress = "0.0.0.0"))
        assertTrue(issues.any { it.field == "bindAddress" })
    }

    @Test
    fun wildcardBindWithFlagIsAccepted() {
        assertTrue(
            TunnelValidator.validate(
                tunnel(bindAddress = "0.0.0.0", allowNonLoopback = true)
            ).isEmpty()
        )
    }

    @Test
    fun portZeroAndPortTooLargeAreRejected() {
        assertTrue(TunnelValidator.validate(tunnel(bindPort = 0)).any { it.field == "bindPort" })
        assertTrue(TunnelValidator.validate(tunnel(targetPort = 65536)).any { it.field == "targetPort" })
    }

    @Test
    fun blankTargetHostIsRejected() {
        assertTrue(TunnelValidator.validate(tunnel(targetHost = " ")).any { it.field == "targetHost" })
    }

    @Test
    fun emptyJumpChainIsRejected() {
        assertTrue(JumpChainValidator.validate(JumpChain(1L, emptyList())).isNotEmpty())
    }

    @Test
    fun duplicateHopIsRejected() {
        assertTrue(
            JumpChainValidator.validate(JumpChain(1L, listOf(2L, 3L, 2L))).isNotEmpty()
        )
    }

    @Test
    fun targetAsOwnJumpIsRejected() {
        assertTrue(JumpChainValidator.validate(JumpChain(1L, listOf(2L, 1L))).isNotEmpty())
    }

    @Test
    fun sixHopsAreRejectedAndFivePass() {
        assertTrue(
            JumpChainValidator.validate(JumpChain(1L, listOf(2L, 3L, 4L, 5L, 6L, 7L))).isNotEmpty()
        )
        assertTrue(
            JumpChainValidator.validate(JumpChain(1L, listOf(2L, 3L, 4L, 5L, 6L))).isEmpty()
        )
    }

    @Test
    fun proxySpecSocksFormsParse() {
        val a = ProxySpecParser.parse("socks://proxy.example.com:1080", 1L)
        assertEquals("proxy.example.com", a.config?.proxyHost)
        assertEquals(1080, a.config?.proxyPort)
        assertEquals(ProxyKind.SOCKS, a.config?.kind)
        assertNull(a.error)
        val b = ProxySpecParser.parse("socks5://10.0.0.2:1080", 1L)
        assertEquals("10.0.0.2", b.config?.proxyHost)
        val c = ProxySpecParser.parse("10.0.0.3:1080", 1L)
        assertEquals(ProxyKind.SOCKS, c.config?.kind)
    }

    @Test
    fun proxySpecHttpIsRejectedExplicitly() {
        val result = ProxySpecParser.parse("http://proxy.example.com:8080", 1L)
        assertNull(result.config)
        assertTrue(result.error!!.contains("HTTP"))
    }

    @Test
    fun proxySpecMissingOrBadPortIsRejected() {
        assertNull(ProxySpecParser.parse("proxy.example.com", 1L).config)
        assertNull(ProxySpecParser.parse("proxy.example.com:0", 1L).config)
        assertNull(ProxySpecParser.parse("proxy.example.com:abc", 1L).config)
        assertNull(ProxySpecParser.parse("", 1L).config)
    }

    @Test
    fun proxyFormatRoundTrips() {
        val config = ProxyConfig(1L, ProxyKind.SOCKS, "proxy.example.com", 1080)
        val parsed = ProxySpecParser.parse(ProxySpecParser.format(config), 1L)
        assertEquals(config, parsed.config)
    }

    @Test
    fun proxyValidationRejectsBadPort() {
        assertTrue(
            ProxySpecParser.validate(ProxyConfig(1L, ProxyKind.SOCKS, "h", 0)).isNotEmpty()
        )
        assertTrue(ProxySpecParser.validate(ProxyConfig(1L)).isEmpty())
    }
}
