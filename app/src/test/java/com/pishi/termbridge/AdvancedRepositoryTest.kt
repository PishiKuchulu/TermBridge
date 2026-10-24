package com.pishi.termbridge

import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.AdvancedRepositoryException
import com.pishi.termbridge.data.HostEntity
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.JumpChain
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdvancedRepositoryTest {
    private fun hostRow(name: String) = HostEntity(
        name = name,
        hostname = "example.com",
        port = 22,
        username = "admin",
        authType = "PASSWORD",
        notes = "",
        tags = "",
        credentialBlob = null,
        createdAt = 1L,
        updatedAt = 1L
    )

    private suspend fun seedHosts(hostDao: FakeHostDao): List<Long> {
        return listOf(
            hostDao.insert(hostRow("target")),
            hostDao.insert(hostRow("jump-a")),
            hostDao.insert(hostRow("jump-b"))
        )
    }

    @Test
    fun tunnelSaveRoundTrip() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val targetId = seedHosts(hostDao)[0]
        val saved = repo.saveTunnel(
            TunnelConfig(
                hostId = targetId,
                kind = TunnelKind.LOCAL,
                bindAddress = "127.0.0.1",
                bindPort = 8080,
                targetHost = "localhost",
                targetPort = 80,
                label = "Web admin"
            )
        )
        assertTrue(saved.id > 0)
        val all = repo.getTunnelsOnce(targetId)
        assertEquals(1, all.size)
        assertEquals("Web admin", all[0].label)
        assertEquals(TunnelKind.LOCAL, all[0].kind)
    }

    @Test
    fun invalidTunnelIsNotSaved() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val targetId = seedHosts(hostDao)[0]
        try {
            repo.saveTunnel(
                TunnelConfig(
                    hostId = targetId,
                    kind = TunnelKind.LOCAL,
                    bindAddress = "192.168.1.5",
                    bindPort = 8080,
                    targetHost = "localhost",
                    targetPort = 80
                )
            )
            fail("Expected AdvancedRepositoryException")
        } catch (e: AdvancedRepositoryException) {
            assertTrue(e.message!!.contains("loopback"))
        }
        assertTrue(repo.getTunnelsOnce(targetId).isEmpty())
    }

    @Test
    fun tunnelCannotMoveToAnotherHost() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val ids = seedHosts(hostDao)
        val saved = repo.saveTunnel(
            TunnelConfig(
                hostId = ids[0],
                kind = TunnelKind.LOCAL,
                bindAddress = "127.0.0.1",
                bindPort = 8080,
                targetHost = "localhost",
                targetPort = 80
            )
        )
        try {
            repo.saveTunnel(saved.copy(hostId = ids[1]))
            fail("Expected AdvancedRepositoryException")
        } catch (e: AdvancedRepositoryException) {
            assertTrue(e.message!!.contains("another host"))
        }
    }

    @Test
    fun tunnelEnabledFlagFlips() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val targetId = seedHosts(hostDao)[0]
        val saved = repo.saveTunnel(
            TunnelConfig(
                hostId = targetId,
                kind = TunnelKind.REMOTE,
                bindAddress = "127.0.0.1",
                bindPort = 2222,
                targetHost = "127.0.0.1",
                targetPort = 22
            )
        )
        repo.setTunnelEnabled(saved.id, targetId, false)
        assertFalse(repo.getTunnelsOnce(targetId)[0].enabled)
    }

    @Test
    fun jumpChainOrderIsPreservedAndReplaceRemovesOldHops() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val ids = seedHosts(hostDao)
        repo.saveJumpChain(JumpChain(ids[0], listOf(ids[1], ids[2])))
        assertEquals(listOf(ids[1], ids[2]), repo.getJumpChainOnce(ids[0]).jumpHostIds)
        repo.saveJumpChain(JumpChain(ids[0], listOf(ids[2])))
        assertEquals(listOf(ids[2]), repo.getJumpChainOnce(ids[0]).jumpHostIds)
        repo.clearJumpChain(ids[0])
        assertTrue(repo.getJumpChainOnce(ids[0]).jumpHostIds.isEmpty())
    }

    @Test
    fun jumpChainWithMissingHostIsRejected() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val targetId = seedHosts(hostDao)[0]
        try {
            repo.saveJumpChain(JumpChain(targetId, listOf(999L)))
            fail("Expected AdvancedRepositoryException")
        } catch (e: AdvancedRepositoryException) {
            assertTrue(e.message!!.contains("Host not found"))
        }
    }

    @Test
    fun proxySaveGetAndClear() = runTest {
        val hostDao = FakeHostDao()
        val repo = AdvancedRepository(FakeTunnelDao(), FakeJumpHopDao(), FakeProxyDao(), hostDao)
        val targetId = seedHosts(hostDao)[0]
        assertEquals(ProxyKind.NONE, repo.getProxyOnce(targetId).kind)
        repo.saveProxy(ProxyConfig(targetId, ProxyKind.SOCKS, "proxy.example.com", 1080))
        val saved = repo.getProxyOnce(targetId)
        assertEquals(ProxyKind.SOCKS, saved.kind)
        assertEquals(1080, saved.proxyPort)
        repo.saveProxy(ProxyConfig(targetId, ProxyKind.NONE))
        assertEquals(ProxyKind.NONE, repo.getProxyOnce(targetId).kind)
    }

    @Test
    fun hostDuplicateCopiesAdvancedConfigAndDeleteRemovesIt() = runTest {
        val hostDao = FakeHostDao()
        val trustedDao = FakeTrustedHostKeyDao()
        val tunnelDao = FakeTunnelDao()
        val jumpDao = FakeJumpHopDao()
        val proxyDao = FakeProxyDao()
        val advanced = AdvancedRepository(tunnelDao, jumpDao, proxyDao, hostDao)
        val hostRepo = HostRepository(
            hostDao = hostDao,
            trustedHostKeyDao = trustedDao,
            secretCrypto = FakeSecretCrypto(),
            advancedRepository = advanced
        )
        val target = hostRepo.saveHost(
            HostProfile(
                name = "target",
                hostname = "example.com",
                port = 22,
                username = "admin",
                authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("s3cr3t".toCharArray())
        )
        val jump = hostRepo.saveHost(
            HostProfile(
                name = "jump",
                hostname = "jump.example.com",
                port = 22,
                username = "admin",
                authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("jump-secret".toCharArray())
        )
        advanced.saveTunnel(
            TunnelConfig(
                hostId = target.id,
                kind = TunnelKind.LOCAL,
                bindAddress = "127.0.0.1",
                bindPort = 8080,
                targetHost = "localhost",
                targetPort = 80
            )
        )
        advanced.saveJumpChain(JumpChain(target.id, listOf(jump.id)))
        advanced.saveProxy(ProxyConfig(target.id, ProxyKind.SOCKS, "proxy.example.com", 1080))

        val copy = hostRepo.duplicateHost(target.id, "target (copy)")
        // Non-secret config copied; credential is not.
        assertEquals(1, advanced.getTunnelsOnce(copy.id).size)
        assertEquals(listOf(jump.id), advanced.getJumpChainOnce(copy.id).jumpHostIds)
        assertEquals(ProxyKind.SOCKS, advanced.getProxyOnce(copy.id).kind)
        assertFalse(hostRepo.hasCredential(copy.id))

        hostRepo.deleteHost(target.id)
        assertTrue(advanced.getTunnelsOnce(target.id).isEmpty())
        assertTrue(advanced.getJumpChainOnce(target.id).jumpHostIds.isEmpty())
        assertEquals(ProxyKind.NONE, advanced.getProxyOnce(target.id).kind)
        // The duplicate's config survives the original's deletion.
        assertEquals(1, advanced.getTunnelsOnce(copy.id).size)
    }
}
