package com.pishi.termbridge

import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostEnvironmentSettings
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.JumpChain
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test world: real repositories over in-memory fakes, exactly
 *  like the prompt-04/08/09/10 repository tests. The Keystore
 *  crypto is the in-memory AES-GCM test double (test sources
 *  only) — the point here is service behaviour, not Keystore
 *  hardware, which the instrumented tests cover. */
private class BackupWorld {
    val hostDao = FakeHostDao()
    val trustedDao = FakeTrustedHostKeyDao()
    val commandDao = FakeCommandButtonDao()
    val settingsDao = FakeHostSettingsDao()
    val envDao = FakeHostEnvVarDao()
    val tunnelDao = FakeTunnelDao()
    val jumpDao = FakeJumpHopDao()
    val proxyDao = FakeProxyDao()
    val crypto = FakeSecretCrypto()

    val advancedRepo = AdvancedRepository(tunnelDao, jumpDao, proxyDao, hostDao)
    val hostRepo = HostRepository(
        hostDao, trustedDao, crypto,
        commandButtonDao = commandDao,
        hostSettingsDao = settingsDao,
        hostEnvVarDao = envDao,
        advancedRepository = advancedRepo
    )
    val commandRepo = CommandRepository(commandDao)
    val envRepo = HostEnvironmentRepository(settingsDao, envDao, crypto)
    val service = BackupService(hostRepo, commandRepo, envRepo, advancedRepo)

    suspend fun seedHost(
        name: String = "Home server",
        hostname: String = "192.168.1.10",
        password: String = "source-password-123"
    ): HostProfile {
        val host = hostRepo.saveHost(
            HostProfile(
                name = name, hostname = hostname, port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password(password.toCharArray())
        )
        commandRepo.saveButton(
            CommandButton(
                hostId = host.id, label = "Update", mode = CommandMode.EXECUTE_ONE,
                template = "sudo apt update"
            )
        )
        envRepo.saveSettings(
            HostEnvironmentSettings(
                hostId = host.id, remoteWorkingDirectory = "/home/admin",
                localDirectoryUri = null, remoteShell = RemoteShell.POSIX
            )
        )
        envRepo.saveEnvVar(host.id, 0L, "APP_MODE", "secret-env-value-456".toCharArray())
        advancedRepo.saveTunnel(
            TunnelConfig(
                hostId = host.id, kind = TunnelKind.LOCAL,
                bindAddress = "127.0.0.1", bindPort = 8080,
                targetHost = "localhost", targetPort = 80, label = "web"
            )
        )
        advancedRepo.saveProxy(
            ProxyConfig(host.id, ProxyKind.SOCKS, "127.0.0.1", 1080)
        )
        return host
    }
}

class BackupServiceTest {
    @Test
    fun plainExportContainsNoSecrets() = runTest {
        val world = BackupWorld()
        world.seedHost()
        val text = world.service.buildExport(false, null)
        assertFalse(text.contains("source-password-123"))
        assertFalse(text.contains("secret-env-value-456"))
        // The export still describes the host and its button.
        assertTrue(text.contains("Home server"))
        assertTrue(text.contains("sudo apt update"))
    }

    @Test
    fun plainExportFlagsCredentialAsNotIncluded() = runTest {
        val world = BackupWorld()
        world.seedHost()
        val preview = world.service.parseForPreview(
            world.service.buildExport(false, null), null
        )
        assertFalse(preview.hasCredentials)
        assertTrue(preview.document.hosts.single().hasCredential)
        assertNull(preview.document.hosts.single().credential)
    }

    @Test
    fun encryptedRoundTripRestoresEverything() = runTest {
        val source = BackupWorld()
        val host = source.seedHost()
        val hop = source.hostRepo.saveHost(
            HostProfile(
                name = "Jump box", hostname = "203.0.113.5", port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("jump-password-789".toCharArray())
        )
        source.advancedRepo.saveJumpChain(JumpChain(host.id, listOf(hop.id)))

        val text = source.service.buildExport(true, "correct-horse-battery".toCharArray())

        val target = BackupWorld()
        val preview = target.service.parseForPreview(
            text, "correct-horse-battery".toCharArray()
        )
        assertTrue(preview.hasCredentials)
        assertTrue(preview.conflicts.isEmpty())
        val result = target.service.applyImport(preview, false)
        assertEquals(2, result.importedNames.size)
        assertTrue(result.warnings.isEmpty())

        val restored = target.hostRepo.getHosts().first()
            .first { it.hostname == "192.168.1.10" }
        assertArrayEquals(
            "source-password-123".toByteArray(),
            target.hostRepo.loadCredential(restored.id)
        )
        val envValues = target.envRepo.loadEnvVarsForConnection(restored.id).values
        assertEquals(1, envValues.size)
        assertEquals("APP_MODE", envValues.single().name)
        assertArrayEquals(
            "secret-env-value-456".toByteArray(), envValues.single().value
        )
        val buttons = target.commandRepo.getButtons(restored.id).first()
        assertEquals(listOf("Update"), buttons.map { it.label })
        val tunnels = target.advancedRepo.getTunnelsOnce(restored.id)
        assertEquals(listOf("web"), tunnels.map { it.label })
        val proxy = target.advancedRepo.getProxyOnce(restored.id)
        assertEquals(ProxyKind.SOCKS, proxy.kind)
        val restoredHop = target.hostRepo.getHosts().first()
            .first { it.hostname == "203.0.113.5" }
        val chain = target.advancedRepo.getJumpChainOnce(restored.id)
        assertEquals(listOf(restoredHop.id), chain.jumpHostIds)
    }

    @Test
    fun importNeverTouchesTrustRecords() = runTest {
        val source = BackupWorld()
        source.seedHost()
        val text = source.service.buildExport(true, "correct-horse-battery".toCharArray())

        val target = BackupWorld()
        target.hostRepo.trustHostKey("192.168.1.10:22", "SHA256:kept", "ssh-ed25519")
        val preview = target.service.parseForPreview(
            text, "correct-horse-battery".toCharArray()
        )
        target.service.applyImport(preview, false)
        // The pre-existing trust record survives, and no new trust
        // record appears for the imported host's endpoint.
        assertEquals("SHA256:kept", target.hostRepo.getTrustedFingerprint("192.168.1.10:22"))
    }

    @Test
    fun conflictSkippedByDefaultAndExistingUntouched() = runTest {
        val source = BackupWorld()
        source.seedHost()
        val text = source.service.buildExport(false, null)

        val target = BackupWorld()
        val existing = target.hostRepo.saveHost(
            HostProfile(
                name = "Existing", hostname = "192.168.1.10", port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("existing-password".toCharArray())
        )
        val preview = target.service.parseForPreview(text, null)
        assertEquals(1, preview.conflicts.size)
        val result = target.service.applyImport(preview, false)
        assertEquals(listOf("Home server"), result.skippedNames)
        assertTrue(result.importedNames.isEmpty())
        // Existing host is exactly as it was: name, credential,
        // and no imported button.
        assertEquals("Existing", target.hostRepo.getHost(existing.id)?.name)
        assertArrayEquals(
            "existing-password".toByteArray(),
            target.hostRepo.loadCredential(existing.id)
        )
        assertTrue(target.commandRepo.getButtons(existing.id).first().isEmpty())
    }

    @Test
    fun plainReplaceKeepsExistingCredential() = runTest {
        val source = BackupWorld()
        source.seedHost()
        val text = source.service.buildExport(false, null)

        val target = BackupWorld()
        val existing = target.hostRepo.saveHost(
            HostProfile(
                name = "Existing", hostname = "192.168.1.10", port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("existing-password".toCharArray())
        )
        target.commandRepo.saveButton(
            CommandButton(
                hostId = existing.id, label = "Old button",
                mode = CommandMode.INSERT_ONLY, template = "ls"
            )
        )
        val preview = target.service.parseForPreview(text, null)
        val result = target.service.applyImport(preview, true)
        assertEquals(listOf("Home server"), result.replacedNames)
        // Credential preserved (plain import carries none), but the
        // button set is replaced, not merged.
        assertArrayEquals(
            "existing-password".toByteArray(),
            target.hostRepo.loadCredential(existing.id)
        )
        assertEquals(
            listOf("Update"),
            target.commandRepo.getButtons(existing.id).first().map { it.label }
        )
    }

    @Test
    fun encryptedReplaceReplacesCredential() = runTest {
        val source = BackupWorld()
        source.seedHost(password = "new-password-999")
        val text = source.service.buildExport(true, "correct-horse-battery".toCharArray())

        val target = BackupWorld()
        val existing = target.hostRepo.saveHost(
            HostProfile(
                name = "Existing", hostname = "192.168.1.10", port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("existing-password".toCharArray())
        )
        val preview = target.service.parseForPreview(
            text, "correct-horse-battery".toCharArray()
        )
        target.service.applyImport(preview, true)
        assertArrayEquals(
            "new-password-999".toByteArray(),
            target.hostRepo.loadCredential(existing.id)
        )
    }

    @Test
    fun chainSkippedWhenHopHostSkipped() = runTest {
        val source = BackupWorld()
        val host = source.seedHost()
        val hop = source.hostRepo.saveHost(
            HostProfile(
                name = "Jump box", hostname = "203.0.113.5", port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("jump-password-789".toCharArray())
        )
        source.advancedRepo.saveJumpChain(JumpChain(host.id, listOf(hop.id)))
        val text = source.service.buildExport(false, null)

        val target = BackupWorld()
        // Only the hop host already exists, so it is skipped; the
        // target host imports, but its chain must not be partial.
        target.hostRepo.saveHost(
            HostProfile(
                name = "Existing jump", hostname = "203.0.113.5", port = 22,
                username = "admin", authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("existing-password".toCharArray())
        )
        val preview = target.service.parseForPreview(text, null)
        val result = target.service.applyImport(preview, false)
        assertTrue(result.importedNames.contains("Home server"))
        assertTrue(result.skippedNames.contains("Jump box"))
        assertTrue(result.warnings.any { it.contains("Jump chain") })
        val restored = target.hostRepo.getHosts().first()
            .first { it.hostname == "192.168.1.10" }
        assertTrue(target.advancedRepo.getJumpChainOnce(restored.id).jumpHostIds.isEmpty())
    }
}
