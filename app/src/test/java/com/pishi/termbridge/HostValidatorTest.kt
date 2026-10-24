package com.pishi.termbridge

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostValidatorTest {
    private fun validProfile() = HostProfile(
        name = "Home server",
        hostname = "192.168.1.10",
        port = 22,
        username = "admin"
    )

    @Test
    fun validProfileHasNoIssues() {
        assertTrue(HostValidator.validate(validProfile()).isEmpty())
    }

    @Test
    fun blankNameHostnameUsernameAndBadPortAreReported() {
        val issues = HostValidator.validate(
            validProfile().copy(name = " ", hostname = "", port = 0, username = "a b")
        )
        val fields = issues.map { it.field }.toSet()
        assertTrue(fields.containsAll(listOf("name", "hostname", "port", "username")))
    }

    @Test
    fun hostnameWithSpaceIsRejected() {
        val issues = HostValidator.validate(validProfile().copy(hostname = "my host"))
        assertTrue(issues.any { it.field == "hostname" })
    }

    @Test
    fun newHostWithoutCredentialIsRejected() {
        val issues = HostValidator.validateCredential(AuthType.PASSWORD, null, false)
        assertEquals(1, issues.size)
    }

    @Test
    fun editWithExistingCredentialAndBlankCredentialIsAllowed() {
        val issues = HostValidator.validateCredential(AuthType.PASSWORD, null, true)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun privateKeyMustLookLikePem() {
        val issues = HostValidator.validateCredential(
            AuthType.PRIVATE_KEY,
            CredentialInput.PrivateKey("not a key", null),
            false
        )
        assertTrue(issues.any { it.field == "credential" })
    }

    @Test
    fun validPemPassesShapeCheck() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"
        val issues = HostValidator.validateCredential(
            AuthType.PRIVATE_KEY,
            CredentialInput.PrivateKey(pem, null),
            false
        )
        assertTrue(issues.isEmpty())
    }
}
