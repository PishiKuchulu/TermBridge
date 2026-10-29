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
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.RSAPublicKeySpec
import net.schmizz.sshj.common.DisconnectReason
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

    /**
     * Review #2 F-02 regression: the expected value was computed
     * independently of the app (RSA key generated with OpenSSL, SSH
     * wire blob `string "ssh-rsa" + mpint e + mpint n` hashed with
     * SHA-256 and Base64-encoded without padding, i.e. exactly what
     * `ssh-keygen -lf` prints). Hashing PublicKey.encoded (X.509 DER)
     * would NOT produce this value.
     */
    @Test
    fun fingerprintMatchesIndependentlyComputedOpenSshValue() {
        val modulus = BigInteger("9339D87CE8CB5980FDA9F8B274C6B001E383BFEF218060F56A3E9EC9B3143FA94E7AB01EA15224DBCCE2892AE6BD6C069D396246BD2657E81AB70C2154E966C03FC75700FFA29BFC45B83D8487AB57DE7253BA10E0E70CF8C348BFF2F98F01EC0B2FA53202913B1A7B6E4B6A38F24D40061F2DF3B7AD2BEA31E520F4907D6F3D30E0C200803E2F89C6BEB9D625A5D9BFE9FFC03D4B0BC24ED3A983257CE165A915E9A5520A29B5BE87BA5617CA5EBE71CDF14ECD7ECCADF037DB3506A03B2FD200AFB878125626501ED6E8DB1A4B436AE073EE42A47C8736937A05C4412B319F26809683B412EB61ACA8CC12EF90A04594B72BE92175BD32D7CF87AA99155E9D", 16)
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        assertEquals("SHA256:9Z/8o198vSmvBYOBdMvpGkBMBu7+hNERS2dpspGDrO4", HostKeyFingerprint.compute(key))
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
        // Review #2 F-06: typed disconnect reasons drive the mapping.
        assertEquals(
            SshError.HostKeyRejected,
            SshErrorMapper.map(
                TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "anything")
            )
        )
        assertEquals(
            SshError.SecureChannelFailed,
            SshErrorMapper.map(TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "x"))
        )
        // A key-exchange message that merely mentions "host key" is
        // NOT a host-key rejection when sshj gave a typed reason.
        assertEquals(
            SshError.SecureChannelFailed,
            SshErrorMapper.map(
                TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "no host key algorithm")
            )
        )
        assertEquals(SshError.ConnectionRefused, SshErrorMapper.map(ConnectException()))
        assertEquals(SshError.Timeout, SshErrorMapper.map(SocketTimeoutException()))
    }

    @Test
    fun diagnosticCarriesOnlyEnumAndClassNames() {
        val secret = "hunter2-should-never-appear"
        val failure = TransportException(
            DisconnectReason.KEY_EXCHANGE_FAILED,
            "boom $secret",
            java.security.NoSuchAlgorithmException("alg $secret")
        )
        val code = SshErrorMapper.diagnose(failure).code
        assertEquals(
            "KEY_EXCHANGE_FAILED:TransportException>NoSuchAlgorithmException",
            code
        )
        assertFalse(code.contains(secret))
        val exception = com.pishi.termbridge.transport.SshTransportException(
            SshErrorMapper.map(failure),
            SshErrorMapper.diagnose(failure)
        )
        assertFalse(exception.userFacingMessage().contains(secret))
        assertTrue(exception.userFacingMessage().contains("KEY_EXCHANGE_FAILED"))
    }
}
