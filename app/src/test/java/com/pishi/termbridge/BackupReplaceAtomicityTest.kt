package com.pishi.termbridge

import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.TransactionRunner
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.BackupDocument
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.ExportButton
import com.pishi.termbridge.domain.ExportHost
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.ImportConflict
import com.pishi.termbridge.domain.ImportPreview
import com.pishi.termbridge.domain.ProxyKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security fix 16 (M3): import REPLACE must be atomic per host.
 * The old code saved the new profile, deleted the old children,
 * then applied new children one by one with warnings — a crash
 * or a rejected child left a new profile with partial children
 * and the old configuration already deleted.
 *
 * What this test proves (and what it does not): the service
 * routes the whole replace unit through exactly one
 * [TransactionRunner.run] call, and a child failure aborts
 * the replace (host not reported as replaced, failure warned).
 * The rollback itself is the runner's job: this test's runner
 * snapshots via repository reads and restores on failure,
 * which is the same contract Room's withTransaction honours
 * in production. Real Room atomicity is NOT JVM-executable
 * (same caveat as prompt 15's transaction test) — the
 * production runner is database.withTransaction, and the
 * fixture/device run must confirm it. NOT RUN (sandbox
 * Gradle limits).
 */
class BackupReplaceAtomicityTest {
    /**
     * Test-double runner: snapshots the single seeded host's
     * config through the repositories, runs the block, and on
     * failure restores the snapshot — the rollback semantics a
     * real transaction runner provides.
     */
    private class SnapshotRestoreRunner(
        private val hostRepo: HostRepository,
        private val commandRepo: CommandRepository,
        private val envRepo: HostEnvironmentRepository,
        private val advancedRepo: AdvancedRepository
    ) : TransactionRunner {
        var runs = 0

        override suspend fun <T> run(block: suspend () -> T): T {
            runs++
            val host = hostRepo.getHosts().first().first()
            val buttons = commandRepo.getButtons(host.id).first()
                .mapNotNull { commandRepo.getButton(it.id) }
            val settings = envRepo.getSettingsOnce(host.id)
            val envValues = envRepo.loadEnvVarsForConnection(host.id).values
                .map { it.name to String(it.value, Charsets.UTF_8) }
            val tunnels = advancedRepo.getTunnelsOnce(host.id)
            val proxy = advancedRepo.getProxyOnce(host.id)
            try {
                return block()
            } catch (e: Exception) {
                commandRepo.deleteForHost(host.id)
                envRepo.deleteForHost(host.id)
                advancedRepo.deleteForHost(host.id)
                // Re-saving the old profile with no credential
                // keeps the existing blob (auth type unchanged).
                hostRepo.saveHost(host, null)
                for (button in buttons) {
                    // Fresh ids: the old rows were deleted above,
                    // and saveButton rejects an unknown id.
                    commandRepo.saveButton(button.copy(id = 0L))
                }
                if (settings != null) envRepo.saveSettings(settings)
                for ((name, value) in envValues) {
                    envRepo.saveEnvVar(host.id, 0L, name, value.toCharArray())
                }
                for (tunnel in tunnels) advancedRepo.saveTunnel(tunnel)
                if (proxy.kind != ProxyKind.NONE) advancedRepo.saveProxy(proxy)
                throw e
            }
        }
    }

    private class World {
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
        val runner = SnapshotRestoreRunner(hostRepo, commandRepo, envRepo, advancedRepo)
        val service = BackupService(hostRepo, commandRepo, envRepo, advancedRepo, runner)

        suspend fun seed(): HostProfile {
            val host = hostRepo.saveHost(
                HostProfile(
                    name = "Old name",
                    hostname = "192.168.1.10",
                    port = 22,
                    username = "admin",
                    authType = AuthType.PASSWORD
                ),
                CredentialInput.Password("s3cr3t".toCharArray())
            )
            commandRepo.saveButton(
                CommandButton(
                    hostId = host.id,
                    label = "Old button",
                    mode = CommandMode.EXECUTE_ONE,
                    template = "uptime"
                )
            )
            envRepo.saveEnvVar(host.id, 0L, "MY_VAR", "old-value".toCharArray())
            return host
        }

        /** A replace preview for the seeded host, carrying one new button. */
        fun replacePreview(existingId: Long): ImportPreview {
            val exported = ExportHost(
                exportId = 0,
                name = "New name",
                hostname = "192.168.1.10",
                port = 22,
                username = "admin",
                authType = AuthType.PASSWORD,
                notes = "",
                tags = "",
                credential = null,
                hasCredential = false,
                buttons = listOf(
                    ExportButton(
                        label = "New button",
                        category = "",
                        sortOrder = 0,
                        mode = CommandMode.EXECUTE_ONE,
                        template = "df -h",
                        steps = emptyList(),
                        variables = emptyList()
                    )
                ),
                settings = null,
                envVars = emptyList(),
                tunnels = emptyList(),
                proxy = null,
                jumpHopExportIds = emptyList()
            )
            return ImportPreview(
                document = BackupDocument(listOf(exported)),
                encrypted = false,
                hasCredentials = false,
                conflicts = listOf(
                    ImportConflict(0, "New name", existingId, "Old name")
                ),
                warnings = emptyList()
            )
        }

        suspend fun buttonLabels(hostId: Long): List<String> =
            commandRepo.getButtons(hostId).first()
                .mapNotNull { commandRepo.getButton(it.id) }.map { it.label }

        suspend fun envValue(hostId: Long, name: String): String? =
            envRepo.loadEnvVarsForConnection(hostId).values
                .firstOrNull { it.name == name }
                ?.let { String(it.value, Charsets.UTF_8) }
    }

    @Test
    fun failedReplaceLeavesOldConfigIntact() = runTest {
        val world = World()
        val seeded = world.seed()
        // Fail the new button's child replacement inside the
        // transaction (prompt-15 injection hook).
        world.commandDao.failNextReplaceChildren = true
        val result = world.service.applyImport(
            world.replacePreview(seeded.id), replaceExisting = true
        )
        assertEquals(1, world.runner.runs)
        assertTrue(
            "failed replace must not be reported as replaced",
            result.replacedNames.isEmpty()
        )
        assertTrue(result.warnings.isNotEmpty())
        val host = world.hostRepo.getHost(seeded.id)!!
        assertEquals("Old name", host.name)
        assertEquals(listOf("Old button"), world.buttonLabels(seeded.id))
        assertEquals("old-value", world.envValue(seeded.id, "MY_VAR"))
        assertArrayEquals(
            "s3cr3t".toByteArray(),
            world.hostRepo.loadCredential(seeded.id)
        )
    }

    @Test
    fun successfulReplaceRunsInsideOneTransaction() = runTest {
        val world = World()
        val seeded = world.seed()
        val result = world.service.applyImport(
            world.replacePreview(seeded.id), replaceExisting = true
        )
        assertEquals(1, world.runner.runs)
        assertEquals(listOf("New name"), result.replacedNames)
        assertEquals("New name", world.hostRepo.getHost(seeded.id)!!.name)
        assertEquals(listOf("New button"), world.buttonLabels(seeded.id))
        // Non-secret replace keeps the existing credential.
        assertArrayEquals(
            "s3cr3t".toByteArray(),
            world.hostRepo.loadCredential(seeded.id)
        )
    }
}
