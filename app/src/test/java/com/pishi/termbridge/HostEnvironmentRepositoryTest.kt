package com.pishi.termbridge

import com.pishi.termbridge.data.HostEnvironmentException
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.domain.EnvApplyResult
import com.pishi.termbridge.domain.HostEnvironmentSettings
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.security.SecretCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostEnvironmentRepositoryTest {
    private fun repository(): Triple<HostEnvironmentRepository, FakeHostSettingsDao, FakeHostEnvVarDao> {
        val settingsDao = FakeHostSettingsDao()
        val envDao = FakeHostEnvVarDao()
        return Triple(HostEnvironmentRepository(settingsDao, envDao, FakeSecretCrypto()), settingsDao, envDao)
    }

    @Test
    fun settingsRoundTripStoresCanonicalDirectory() = runTest {
        val (repo, _, _) = repository()
        repo.saveSettings(
            HostEnvironmentSettings(
                hostId = 1L,
                remoteWorkingDirectory = "/home/user/../user",
                remoteShell = RemoteShell.POSIX
            )
        )
        val stored = repo.getSettingsOnce(1L)
        assertEquals("/home/user", stored?.remoteWorkingDirectory)
        assertEquals(RemoteShell.POSIX, stored?.remoteShell)
        assertNull(stored?.localDirectoryUri)
    }

    @Test
    fun shellConfirmedRoundTripsThroughSave() = runTest {
        val (repo, _, _) = repository()
        repo.saveSettings(
            HostEnvironmentSettings(
                hostId = 1L,
                remoteWorkingDirectory = "C:\\Users\\user",
                remoteShell = RemoteShell.POWERSHELL,
                shellConfirmed = true
            )
        )
        val stored = repo.getSettingsOnce(1L)
        assertEquals(RemoteShell.POWERSHELL, stored?.remoteShell)
        assertTrue(stored?.shellConfirmed == true)
    }

    @Test
    fun shellConfirmedDefaultsFalse() = runTest {
        // The prompt-18 default: settings saved (or imported)
        // without the flag are UNCONFIRMED, so parameterised
        // buttons warn until the environment screen is saved.
        val (repo, _, _) = repository()
        repo.saveSettings(
            HostEnvironmentSettings(
                hostId = 1L,
                remoteWorkingDirectory = "/home/user",
                remoteShell = RemoteShell.POSIX
            )
        )
        assertFalse(repo.getSettingsOnce(1L)?.shellConfirmed == true)
    }

    @Test
    fun settingsRejectInvalidDirectoryWithoutSaving() = runTest {
        val (repo, _, _) = repository()
        try {
            repo.saveSettings(
                HostEnvironmentSettings(
                    hostId = 1L,
                    remoteWorkingDirectory = "relative/path",
                    remoteShell = RemoteShell.POSIX
                )
            )
            fail("Expected HostEnvironmentException")
        } catch (e: HostEnvironmentException) {
            assertNull(repo.getSettingsOnce(1L))
        }
    }

    @Test
    fun settingsRejectFilesystemLocalPath() = runTest {
        val (repo, _, _) = repository()
        try {
            repo.saveSettings(
                HostEnvironmentSettings(
                    hostId = 1L,
                    localDirectoryUri = "/storage/emulated/0/Docs"
                )
            )
            fail("Expected HostEnvironmentException")
        } catch (e: HostEnvironmentException) {
            assertNull(repo.getSettingsOnce(1L))
        }
    }

    @Test
    fun envVarSaveEncryptsAndLoadDecrypts() = runTest {
        val (repo, _, envDao) = repository()
        repo.saveEnvVar(1L, 0L, "API_TOKEN", "top-secret-value".toCharArray())
        val names = repo.getEnvVarNames(1L).first()
        assertEquals(listOf("API_TOKEN"), names.map { it.name })
        // The stored blob is not the plaintext, and the names flow
        // carries no blob at all (checked by type: EnvVarInfo has
        // no value field; the raw row is only reachable here).
        val raw = envDao.rawRowsForHost(1L).single()
        assertFalse(raw.valueBlob.contentEquals("top-secret-value".toByteArray()))
        val loaded = repo.loadEnvVarsForConnection(1L)
        assertTrue(loaded.undecryptableNames.isEmpty())
        assertEquals("API_TOKEN", loaded.values.single().name)
        assertArrayEquals("top-secret-value".toByteArray(), loaded.values.single().value)
        loaded.values.forEach { it.value.wipe() }
    }

    @Test
    fun envVarDuplicateNameRejected() = runTest {
        val (repo, _, _) = repository()
        repo.saveEnvVar(1L, 0L, "FOO", "one".toCharArray())
        try {
            repo.saveEnvVar(1L, 0L, "FOO", "two".toCharArray())
            fail("Expected HostEnvironmentException")
        } catch (e: HostEnvironmentException) {
            val loaded = repo.loadEnvVarsForConnection(1L)
            assertEquals(1, loaded.values.size)
            assertArrayEquals("one".toByteArray(), loaded.values.single().value)
            loaded.values.forEach { it.value.wipe() }
        }
    }

    @Test
    fun envVarBlockedNameRejectedWithoutLeakingValue() = runTest {
        val (repo, _, envDao) = repository()
        try {
            repo.saveEnvVar(1L, 0L, "LD_PRELOAD", "my-injection-value".toCharArray())
            fail("Expected HostEnvironmentException")
        } catch (e: HostEnvironmentException) {
            assertFalse(e.message!!.contains("my-injection-value"))
            assertTrue(envDao.rawRowsForHost(1L).isEmpty())
        }
    }

    @Test
    fun envVarEditRequiresNewValueAndReplacesBlob() = runTest {
        val (repo, _, _) = repository()
        repo.saveEnvVar(1L, 0L, "FOO", "old".toCharArray())
        val id = repo.getEnvVarNames(1L).first().single().id
        repo.saveEnvVar(1L, id, "FOO", "new".toCharArray())
        val loaded = repo.loadEnvVarsForConnection(1L)
        assertArrayEquals("new".toByteArray(), loaded.values.single().value)
        loaded.values.forEach { it.value.wipe() }
    }

    @Test
    fun envVarDeleteAndHostDeleteRemoveRows() = runTest {
        val (repo, _, envDao) = repository()
        repo.saveEnvVar(1L, 0L, "FOO", "one".toCharArray())
        val id = repo.getEnvVarNames(1L).first().single().id
        repo.deleteEnvVar(id, 1L)
        assertTrue(envDao.rawRowsForHost(1L).isEmpty())
        repo.saveEnvVar(1L, 0L, "BAR", "two".toCharArray())
        repo.deleteForHost(1L)
        assertTrue(envDao.rawRowsForHost(1L).isEmpty())
        assertNull(repo.getSettingsOnce(1L))
    }

    @Test
    fun envVarCannotMoveToAnotherHost() = runTest {
        val (repo, _, _) = repository()
        repo.saveEnvVar(1L, 0L, "FOO", "one".toCharArray())
        val id = repo.getEnvVarNames(1L).first().single().id
        try {
            repo.saveEnvVar(2L, id, "FOO", "two".toCharArray())
            fail("Expected HostEnvironmentException")
        } catch (e: HostEnvironmentException) {
            // Original host still owns the var.
            assertEquals(1, repo.getEnvVarNames(1L).first().size)
        }
    }

    @Test
    fun envApplyResultCarriesNamesOnly() {
        val result = EnvApplyResult(
            appliedNames = listOf("EDITOR"),
            rejectedNames = listOf("FOO")
        )
        // A value accidentally added to this type would show up
        // here; the type has no value field by construction.
        assertFalse(result.toString().contains("secret"))
        assertEquals(listOf("EDITOR", "FOO"), result.configuredNames)
    }

    @Test
    fun legacyEnvValueMigratesToBoundFormatOnLoad() = runTest {
        // Item R-02: env values follow the same rule.
        val settingsDao = FakeHostSettingsDao()
        val envDao = FakeHostEnvVarDao()
        val crypto = FakeSecretCrypto()
        val repo = HostEnvironmentRepository(settingsDao, envDao, crypto)
        repo.saveEnvVar(1L, 0L, "API_TOKEN", "top-secret-value".toCharArray())
        val row = envDao.rawRowsForHost(1L).single()
        envDao.update(
            row.copy(
                valueBlob =
                    crypto.legacyEncrypt("top-secret-value".toByteArray(Charsets.UTF_8))
            )
        )
        val loaded = repo.loadEnvVarsForConnection(1L)
        assertTrue(loaded.undecryptableNames.isEmpty())
        assertArrayEquals(
            "top-secret-value".toByteArray(), loaded.values.single().value
        )
        loaded.values.forEach { it.value.wipe() }
        val upgraded = envDao.rawRowsForHost(1L).single().valueBlob
        assertFalse(crypto.isLegacyBlob(upgraded))
    }

    @Test
    fun legacyBlobWithVersionByteIvStillMigratesOnLoad() = runTest {
        // CI run #86 (2026-11-08) failed the test above once:
        // a legacy blob whose random IV starts with 0x02 is
        // routed to the v2 path by the first-byte check
        // (p = 1/256) and, without the AEAD fallback, the load
        // marks the value undecryptable. Seed that exact IV
        // deterministically so the fallback is pinned, not
        // left to chance.
        val settingsDao = FakeHostSettingsDao()
        val envDao = FakeHostEnvVarDao()
        val crypto = FakeSecretCrypto()
        val repo = HostEnvironmentRepository(settingsDao, envDao, crypto)
        repo.saveEnvVar(1L, 0L, "API_TOKEN", "top-secret-value".toCharArray())
        val row = envDao.rawRowsForHost(1L).single()
        envDao.update(
            row.copy(
                valueBlob = crypto.legacyEncryptWithFirstIvByte(
                    "top-secret-value".toByteArray(Charsets.UTF_8),
                    SecretCrypto.BLOB_VERSION_2
                )
            )
        )
        val loaded = repo.loadEnvVarsForConnection(1L)
        assertTrue(loaded.undecryptableNames.isEmpty())
        assertArrayEquals(
            "top-secret-value".toByteArray(), loaded.values.single().value
        )
        loaded.values.forEach { it.value.wipe() }
        val upgraded = envDao.rawRowsForHost(1L).single().valueBlob
        assertFalse(crypto.isLegacyBlob(upgraded))
    }

}
