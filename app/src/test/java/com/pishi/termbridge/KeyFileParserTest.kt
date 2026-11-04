package com.pishi.termbridge

import com.pishi.termbridge.domain.KeyFileFormat
import com.pishi.termbridge.domain.KeyFileParse
import com.pishi.termbridge.domain.KeyFileParser
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Item F-01: the credential-file classifier. Classification
 * only — key LOADING is proven by SshKeyLoadTest and the
 * fixture integration tests, not here.
 */
class KeyFileParserTest {

    private fun pem(label: String, bodyBase64: String, extra: List<String> = emptyList()): String {
        val lines = mutableListOf("-----BEGIN $label-----")
        lines += extra
        if (extra.isNotEmpty()) lines += ""
        lines += bodyBase64.chunked(64)
        lines += "-----END $label-----"
        return lines.joinToString("\n") + "\n"
    }

    /** Minimal openssh-key-v1 body: magic + cipher string +
     *  kdf string + empty kdfoptions + one dummy block. Only
     *  the cipher string is read by the parser. */
    private fun opensshPem(cipher: String): String {
        val out = ByteArrayOutputStream()
        out.write("openssh-key-v1".toByteArray(Charsets.US_ASCII))
        out.write(0)
        fun writeString(s: String) {
            val b = s.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(0, 0, 0, b.size.toByte()))
            out.write(b)
        }
        writeString(cipher)
        writeString("none")
        writeString("")
        out.write(byteArrayOf(0, 0, 0, 1, 42))
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(out.toByteArray())
        return pem("OPENSSH PRIVATE KEY", b64.replace("\n", ""))
    }

    private fun asKey(result: KeyFileParse): KeyFileParse.PrivateKey =
        when (result) {
            is KeyFileParse.PrivateKey -> result
            else -> throw AssertionError("Expected PrivateKey, got $result")
        }

    private fun asRejected(result: KeyFileParse): KeyFileParse.Rejected =
        when (result) {
            is KeyFileParse.Rejected -> result
            else -> throw AssertionError("Expected Rejected, got $result")
        }

    @Test
    fun opensshUnencryptedDetected() {
        val key = asKey(KeyFileParser.parse(opensshPem("none").toByteArray()))
        assertEquals(KeyFileFormat.OPENSSH, key.format)
        assertFalse(key.encrypted)
    }

    @Test
    fun opensshEncryptedDetected() {
        val key = asKey(KeyFileParser.parse(opensshPem("aes256-ctr").toByteArray()))
        assertEquals(KeyFileFormat.OPENSSH, key.format)
        assertTrue(key.encrypted)
    }

    @Test
    fun pkcs8Detected() {
        val key = asKey(
            KeyFileParser.parse(pem("PRIVATE KEY", "QUJDZA==").toByteArray())
        )
        assertEquals(KeyFileFormat.PKCS8, key.format)
        assertFalse(key.encrypted)
    }

    @Test
    fun encryptedPkcs8Detected() {
        val key = asKey(
            KeyFileParser.parse(pem("ENCRYPTED PRIVATE KEY", "QUJDZA==").toByteArray())
        )
        assertEquals(KeyFileFormat.PKCS8, key.format)
        assertTrue(key.encrypted)
    }

    @Test
    fun traditionalRsaEncryptionHeaderDetected() {
        val encrypted = asKey(
            KeyFileParser.parse(
                pem(
                    "RSA PRIVATE KEY", "QUJDZA==",
                    listOf("Proc-Type: 4,ENCRYPTED", "DEK-Info: AES-128-CBC,0123456789ABCDEF")
                ).toByteArray()
            )
        )
        assertEquals(KeyFileFormat.RSA_PEM, encrypted.format)
        assertTrue(encrypted.encrypted)
        val plain = asKey(
            KeyFileParser.parse(pem("RSA PRIVATE KEY", "QUJDZA==").toByteArray())
        )
        assertFalse(plain.encrypted)
    }

    @Test
    fun ecPemDetected() {
        val key = asKey(
            KeyFileParser.parse(pem("EC PRIVATE KEY", "QUJDZA==").toByteArray())
        )
        assertEquals(KeyFileFormat.EC_PEM, key.format)
    }

    @Test
    fun mismatchedEndRejected() {
        val text = "-----BEGIN PRIVATE KEY-----\nQUJDZA==\n-----END RSA PRIVATE KEY-----\n"
        asRejected(KeyFileParser.parse(text.toByteArray()))
    }

    @Test
    fun nonKeyPemRejected() {
        asRejected(KeyFileParser.parse(pem("CERTIFICATE", "QUJDZA==").toByteArray()))
    }

    @Test
    fun passwordFileWithTrailingNewline() {
        val result = KeyFileParser.parse("hunter2\n".toByteArray())
        val password = result as? KeyFileParse.Password
            ?: throw AssertionError("Expected Password, got $result")
        assertEquals("hunter2", String(password.password))
    }

    @Test
    fun passwordFileWithoutNewlineAndCrlf() {
        val plain = KeyFileParser.parse("hunter2".toByteArray())
        assertTrue(plain is KeyFileParse.Password)
        val crlf = KeyFileParser.parse("hunter2\r\n".toByteArray())
        assertTrue(crlf is KeyFileParse.Password)
    }

    @Test
    fun multiLinePasswordRejected() {
        asRejected(KeyFileParser.parse("first\nsecond\n".toByteArray()))
    }

    @Test
    fun emptyOversizedBinaryAndControlRejected() {
        asRejected(KeyFileParser.parse(ByteArray(0)))
        asRejected(KeyFileParser.parse(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }))
        asRejected(KeyFileParser.parse(byteArrayOf(0x50, 0x4B, 0x00, 0x01)))
        asRejected(KeyFileParser.parse("bad\u0007pass\n".toByteArray()))
        asRejected(KeyFileParser.parse("x".repeat(1025).toByteArray()))
    }
}
