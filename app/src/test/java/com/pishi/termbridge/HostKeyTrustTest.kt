package com.pishi.termbridge

import com.pishi.termbridge.domain.HostKeyTrustVerifier
import com.pishi.termbridge.domain.HostKeyVerification
import com.pishi.termbridge.domain.SecretRedactor
import org.junit.Assert.assertEquals
import org.junit.Test

class HostKeyTrustTest {
    @Test
    fun unknownWhenNothingStored() {
        assertEquals(
            HostKeyVerification.UNKNOWN,
            HostKeyTrustVerifier.verify(null, "SHA256:abc")
        )
    }

    @Test
    fun matchWhenIdentical() {
        assertEquals(
            HostKeyVerification.MATCH,
            HostKeyTrustVerifier.verify("SHA256:abc", "SHA256:abc")
        )
    }

    @Test
    fun changedWhenDifferent() {
        assertEquals(
            HostKeyVerification.CHANGED,
            HostKeyTrustVerifier.verify("SHA256:abc", "SHA256:xyz")
        )
    }

    @Test
    fun redactorReplacesSecrets() {
        val redacted = SecretRedactor.redact(
            "Failed to connect with hunter2 as password",
            listOf("hunter2")
        )
        assertEquals("Failed to connect with *** as password", redacted)
    }

    @Test
    fun redactorIgnoresEmptySecrets() {
        assertEquals("unchanged", SecretRedactor.redact("unchanged", listOf("")))
    }
}
