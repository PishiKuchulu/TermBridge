package com.pishi.termbridge

import com.pishi.termbridge.domain.SecretRedactor
import com.pishi.termbridge.domain.wipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 15 gap closure: SecretRedactor and the wipe helpers
 * (prompt 04) had no test, although every error path relies on
 * them as the second net against secret leakage. These tests
 * pin: replacement, empty-secret safety (an empty secret must
 * not rewrite the whole string), CharArray handling, and that
 * wipe actually zeroes buffers.
 */
class SecretRedactorTest {
    @Test
    fun replacesASecretOccurrence() {
        assertEquals(
            "login failed for ***",
            SecretRedactor.redact("login failed for hunter2", listOf("hunter2"))
        )
    }

    @Test
    fun replacesMultipleSecretsAndAllOccurrences() {
        val out = SecretRedactor.redact(
            "a hunter2 b token-9 c hunter2",
            listOf("hunter2", "token-9")
        )
        assertEquals("a *** b *** c ***", out)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("token-9"))
    }

    @Test
    fun emptySecretIsSkippedNotMatchedEverywhere() {
        assertEquals("unchanged text", SecretRedactor.redact("unchanged text", listOf("")))
    }

    @Test
    fun charArrayVariantRedactsAndNullPassthrough() {
        val secret = "p4ss".toCharArray()
        assertEquals(
            "key *** here",
            SecretRedactor.redactCharArray("key p4ss here", secret)
        )
        assertEquals("plain", SecretRedactor.redactCharArray("plain", null))
        assertEquals("plain", SecretRedactor.redactCharArray("plain", charArrayOf()))
    }

    @Test
    fun wipeZeroesCharArray() {
        val chars = "secret".toCharArray()
        chars.wipe()
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun wipeZeroesByteArray() {
        val bytes = byteArrayOf(1, 2, 3, 42)
        bytes.wipe()
        assertTrue(bytes.all { it == 0.toByte() })
    }
}
