package com.pishi.termbridge

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import com.pishi.termbridge.domain.QuickConnect
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
    fun internationalizedHostnameConvertsToPunycodeAndValidates() {
        // Audit #3 (A-15): a Persian-script domain is converted
        // per label to its xn-- form, and the converted form is
        // what validation judges.
        val ascii = HostValidator.asciiHostname("سرور.ir")
        assertTrue(ascii.startsWith("xn--"))
        assertTrue(ascii.endsWith(".ir"))
        val issues = HostValidator.validate(validProfile().copy(hostname = "سرور.ir"))
        assertTrue(issues.none { it.field == "hostname" })
    }

    @Test
    fun asciiHostnamePassesAsciiAndIpLiteralsThroughUnchanged() {
        assertEquals("example.com", HostValidator.asciiHostname("example.com"))
        assertEquals("192.168.1.10", HostValidator.asciiHostname("192.168.1.10"))
        assertEquals("[::1]", HostValidator.asciiHostname("[::1]"))
    }

    @Test
    fun garbageHostnameStillRejectedAfterConversion() {
        val issues = HostValidator.validate(validProfile().copy(hostname = "bad host.ir"))
        assertTrue(issues.any { it.field == "hostname" })
    }

    @Test
    fun overlongConvertedHostnameIsRejected() {
        // Each Persian label expands in punycode, so a name that
        // looks moderate in Unicode can exceed the 253-char DNS
        // limit once converted — the limit applies to the
        // converted form.
        val longName = List(30) { "سرور" }.joinToString(".") + ".ir"
        assertTrue(HostValidator.asciiHostname(longName).length > 253)
        val issues = HostValidator.validate(validProfile().copy(hostname = longName))
        assertTrue(issues.any { it.field == "hostname" })
    }

    @Test
    fun quickConnectParsesInternationalizedHostToPunycode() {
        val target = QuickConnect.parse("سرور.ir")
        assertTrue(target != null && target.host.startsWith("xn--"))
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
