package com.pishi.termbridge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pishi.termbridge.data.HostEntity
import com.pishi.termbridge.data.TermBridgeDatabase
import com.pishi.termbridge.security.AndroidKeystoreSecretCrypto
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the two Android-only pieces of prompt 04:
 * platform Keystore encryption and the real Room DAOs.
 *
 * NOT RUN until an emulator/device run exists. They are written
 * now so the prompt-04 gate has a concrete test list, not a promise.
 * In particular, the Keystore test must run on a real device class
 * (not only an emulator) before prompt 05 relies on it, because
 * Keystore behaviour varies by OEM.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreAndRoomTest {
    private lateinit var db: TermBridgeDatabase
    private val crypto = AndroidKeystoreSecretCrypto()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TermBridgeDatabase::class.java
        ).build()
        crypto.deleteKey()
    }

    @After
    fun tearDown() {
        db.close()
        crypto.deleteKey()
    }

    @Test
    fun keystoreEncryptDecryptRoundTrip() {
        val blob = crypto.encrypt("s3cr3t".toByteArray())
        assertFalse(blob.contentEquals("s3cr3t".toByteArray()))
        assertArrayEquals("s3cr3t".toByteArray(), crypto.decrypt(blob))
    }

    @Test
    fun keystoreTamperedBlobFails() {
        val blob = crypto.encrypt("s3cr3t".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()
        try {
            crypto.decrypt(blob)
            throw AssertionError("Expected decryption to fail on tampered blob")
        } catch (e: Exception) {
            // Expected: GCM authentication failure.
        }
    }

    @Test
    fun roomHostCrud() = runTest {
        val dao = db.hostDao()
        val id = dao.insert(
            HostEntity(
                name = "Home",
                hostname = "192.168.1.10",
                port = 22,
                username = "admin",
                authType = "PASSWORD",
                notes = "",
                tags = "",
                credentialBlob = byteArrayOf(1, 2, 3),
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        assertNotNull(dao.getById(id))
        dao.deleteById(id)
        assertNull(dao.getById(id))
    }

    @Test
    fun roomTrustUpsertAndDelete() = runTest {
        val dao = db.trustedHostKeyDao()
        dao.upsert(
            com.pishi.termbridge.data.TrustedHostKeyEntity(
                endpoint = "example.com:22",
                fingerprint = "SHA256:abc",
                keyType = "ssh-ed25519",
                firstSeenAt = 1L,
                lastSeenAt = 1L
            )
        )
        assertEquals("SHA256:abc", dao.getByEndpoint("example.com:22")?.fingerprint)
        dao.deleteByEndpoint("example.com:22")
        assertNull(dao.getByEndpoint("example.com:22"))
    }
}
