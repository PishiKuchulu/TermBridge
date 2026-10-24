package com.pishi.termbridge

import com.pishi.termbridge.data.BackupCodec
import com.pishi.termbridge.data.BackupException
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.BackupDocument
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.ExportButton
import com.pishi.termbridge.domain.ExportCredential
import com.pishi.termbridge.domain.ExportHost
import com.pishi.termbridge.domain.RemoteShell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private fun host(
        exportId: Int = 0,
        credential: ExportCredential? = null,
        hasCredential: Boolean = credential != null
    ) = ExportHost(
        exportId = exportId,
        name = "Home server",
        hostname = "192.168.1.10",
        port = 22,
        username = "admin",
        authType = AuthType.PASSWORD,
        notes = "",
        tags = "",
        credential = credential,
        hasCredential = hasCredential,
        buttons = listOf(
            ExportButton(
                label = "Update",
                category = "",
                sortOrder = 0,
                mode = CommandMode.EXECUTE_ONE,
                template = "sudo apt update",
                steps = emptyList(),
                variables = emptyList()
            )
        ),
        settings = null,
        envVars = emptyList(),
        tunnels = emptyList(),
        proxy = null,
        jumpHopExportIds = emptyList()
    )

    private fun plainText(document: BackupDocument): String =
        BackupCodec.encodeEnvelope(document, false, null)

    @Test
    fun plainRoundTrip() {
        val decoded = BackupCodec.decodeEnvelope(
            plainText(BackupDocument(listOf(host()))), null
        )
        assertFalse(decoded.encrypted)
        assertFalse(decoded.hasCredentials)
        assertEquals("Home server", decoded.document.hosts.single().name)
        assertEquals("Update", decoded.document.hosts.single().buttons.single().label)
    }

    @Test
    fun encryptedRoundTrip() {
        val text = BackupCodec.encodeEnvelope(
            BackupDocument(
                listOf(host(credential = ExportCredential(AuthType.PASSWORD, "s3cr3t")))
            ),
            true,
            "correct-horse-battery".toCharArray()
        )
        val decoded = BackupCodec.decodeEnvelope(text, "correct-horse-battery".toCharArray())
        assertTrue(decoded.encrypted)
        assertTrue(decoded.hasCredentials)
        assertEquals("s3cr3t", decoded.document.hosts.single().credential?.value)
    }

    @Test
    fun wrongPassphraseFailsWithoutDistinguishingCorruption() {
        val text = BackupCodec.encodeEnvelope(
            BackupDocument(listOf(host())),
            true,
            "correct-horse-battery".toCharArray()
        )
        try {
            BackupCodec.decodeEnvelope(text, "wrong-passphrase-here".toCharArray())
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertEquals("Wrong passphrase or corrupt backup file.", e.message)
        }
    }

    @Test
    fun tamperedEncryptedPayloadFails() {
        val text = BackupCodec.encodeEnvelope(
            BackupDocument(listOf(host())),
            true,
            "correct-horse-battery".toCharArray()
        )
        val envelope = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        val cipher = envelope["ciphertext"]!!.jsonPrimitive.content
        val tamperedCipher = if (cipher[0] == 'A') "B" + cipher.drop(1) else "A" + cipher.drop(1)
        envelope["ciphertext"] = kotlinx.serialization.json.JsonPrimitive(tamperedCipher)
        val tampered = JsonObject(envelope).toString()
        try {
            BackupCodec.decodeEnvelope(tampered, "correct-horse-battery".toCharArray())
            fail("Expected BackupException")
        } catch (e: BackupException) {
            // Expected: GCM failure surfaced as wrong-passphrase-or-corrupt.
        }
    }

    @Test
    fun malformedJsonFails() {
        try {
            BackupCodec.decodeEnvelope("{ not json", null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertEquals("Backup file is not valid JSON.", e.message)
        }
    }

    @Test
    fun wrongFormatFails() {
        val text = buildJsonObject {
            put("format", "something-else")
            put("version", 1)
            put("encrypted", false)
            put("payload", buildJsonObject { put("hosts", buildJsonArray {}) })
        }.toString()
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertEquals("Not a TermBridge backup file.", e.message)
        }
    }

    @Test
    fun wrongVersionFails() {
        val envelope = Json.parseToJsonElement(
            plainText(BackupDocument(listOf(host())))
        ).jsonObject.toMutableMap()
        envelope["version"] = JsonPrimitive(2)
        val text = JsonObject(envelope).toString()
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertEquals("Unsupported backup version.", e.message)
        }
    }

    @Test
    fun unknownFieldFails() {
        val text = plainText(BackupDocument(listOf(host())))
            .replace("\"tags\":\"\"", "\"tags\":\"\",\"evil\":1")
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("Unknown field"))
        }
    }

    @Test
    fun plaintextCredentialInPlainFileFails() {
        // Hand-craft a non-encrypted envelope that smuggles a
        // credential in the payload: the codec must reject it,
        // even though the JSON is otherwise well-formed.
        val plain = plainText(BackupDocument(listOf(host())))
        val injected = plain.replace(
            "\"credential\":null",
            "\"credential\":{\"kind\":\"PASSWORD\",\"value\":\"s3cr3t\"}"
        )
        assertTrue(injected.contains("s3cr3t"))
        try {
            BackupCodec.decodeEnvelope(injected, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("must not contain credentials"))
        }
    }

    @Test
    fun badHostPortFails() {
        val bad = host().copy(port = 0)
        try {
            BackupCodec.decodeEnvelope(plainText(BackupDocument(listOf(bad))), null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            // Expected: host validation rejects port 0.
        }
    }

    @Test
    fun tooManyHostsFails() {
        val many = (0 until 501).map { host(exportId = it) }
        val document = BackupDocument(many.mapIndexed { i, h -> h.copy(name = "Host $i") })
        try {
            BackupCodec.decodeEnvelope(plainText(document), null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("too many hosts"))
        }
    }

    @Test
    fun missingJumpHopDropsChainWithWarning() {
        val withChain = host().copy(jumpHopExportIds = listOf(99))
        val decoded = BackupCodec.decodeEnvelope(
            plainText(BackupDocument(listOf(withChain))), null
        )
        assertTrue(decoded.document.hosts.single().jumpHopExportIds.isEmpty())
        assertTrue(decoded.warnings.any { it.contains("Jump chain") })
    }

    @Test
    fun shortPassphraseForEncryptedEncodeFails() {
        try {
            BackupCodec.encodeEnvelope(
                BackupDocument(listOf(host())), true, "short".toCharArray()
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected: passphrase policy enforced in the codec.
        }
    }

    @Test
    fun encryptedFileWithoutPassphraseFails() {
        val text = BackupCodec.encodeEnvelope(
            BackupDocument(listOf(host())), true,
            "correct-horse-battery".toCharArray()
        )
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("passphrase"))
        }
    }

    @Test
    fun remoteShellRoundTrip() {
        val withSettings = host().copy(
            settings = com.pishi.termbridge.domain.ExportSettings(
                "/home/admin", RemoteShell.POSIX
            )
        )
        val decoded = BackupCodec.decodeEnvelope(
            plainText(BackupDocument(listOf(withSettings))), null
        )
        assertNotNull(decoded.document.hosts.single().settings)
        assertEquals("/home/admin", decoded.document.hosts.single().settings?.remoteWorkingDirectory)
        assertEquals(RemoteShell.POSIX, decoded.document.hosts.single().settings?.remoteShell)
    }
}
