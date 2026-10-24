package com.pishi.termbridge

import com.pishi.termbridge.transport.HostKeyFingerprint
import com.pishi.termbridge.transport.HostKeyVerificationPolicy
import com.pishi.termbridge.transport.PendingTrust
import com.pishi.termbridge.transport.SshError
import com.pishi.termbridge.transport.SshErrorMapper
import com.pishi.termbridge.transport.VerificationOutcome
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTransportTest {
    @Test
    fun fingerprintFormatIsOpenSshSha256() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val fingerprint = HostKeyFingerprint.compute(key.public)
        assertTrue(fingerprint.startsWith("SHA256:"))
        // SHA-256 Base64 without padding is 43 characters.
        assertEquals(50, fingerprint.length)
        assertFalse(fingerprint.contains("="))
        assertEquals(fingerprint, HostKeyFingerprint.compute(key.public))
        assertEquals("ssh-rsa", HostKeyFingerprint.sshKeyType(key.public))
    }

    @Test
    fun differentKeysGiveDifferentFingerprints() {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val a = HostKeyFingerprint.compute(gen.generateKeyPair().public)
        val b = HostKeyFingerprint.compute(gen.generateKeyPair().public)
        assertTrue(a != b)
    }

    @Test
    fun policyUnknownAcceptPersistsPending() {
        val outcome = HostKeyVerificationPolicy.verify(
            storedFingerprint = null,
            presentedFingerprint = "SHA256:abc",
            keyType = "ssh-ed25519",
            endpoint = "example.com:22",
            decideUnknown = { true },
            decideChanged = { false }
        )
        assertTrue(outcome is VerificationOutcome.Accept)
        assertEquals("SHA256:abc", (outcome as VerificationOutcome.Accept).pendingTrust?.fingerprint)
    }

    @Test
    fun policyUnknownReject() {
        val outcome = HostKeyVerificationPolicy.verify(
            null, "SHA256:abc", "ssh-ed25519", "example.com:22",
            decideUnknown = { false }, decideChanged = { true }
        )
        assertTrue(outcome is VerificationOutcome.Reject)
    }

    @Test
    fun policyMatchAcceptsWithoutPending() {
        val outcome = HostKeyVerificationPolicy.verify(
            "SHA256:abc", "SHA256:abc", "ssh-ed25519", "example.com:22",
            decideUnknown = { true }, decideChanged = { true }
        )
        assertTrue(outcome is VerificationOutcome.Accept)
        assertEquals(null, (outcome as VerificationOutcome.Accept).pendingTrust)
    }

    @Test
    fun policyChangedAcceptAndReject() {
        val accept = HostKeyVerificationPolicy.verify(
            "SHA256:old", "SHA256:new", "ssh-ed25519", "example.com:22",
            decideUnknown = { false }, decideChanged = { true }
        )
        assertTrue(accept is VerificationOutcome.Accept)
        assertEquals("SHA256:old", (accept as VerificationOutcome.Accept).pendingTrust?.storedFingerprint)
        val reject = HostKeyVerificationPolicy.verify(
            "SHA256:old", "SHA256:new", "ssh-ed25519", "example.com:22",
            decideUnknown = { false }, decideChanged = { false }
        )
        assertTrue(reject is VerificationOutcome.Reject)
    }

    @Test
    fun errorMappingNeverCopiesExceptionMessage() {
        val secret = "hunter2-secret"
        val mapped = SshErrorMapper.map(UserAuthException("auth failed for $secret"))
        assertEquals(SshError.AuthFailed, mapped)
        assertFalse(mapped.userMessage.contains(secret))
        assertEquals(
            SshError.HostKeyRejected,
            SshErrorMapper.map(TransportException("Host key verification failed"))
        )
        assertEquals(SshError.UnknownHost, SshErrorMapper.map(UnknownHostException()))
        assertEquals(SshError.ConnectionRefused, SshErrorMapper.map(ConnectException()))
        assertEquals(SshError.Timeout, SshErrorMapper.map(SocketTimeoutException()))
    }
}
