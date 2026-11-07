package com.pishi.termbridge

import com.pishi.termbridge.data.BackupCodec
import com.pishi.termbridge.data.BackupException
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.BackupDocument
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.ExportButton
import com.pishi.termbridge.domain.ExportCredential
import com.pishi.termbridge.domain.ExportHost
import com.pishi.termbridge.domain.ExportKeychainEntry
import com.pishi.termbridge.domain.ExportVariable
import com.pishi.termbridge.domain.MAX_FIELD_CHARS
import com.pishi.termbridge.domain.MAX_SHORT_FIELD_CHARS
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

    private fun keychainEntry(
        label: String = "Prod password",
        material: ExportCredential? = null
    ) = ExportKeychainEntry(
        label = label,
        kind = AuthType.PASSWORD,
        material = material,
        hasMaterial = true,
        publicKeyText = null,
        fingerprint = null
    )

    @Test
    fun keychainEncryptedRoundTrip() {
        val document = BackupDocument(
            hosts = listOf(host(hasCredential = true).copy(keychainLabel = "Prod password")),
            keychain = listOf(
                keychainEntry(material = ExportCredential(AuthType.PASSWORD, "shared-secret"))
            )
        )
        val text = BackupCodec.encodeEnvelope(
            document, true, "correct-horse-battery".toCharArray()
        )
        val decoded = BackupCodec.decodeEnvelope(text, "correct-horse-battery".toCharArray())
        assertTrue(decoded.hasCredentials)
        val entry = decoded.document.keychain.single()
        assertEquals("shared-secret", entry.material?.value)
        assertEquals("Prod password", decoded.document.hosts.single().keychainLabel)
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun transcriptEncryptedRoundTrip() {
        val document = BackupDocument(
            hosts = listOf(host(hasCredential = true)),
            transcripts = listOf(
                com.pishi.termbridge.domain.ExportTranscript(
                    "Home server", "line one\nline two\n"
                )
            )
        )
        val text = BackupCodec.encodeEnvelope(
            document, true, "correct-horse-battery".toCharArray()
        )
        val decoded = BackupCodec.decodeEnvelope(
            text, "correct-horse-battery".toCharArray()
        )
        val transcript = decoded.document.transcripts.single()
        assertEquals("Home server", transcript.hostLabel)
        assertEquals("line one\nline two\n", transcript.text)
    }

    @Test
    fun transcriptInPlainFileFails() {
        val document = BackupDocument(
            hosts = listOf(host(hasCredential = true)),
            transcripts = listOf(
                com.pishi.termbridge.domain.ExportTranscript("Home server", "x")
            )
        )
        try {
            BackupCodec.decodeEnvelope(plainText(document), null)
            fail("A transcript in a plain backup must be rejected")
        } catch (_: BackupException) {
            // Expected.
        }
    }

    @Test
    fun keychainPlainMetadataRoundTrip() {
        val document = BackupDocument(
            hosts = listOf(host(hasCredential = true).copy(keychainLabel = "Prod password")),
            keychain = listOf(keychainEntry())
        )
        val decoded = BackupCodec.decodeEnvelope(plainText(document), null)
        assertFalse(decoded.hasCredentials)
        assertEquals(1, decoded.document.keychain.size)
        assertEquals("Prod password", decoded.document.hosts.single().keychainLabel)
    }

    @Test
    fun snippetsRoundTripPlainAndEncrypted() {
        // Item T-02: global snippets keep their full shape —
        // steps and variables included — through both modes.
        val snippet = ExportButton(
            label = "Deploy",
            category = "ops",
            sortOrder = 3,
            mode = CommandMode.SEQUENCE,
            template = "",
            steps = listOf("git pull", "systemctl restart {{unit}}"),
            variables = listOf(
                ExportVariable(name = "unit", defaultValue = "app", required = true)
            )
        )
        val document = BackupDocument(
            hosts = listOf(host()),
            snippets = listOf(snippet)
        )
        val plain = BackupCodec.decodeEnvelope(plainText(document), null)
        assertEquals(listOf(snippet), plain.document.snippets)
        val encryptedText = BackupCodec.encodeEnvelope(
            document, true, "correct-horse-battery".toCharArray()
        )
        val encrypted = BackupCodec.decodeEnvelope(
            encryptedText, "correct-horse-battery".toCharArray()
        )
        assertEquals(listOf(snippet), encrypted.document.snippets)
    }

    @Test
    fun favoriteAndGroupRoundTrip() {
        // Item T-22: organization fields are configuration and
        // ride in both export modes; old files without them are
        // covered by oldFileWithoutKeychainSectionDecodes (the
        // fields are optional on decode).
        val document = BackupDocument(
            listOf(host().copy(favorite = true, groupName = "Work"))
        )
        val plain = BackupCodec.decodeEnvelope(plainText(document), null)
        assertTrue(plain.document.hosts.single().favorite)
        assertEquals("Work", plain.document.hosts.single().groupName)
        val encryptedText = BackupCodec.encodeEnvelope(
            document, true, "correct-horse-battery".toCharArray()
        )
        val encrypted = BackupCodec.decodeEnvelope(
            encryptedText, "correct-horse-battery".toCharArray()
        )
        assertTrue(encrypted.document.hosts.single().favorite)
        assertEquals("Work", encrypted.document.hosts.single().groupName)
    }

    @Test
    fun keychainMaterialInPlainFileFails() {
        val document = BackupDocument(
            hosts = emptyList(),
            keychain = listOf(
                keychainEntry(material = ExportCredential(AuthType.PASSWORD, "shared-secret"))
            )
        )
        try {
            BackupCodec.decodeEnvelope(plainText(document), null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("must not contain keychain material"))
        }
    }

    @Test
    fun oldFileWithoutKeychainSectionDecodes() {
        // A v1 payload written before item T-01: no "keychain"
        // section and no per-host "keychainLabel" field at all.
        val payload = buildJsonObject {
            put("hosts", buildJsonArray {
                add(buildJsonObject {
                    put("exportId", JsonPrimitive(0))
                    put("name", JsonPrimitive("Old host"))
                    put("hostname", JsonPrimitive("192.0.2.5"))
                    put("port", JsonPrimitive(22))
                    put("username", JsonPrimitive("root"))
                    put("authType", JsonPrimitive("PASSWORD"))
                    put("notes", JsonPrimitive(""))
                    put("tags", JsonPrimitive(""))
                    put("credential", kotlinx.serialization.json.JsonNull)
                    put("hasCredential", JsonPrimitive(false))
                    put("buttons", buildJsonArray {})
                    put("settings", kotlinx.serialization.json.JsonNull)
                    put("envVars", buildJsonArray {})
                    put("tunnels", buildJsonArray {})
                    put("proxy", kotlinx.serialization.json.JsonNull)
                    put("jumpHopExportIds", buildJsonArray {})
                })
            })
        }
        val envelope = buildJsonObject {
            put("format", JsonPrimitive("termbridge-backup"))
            put("version", JsonPrimitive(1))
            put("encrypted", JsonPrimitive(false))
            put("payload", payload)
        }
        val decoded = BackupCodec.decodeEnvelope(envelope.toString(), null)
        assertEquals("Old host", decoded.document.hosts.single().name)
        assertTrue(decoded.document.keychain.isEmpty())
        assertEquals(null, decoded.document.hosts.single().keychainLabel)
    }

    @Test
    fun unknownKeychainLabelDropsLinkWithWarning() {
        val document = BackupDocument(
            hosts = listOf(host(hasCredential = true).copy(keychainLabel = "Ghost")),
            keychain = emptyList()
        )
        val decoded = BackupCodec.decodeEnvelope(plainText(document), null)
        assertEquals(null, decoded.document.hosts.single().keychainLabel)
        assertTrue(decoded.warnings.any { it.contains("not in this backup") })
    }

    @Test
    fun kindMismatchedKeychainLinkDropsWithWarning() {
        val keyEntry = ExportKeychainEntry(
            label = "A key",
            kind = AuthType.PRIVATE_KEY,
            material = null,
            hasMaterial = true,
            publicKeyText = null,
            fingerprint = null
        )
        val document = BackupDocument(
            hosts = listOf(host(hasCredential = true).copy(keychainLabel = "A key")),
            keychain = listOf(keyEntry)
        )
        val decoded = BackupCodec.decodeEnvelope(plainText(document), null)
        assertEquals(null, decoded.document.hosts.single().keychainLabel)
        assertTrue(decoded.warnings.any { it.contains("does not match") })
    }

    @Test
    fun duplicateKeychainLabelsFail() {
        val document = BackupDocument(
            hosts = emptyList(),
            keychain = listOf(keychainEntry(), keychainEntry())
        )
        try {
            BackupCodec.decodeEnvelope(plainText(document), null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("labels must be unique"))
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

    // ---- Audit #7 strictness: AUD-001/002/002b/003 ----

    @Test
    fun deeplyNestedJsonFailsWithBackupException() {
        // AUD-001: without the depth guard this input escapes
        // as a StackOverflowError (an Error), crashing the
        // process instead of producing this exception.
        val text = "[".repeat(5_000) + "]".repeat(5_000)
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("deeply nested"))
        }
    }

    @Test
    fun deeplyNestedInsidePayloadFails() {
        val plain = plainText(BackupDocument(listOf(host())))
        val injected = plain.replace(
            "\"tags\":\"\"",
            "\"tags\":\"\",\"evil\":" + "[".repeat(500) + "]".repeat(500)
        )
        try {
            BackupCodec.decodeEnvelope(injected, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("deeply nested"))
        }
    }

    @Test
    fun quotedPortFails() {
        // AUD-002: "22" is a string, not an int.
        val text = plainText(BackupDocument(listOf(host())))
            .replace("\"port\":22", "\"port\":\"22\"")
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("wrong type"))
        }
    }

    @Test
    fun quotedVersionFails() {
        val text = plainText(BackupDocument(listOf(host())))
            .replace("\"version\":1", "\"version\":\"1\"")
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertEquals("Unsupported backup version.", e.message)
        }
    }

    @Test
    fun nullUsernameFails() {
        // AUD-002b: JSON null must not decode as the text "null".
        val text = plainText(BackupDocument(listOf(host())))
            .replace("\"username\":\"admin\"", "\"username\":null")
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("wrong type"))
        }
    }

    @Test
    fun nullButtonStepFails() {
        val text = plainText(BackupDocument(listOf(host())))
            .replace("\"steps\":[]", "\"steps\":[null]")
        try {
            BackupCodec.decodeEnvelope(text, null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("steps"))
        }
    }

    @Test
    fun overlongNameFails() {
        // AUD-003: per-field length caps on decode.
        val bad = host().copy(name = "x".repeat(MAX_SHORT_FIELD_CHARS + 1))
        try {
            BackupCodec.decodeEnvelope(plainText(BackupDocument(listOf(bad))), null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }

    @Test
    fun overlongNotesFails() {
        val bad = host().copy(notes = "x".repeat(MAX_FIELD_CHARS + 1))
        try {
            BackupCodec.decodeEnvelope(plainText(BackupDocument(listOf(bad))), null)
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }
}
