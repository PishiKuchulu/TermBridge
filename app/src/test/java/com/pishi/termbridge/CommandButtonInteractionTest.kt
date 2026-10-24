package com.pishi.termbridge

import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.HostEnvironmentSettings
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 09 interaction tests: environment settings and working
 * directories must NEVER silently rewrite a command button.
 *
 * The decision, in one sentence: a button's preview is exactly
 * what is inserted or sent (prompt 08 guarantee), so env vars and
 * working directories apply to the SHELL the button runs in, not
 * to the button's text. A user who wants a directory for one
 * button writes it in the button itself, visibly. These tests pin
 * that decision: even with env vars and a working directory
 * configured for the host, preparation output is byte-identical
 * to the button's own text, with no cd prefix and no env prefix.
 */
class CommandButtonInteractionTest {
    private fun button(mode: CommandMode) = CommandButton(
        hostId = 1L,
        label = "Check",
        mode = mode,
        template = "df -h {{path}}",
        steps = listOf("cd {{dir}}", "ls -la"),
        variables = listOf(
            CommandVariable("path", "/tmp", false),
            CommandVariable("dir", "/tmp", false)
        )
    )

    @Test
    fun buttonPreparationIgnoresEnvAndWorkingDirectory() {
        val execute = CommandTemplate.prepare(button(CommandMode.EXECUTE_ONE), emptyMap())
        assertEquals("df -h '/tmp'", execute.rendered)
        assertFalse(execute.rendered.startsWith("cd "))
        assertFalse(execute.rendered.contains("EDITOR="))

        val insert = CommandTemplate.prepare(button(CommandMode.INSERT_ONLY), emptyMap())
        assertEquals("df -h '/tmp'", insert.rendered)

        val sequence = CommandTemplate.prepare(button(CommandMode.SEQUENCE), emptyMap())
        assertEquals(listOf("cd '/tmp'", "ls -la"), sequence.renderedSteps)
    }

    @Test
    fun workingDirectoryCdIsASeparateTerminalOnlyRendering() {
        // The cd command exists ONLY as the terminal-startup
        // rendering (CdCommand), never inside a PreparedCommand:
        // the two code paths share no function.
        val cd = CdCommand.render("/home/user", RemoteShell.POSIX)
        assertEquals("cd '/home/user'", cd)
        val prepared = CommandTemplate.prepare(button(CommandMode.EXECUTE_ONE), emptyMap())
        assertFalse(prepared.rendered.contains(cd))
    }

    @Test
    fun duplicateHostCopiesSettingsButNotEnvVars() = runTest {
        val hostDao = FakeHostDao()
        val settingsDao = FakeHostSettingsDao()
        val envDao = FakeHostEnvVarDao()
        val envRepo = HostEnvironmentRepository(settingsDao, envDao, FakeSecretCrypto())
        val hostRepo = HostRepository(
            hostDao = hostDao,
            trustedHostKeyDao = FakeTrustedHostKeyDao(),
            secretCrypto = FakeSecretCrypto(),
            hostSettingsDao = settingsDao,
            hostEnvVarDao = envDao
        )
        val saved = hostRepo.saveHost(
            HostProfile(
                name = "Home",
                hostname = "192.168.1.10",
                port = 22,
                username = "admin",
                authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("s3cr3t".toCharArray())
        )
        envRepo.saveSettings(
            HostEnvironmentSettings(
                hostId = saved.id,
                remoteWorkingDirectory = "/home/user",
                remoteShell = RemoteShell.POSIX
            )
        )
        envRepo.saveEnvVar(saved.id, 0L, "API_TOKEN", "top-secret".toCharArray())
        val copy = hostRepo.duplicateHost(saved.id, "Home (copy)")
        // Settings (non-secret) travelled with the duplicate…
        assertEquals("/home/user", envRepo.getSettingsOnce(copy.id)?.remoteWorkingDirectory)
        // …env vars (secret-bearing) did not, exactly like credentials.
        assertTrue(envRepo.loadEnvVarsForConnection(copy.id).values.isEmpty())
        // …and the original keeps both.
        assertEquals(1, envRepo.loadEnvVarsForConnection(saved.id).values.size)
    }

    @Test
    fun deleteHostRemovesSettingsAndVars() = runTest {
        val hostDao = FakeHostDao()
        val settingsDao = FakeHostSettingsDao()
        val envDao = FakeHostEnvVarDao()
        val envRepo = HostEnvironmentRepository(settingsDao, envDao, FakeSecretCrypto())
        val hostRepo = HostRepository(
            hostDao = hostDao,
            trustedHostKeyDao = FakeTrustedHostKeyDao(),
            secretCrypto = FakeSecretCrypto(),
            hostSettingsDao = settingsDao,
            hostEnvVarDao = envDao
        )
        val saved = hostRepo.saveHost(
            HostProfile(
                name = "Home",
                hostname = "192.168.1.10",
                port = 22,
                username = "admin",
                authType = AuthType.PASSWORD
            ),
            CredentialInput.Password("s3cr3t".toCharArray())
        )
        envRepo.saveSettings(
            HostEnvironmentSettings(hostId = saved.id, remoteWorkingDirectory = "/tmp")
        )
        envRepo.saveEnvVar(saved.id, 0L, "FOO", "bar".toCharArray())
        hostRepo.deleteHost(saved.id)
        assertEquals(null, envRepo.getSettingsOnce(saved.id))
        assertTrue(envRepo.loadEnvVarsForConnection(saved.id).values.isEmpty())
    }
}
