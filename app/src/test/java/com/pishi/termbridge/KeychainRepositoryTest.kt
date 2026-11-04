package com.pishi.termbridge

import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.HostRepositoryException
import com.pishi.termbridge.data.KeychainRepository
import com.pishi.termbridge.data.KeychainRepositoryException
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.KeychainEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Item T-01: keychain repository + host resolution rules, on the
 * JVM with in-memory DAOs and the test SecretCrypto. What this
 * proves: encryption at rest, label/kind validation, and that a
 * host linked to an entry resolves its credential through
 * HostRepository.loadCredential — the single path every caller
 * (session, files, tunnels, backup) already uses.
 */
class KeychainRepositoryTest {

    private class Fixture {
        val hostDao = FakeHostDao()
        val keychainDao = FakeKeychainDao()
        val crypto = FakeSecretCrypto()
        val keychain = KeychainRepository(keychainDao, hostDao, crypto)
        val hosts = HostRepository(
            hostDao, FakeTrustedHostKeyDao(), crypto,
            keychainDao = keychainDao
        )
    }

    private fun passwordEntry(label: String = "Prod password") =
        KeychainEntry(label = label, kind = AuthType.PASSWORD)

    private fun host(
        name: String = "Box",
        authType: AuthType = AuthType.PASSWORD,
        keychainEntryId: Long? = null
    ) = HostProfile(
        name = name,
        hostname = "192.0.2.10",
        port = 22,
        username = "root",
        authType = authType,
        keychainEntryId = keychainEntryId
    )

    private val pem =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nZm9vYmFy\n-----END OPENSSH PRIVATE KEY-----\n"

    @Test
    fun savePasswordEntryEncryptsAndLoadReturnsIt() = runTest {
        val f = Fixture()
        val saved = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("hunter2".toCharArray())
        )
        val blob = f.keychainDao.rawRow(saved.id)?.materialBlob
        assertTrue(blob != null)
        assertFalse(blob!!.contentEquals("hunter2".toByteArray()))
        assertArrayEquals("hunter2".toByteArray(), f.keychain.loadMaterial(saved.id))
    }

    @Test
    fun saveKeyEntryRoundTripsPem() = runTest {
        val f = Fixture()
        val saved = f.keychain.saveEntry(
            KeychainEntry(label = "Deploy key", kind = AuthType.PRIVATE_KEY),
            CredentialInput.PrivateKey(pem, null),
            fingerprint = "SHA256:abc123"
        )
        assertEquals("SHA256:abc123", saved.fingerprint)
        assertArrayEquals(
            pem.toByteArray(Charsets.UTF_8),
            f.keychain.loadMaterial(saved.id)
        )
    }

    @Test
    fun duplicateLabelRejected() = runTest {
        val f = Fixture()
        f.keychain.saveEntry(passwordEntry(), CredentialInput.Password("one".toCharArray()))
        try {
            f.keychain.saveEntry(passwordEntry(), CredentialInput.Password("two".toCharArray()))
            fail("Expected duplicate label to be rejected.")
        } catch (expected: KeychainRepositoryException) {
            assertTrue(expected.message!!.contains("already uses this label"))
        }
    }

    @Test
    fun kindMismatchRejected() = runTest {
        val f = Fixture()
        try {
            f.keychain.saveEntry(
                passwordEntry(),
                CredentialInput.PrivateKey(pem, null)
            )
            fail("Expected kind mismatch to be rejected.")
        } catch (expected: KeychainRepositoryException) {
            assertTrue(expected.message!!.contains("does not match"))
        }
    }

    @Test
    fun blankAndControlLabelsRejected() = runTest {
        val f = Fixture()
        for (bad in listOf("", "   ", "bad\u0001label", "bad\u0007label")) {
            try {
                f.keychain.saveEntry(
                    KeychainEntry(label = bad, kind = AuthType.PASSWORD),
                    CredentialInput.Password("x".toCharArray())
                )
                fail("Expected label '$bad' to be rejected.")
            } catch (expected: KeychainRepositoryException) {
                // Rejected as required.
            }
        }
    }

    @Test
    fun editWithoutCredentialKeepsMaterial() = runTest {
        val f = Fixture()
        val saved = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("keepme".toCharArray())
        )
        val renamed = f.keychain.saveEntry(
            saved.copy(label = "Renamed"),
            credential = null
        )
        assertEquals("Renamed", renamed.label)
        assertArrayEquals("keepme".toByteArray(), f.keychain.loadMaterial(saved.id))
    }

    @Test
    fun hostLinkedToEntryResolvesCredential() = runTest {
        val f = Fixture()
        val entry = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("shared-secret".toCharArray())
        )
        val saved = f.hosts.saveHost(host(keychainEntryId = entry.id), credential = null)
        assertNull(f.hostDao.rawRow(saved.id)?.credentialBlob)
        assertEquals(entry.id, f.hostDao.rawRow(saved.id)?.keychainEntryId)
        assertTrue(f.hosts.hasCredential(saved.id))
        assertArrayEquals(
            "shared-secret".toByteArray(),
            f.hosts.loadCredential(saved.id)
        )
    }

    @Test
    fun hostLinkKindMismatchRejected() = runTest {
        val f = Fixture()
        val keyEntry = f.keychain.saveEntry(
            KeychainEntry(label = "Key", kind = AuthType.PRIVATE_KEY),
            CredentialInput.PrivateKey(pem, null)
        )
        try {
            f.hosts.saveHost(
                host(authType = AuthType.PASSWORD, keychainEntryId = keyEntry.id),
                credential = null
            )
            fail("Expected kind mismatch to be rejected.")
        } catch (expected: HostRepositoryException) {
            assertTrue(expected.message!!.contains("does not match the authentication type"))
        }
    }

    @Test
    fun hostLinkPlusInlineCredentialRejected() = runTest {
        val f = Fixture()
        val entry = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("shared".toCharArray())
        )
        try {
            f.hosts.saveHost(
                host(keychainEntryId = entry.id),
                CredentialInput.Password("inline".toCharArray())
            )
            fail("Expected link + inline credential to be rejected.")
        } catch (expected: HostRepositoryException) {
            assertTrue(expected.message!!.contains("not both"))
        }
    }

    @Test
    fun deleteInUseRefusedThenForceDetaches() = runTest {
        val f = Fixture()
        val entry = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("shared".toCharArray())
        )
        val saved = f.hosts.saveHost(host(keychainEntryId = entry.id), credential = null)
        assertEquals(1, f.keychain.referencingHostCount(entry.id))
        try {
            f.keychain.deleteEntry(entry.id)
            fail("Expected in-use delete to be refused.")
        } catch (expected: KeychainRepositoryException) {
            assertTrue(expected.message!!.contains("used by 1 host"))
        }
        f.keychain.deleteEntry(entry.id, force = true)
        assertNull(f.keychain.getEntry(entry.id))
        assertNull(f.hostDao.rawRow(saved.id)?.keychainEntryId)
        assertFalse(f.hosts.hasCredential(saved.id))
    }

    @Test
    fun duplicateHostCopiesKeychainReference() = runTest {
        val f = Fixture()
        val entry = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("shared".toCharArray())
        )
        val saved = f.hosts.saveHost(host(keychainEntryId = entry.id), credential = null)
        val copy = f.hosts.duplicateHost(saved.id, "Box copy")
        assertEquals(entry.id, copy.keychainEntryId)
        assertArrayEquals(
            "shared".toByteArray(),
            f.hosts.loadCredential(copy.id)
        )
    }

    @Test
    fun legacyMaterialMigratesToBoundFormatOnLoad() = runTest {
        // Item R-02: same migration contract as host blobs.
        val f = Fixture()
        val saved = f.keychain.saveEntry(
            passwordEntry(),
            CredentialInput.Password("entry-pw".toCharArray())
        )
        val row = f.keychainDao.getById(saved.id)!!
        f.keychainDao.update(
            row.copy(
                materialBlob =
                    f.crypto.legacyEncrypt("entry-pw".toByteArray(Charsets.UTF_8))
            )
        )
        val loaded = f.keychain.loadMaterial(saved.id)!!
        assertArrayEquals("entry-pw".toByteArray(Charsets.UTF_8), loaded)
        loaded.wipe()
        val upgraded = f.keychainDao.getById(saved.id)!!.materialBlob
        assertFalse(f.crypto.isLegacyBlob(upgraded))
    }

}
