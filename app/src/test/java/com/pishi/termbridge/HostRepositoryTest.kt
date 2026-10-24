package com.pishi.termbridge

import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.HostRepositoryException
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostRepositoryTest {
    private fun repository(
        hostDao: FakeHostDao = FakeHostDao(),
        trustedDao: FakeTrustedHostKeyDao = FakeTrustedHostKeyDao(),
        transactionRunner: com.pishi.termbridge.data.TransactionRunner =
            object : com.pishi.termbridge.data.TransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T = block()
            }
    ): Triple<HostRepository, FakeHostDao, FakeTrustedHostKeyDao> {
        return Triple(
            HostRepository(
                hostDao, trustedDao, FakeSecretCrypto(),
                transactionRunner = transactionRunner
            ),
            hostDao,
            trustedDao
        )
    }

    private fun profile() = HostProfile(
        name = "Home server",
        hostname = "192.168.1.10",
        port = 22,
        username = "admin",
        authType = AuthType.PASSWORD
    )

    @Test
    fun saveEncryptsCredentialAndLoadReturnsIt() = runTest {
        val (repo, dao, _) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        val blob = dao.rawRow(saved.id)?.credentialBlob
        assertTrue(blob != null)
        assertFalse(blob!!.contentEquals("s3cr3t".toByteArray()))
        assertArrayEquals("s3cr3t".toByteArray(), repo.loadCredential(saved.id))
    }

    @Test
    fun saveRejectsInvalidPortWithoutLeakingSecret() = runTest {
        val (repo, _, _) = repository()
        try {
            repo.saveHost(
                profile().copy(port = 0),
                CredentialInput.Password("my-secret-password-123".toCharArray())
            )
            fail("Expected HostRepositoryException")
        } catch (e: HostRepositoryException) {
            assertFalse(e.message!!.contains("my-secret-password-123"))
        }
    }

    @Test
    fun duplicateCopiesShellValueButNotItsConfirmation() = runTest {
        // Prompt 18 (audit-17 R1): the shell VALUE is non-secret
        // and is copied, but CONFIRMATION attaches to the host
        // row the user reviewed — the duplicate warns until its
        // own environment screen is saved.
        val settingsDao = FakeHostSettingsDao()
        val repo = HostRepository(
            FakeHostDao(), FakeTrustedHostKeyDao(), FakeSecretCrypto(),
            hostSettingsDao = settingsDao,
            hostEnvVarDao = FakeHostEnvVarDao()
        )
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        settingsDao.upsert(
            com.pishi.termbridge.data.HostSettingsEntity(
                hostId = saved.id,
                remoteWorkingDirectory = "/home/admin",
                localDirectoryUri = null,
                remoteShell = "POWERSHELL",
                shellConfirmed = true
            )
        )
        val copy = repo.duplicateHost(saved.id, "Home server (copy)")
        val copied = settingsDao.getForHostOnce(copy.id)
        assertEquals("POWERSHELL", copied?.remoteShell)
        assertEquals("/home/admin", copied?.remoteWorkingDirectory)
        assertFalse(copied?.shellConfirmed == true)
    }

    @Test
    fun duplicateDoesNotCopyCredential() = runTest {
        val (repo, _, _) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        val copy = repo.duplicateHost(saved.id, "Home server (copy)")
        assertEquals("Home server (copy)", copy.name)
        assertFalse(repo.hasCredential(copy.id))
        assertNull(repo.loadCredential(copy.id))
        // Original keeps its credential.
        assertTrue(repo.hasCredential(saved.id))
    }

    @Test
    fun deleteRemovesCredential() = runTest {
        val (repo, dao, _) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        repo.deleteHost(saved.id)
        assertNull(repo.getHost(saved.id))
        assertNull(dao.rawRow(saved.id))
    }

    @Test
    fun deleteHostDeletesTrustRecord() = runTest {
        // Security fix 16 (M2): the prompt-04 rule ("trust
        // records survive deletion") was reversed — a
        // delete-and-re-add cycle must re-verify, never
        // silently MATCH an old fingerprint.
        val (repo, _, trustedDao) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        repo.trustHostKey("192.168.1.10:22", "SHA256:abc", "ssh-ed25519")
        assertEquals(
            "SHA256:abc",
            trustedDao.getByEndpoint("192.168.1.10:22")?.fingerprint
        )
        repo.deleteHost(saved.id)
        assertNull(trustedDao.getByEndpoint("192.168.1.10:22"))
    }

    @Test
    fun deleteHostRunsInsideTransactionRunner() = runTest {
        // Security fix 16 (L4): the whole delete sequence runs
        // inside one transaction when a runner is supplied.
        var runs = 0
        val runner = object : com.pishi.termbridge.data.TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                runs++
                return block()
            }
        }
        val (repo, _, _) = repository(transactionRunner = runner)
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        repo.deleteHost(saved.id)
        assertEquals(1, runs)
        assertNull(repo.getHost(saved.id))
    }

    @Test
    fun editWithoutCredentialKeepsExistingBlob() = runTest {
        val (repo, dao, _) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        val before = dao.rawRow(saved.id)?.credentialBlob
        repo.saveHost(saved.copy(name = "Renamed"), null)
        assertArrayEquals(before, dao.rawRow(saved.id)?.credentialBlob)
        assertArrayEquals("s3cr3t".toByteArray(), repo.loadCredential(saved.id))
    }

    @Test
    fun editWithNewCredentialReplacesIt() = runTest {
        val (repo, _, _) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("old".toCharArray()))
        repo.saveHost(saved, CredentialInput.Password("new".toCharArray()))
        assertArrayEquals("new".toByteArray(), repo.loadCredential(saved.id))
    }

    @Test
    fun privateKeySaveStoresPemNotPassphrase() = runTest {
        val (repo, _, _) = repository()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"
        val saved = repo.saveHost(
            profile().copy(authType = AuthType.PRIVATE_KEY),
            CredentialInput.PrivateKey(pem, "key-passphrase-xyz".toCharArray())
        )
        val loaded = String(repo.loadCredential(saved.id)!!)
        assertEquals(pem, loaded)
        assertFalse(loaded.contains("key-passphrase-xyz"))
    }

    @Test
    fun changingAuthTypeWithoutNewCredentialIsRejected() = runTest {
        val (repo, _, _) = repository()
        val saved = repo.saveHost(profile(), CredentialInput.Password("s3cr3t".toCharArray()))
        try {
            repo.saveHost(saved.copy(authType = AuthType.PRIVATE_KEY), null)
            fail("Expected HostRepositoryException")
        } catch (e: HostRepositoryException) {
            assertTrue(e.message!!.contains("authentication type"))
        }
        // Original credential is untouched.
        assertArrayEquals("s3cr3t".toByteArray(), repo.loadCredential(saved.id))
    }

    @Test
    fun trustStoreRoundTripAndClear() = runTest {
        val (repo, _, _) = repository()
        assertNull(repo.getTrustedFingerprint("example.com:22"))
        repo.trustHostKey("example.com:22", "SHA256:abc", "ssh-ed25519")
        assertEquals("SHA256:abc", repo.getTrustedFingerprint("example.com:22"))
        repo.clearTrustedKey("example.com:22")
        assertNull(repo.getTrustedFingerprint("example.com:22"))
    }
}
