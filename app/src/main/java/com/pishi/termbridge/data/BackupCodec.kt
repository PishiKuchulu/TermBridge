package com.pishi.termbridge.data

import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.BACKUP_FORMAT
import com.pishi.termbridge.domain.BACKUP_VERSION
import com.pishi.termbridge.domain.BackupDocument
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandValidator
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.EnvironmentValidator
import com.pishi.termbridge.domain.ExportButton
import com.pishi.termbridge.domain.ExportCredential
import com.pishi.termbridge.domain.ExportEnvVar
import com.pishi.termbridge.domain.ExportHost
import com.pishi.termbridge.domain.ExportKeychainEntry
import com.pishi.termbridge.domain.ExportProxy
import com.pishi.termbridge.domain.ExportSettings
import com.pishi.termbridge.domain.ExportTunnel
import com.pishi.termbridge.domain.ExportVariable
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import com.pishi.termbridge.domain.JumpChainValidator
import com.pishi.termbridge.domain.KeychainValidator
import com.pishi.termbridge.domain.MAX_BUTTONS_PER_HOST
import com.pishi.termbridge.domain.MAX_KEYCHAIN_ENTRIES_IN_BACKUP
import com.pishi.termbridge.domain.MAX_SNIPPETS_IN_BACKUP
import com.pishi.termbridge.domain.MAX_ENV_VARS_PER_HOST
import com.pishi.termbridge.domain.MAX_HOSTS_IN_BACKUP
import com.pishi.termbridge.domain.MAX_TUNNELS_PER_HOST
import com.pishi.termbridge.domain.MIN_EXPORT_PASSPHRASE_LENGTH
import com.pishi.termbridge.domain.MIN_IMPORT_PASSPHRASE_LENGTH
import com.pishi.termbridge.domain.MAX_BACKUP_BYTES
import com.pishi.termbridge.domain.JsonDepth
import com.pishi.termbridge.domain.MAX_FIELD_CHARS
import com.pishi.termbridge.domain.MAX_JSON_DEPTH
import com.pishi.termbridge.domain.MAX_SHORT_FIELD_CHARS
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.ProxySpecParser
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import com.pishi.termbridge.domain.TunnelValidator
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.security.ExportCiphertext
import com.pishi.termbridge.security.ExportCrypto
import com.pishi.termbridge.security.ExportCryptoException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** A backup failure. Messages name fields, never values — a
 *  message must be safe to show on screen and to put in a
 *  diagnostic, even when the file under inspection is hostile. */
class BackupException(message: String) : Exception(message)

/** A decoded envelope: the document, whether it arrived
 *  encrypted, whether it carries any credential, and decode
 *  warnings (jump-hop drops only — everything else fails the
 *  whole file). Warnings carry host NAMES only, never values. */
data class ParsedBackup(
    val document: BackupDocument,
    val encrypted: Boolean,
    val hasCredentials: Boolean,
    val warnings: List<String> = emptyList()
)

/**
 * Prompt 11 backup codec: the ONLY import/export format.
 *
 * Envelope (the file itself, always JSON text):
 * - Non-secret export: {"format":"termbridge-backup","version":1,
 *   "encrypted":false,"payload":{...document...}}
 * - Encrypted backup: {"format":"termbridge-backup","version":1,
 *   "encrypted":true,"kdf":"PBKDF2WithHmacSHA256",
 *   "iterations":600000,"salt":"<base64>","iv":"<base64>",
 *   "ciphertext":"<base64>"} — the ciphertext is the whole
 *   document JSON, AES-256-GCM encrypted (see ExportCrypto).
 *
 * Document (payload, or the decrypted inner JSON):
 * {"hosts":[{ "exportId":0,"name":...,"hostname":...,"port":22,
 *   "username":...,"authType":"PASSWORD","notes":...,"tags":...,
 *   "credential":null|{"kind":"PASSWORD","value":...},
 *   "hasCredential":true,
 *   "buttons":[{label,category,sortOrder,mode,template,steps,
 *     variables:[{name,defaultValue,required}]}],
 *   "settings":null|{"remoteWorkingDirectory":...,
 *     "remoteShell":"POSIX"},
 *   "envVars":[{name,value}],
 *   "tunnels":[{kind,bindAddress,bindPort,targetHost,targetPort,
 *     allowNonLoopback,enabled,label}],
 *   "proxy":null|{kind,proxyHost,proxyPort},
 *   "jumpHopExportIds":[1,2],
 *   "keychainLabel":null|"Prod key",
 *   "favorite":false,"groupName":"" }],
 *  "keychain":[{ "label":...,"kind":"PRIVATE_KEY",
 *   "material":null|{"kind":"PRIVATE_KEY","value":...},
 *   "hasMaterial":true,"publicKeyText":...,"fingerprint":... }]}
 * The "keychain" section and the per-host "keychainLabel" are
 * item T-01 additions: both are optional on decode so backups
 * written before the keychain existed still open; keychain
 * material obeys the same encrypted-only rule as credentials.
 * The "snippets" section (item T-02) carries the global
 * snippets in the same shape as a host's "buttons" array and
 * is likewise optional on decode.
 *
 * Fail-closed rules, all enforced here (never in the UI):
 * - Wrong format string, wrong version, malformed JSON, wrong
 *   types, missing required fields, or ANY unknown field at any
 *   level -> whole file rejected.
 * - Structural depth and per-field length caps (audit #7
 *   AUD-001/003): nesting beyond MAX_JSON_DEPTH is rejected
 *   before parsing, so a hostile file gets a BackupException,
 *   never a StackOverflowError crash; primitive types are
 *   checked strictly (a quoted "22" is not an int, and JSON
 *   null is never the string "null" — AUD-002/002b).
 * - A non-encrypted file containing a credential or an env value
 *   -> whole file rejected (a plaintext secret in a file that
 *   claims to be secret-free is a leak, not a warning).
 * - Host/button/env/tunnel/proxy shapes re-run their existing
 *   validators, so import cannot smuggle in a record the app
 *   would refuse to save by hand.
 * - Jump-hop references to missing hosts are the ONE forgiving
 *   case: the hop is dropped with a warning (a chain with a
 *   missing hop is not a route at all, so the whole chain is
 *   dropped if any hop is missing — see decode).
 * - No trusted host key field exists anywhere in this format, so
 *   import cannot create trust. No command is ever executed by
 *   decoding: buttons are data until the user presses one.
 */
object BackupCodec {
    private val envelopePlainKeys = setOf("format", "version", "encrypted", "payload")
    private val envelopeEncryptedKeys =
        setOf("format", "version", "encrypted", "kdf", "iterations", "salt", "iv", "ciphertext")
    private val hostKeys = setOf(
        "exportId", "name", "hostname", "port", "username", "authType",
        "notes", "tags", "credential", "hasCredential", "buttons",
        "settings", "envVars", "tunnels", "proxy", "jumpHopExportIds",
        // Optional: backups written before the keychain existed
        // do not carry it (allowed, never required).
        "keychainLabel",
        // Optional: item T-22 organization fields, same rule.
        "favorite", "groupName"
    )
    /** hostKeys minus the fields added after v1 files exist:
     *  allowed on decode, never required. */
    private val hostRequiredKeys = hostKeys - "keychainLabel" - "favorite" - "groupName"
    private val credentialKeys = setOf("kind", "value")
    private val keychainEntryKeys = setOf(
        "label", "kind", "material", "hasMaterial", "publicKeyText", "fingerprint"
    )
    private val buttonKeys =
        setOf("label", "category", "sortOrder", "mode", "template", "steps", "variables")
    private val variableKeys = setOf("name", "defaultValue", "required")
    private val settingsKeys = setOf("remoteWorkingDirectory", "remoteShell")
    private val envVarKeys = setOf("name", "value")
    private val tunnelKeys = setOf(
        "kind", "bindAddress", "bindPort", "targetHost", "targetPort",
        "allowNonLoopback", "enabled", "label"
    )
    private val proxyKeys = setOf("kind", "proxyHost", "proxyPort")

    fun encodeEnvelope(
        document: BackupDocument,
        encrypted: Boolean,
        passphrase: CharArray?
    ): String {
        val payload = encodeDocument(document)
        if (!encrypted) {
            return buildJsonObject {
                put("format", JsonPrimitive(BACKUP_FORMAT))
                put("version", JsonPrimitive(BACKUP_VERSION))
                put("encrypted", JsonPrimitive(false))
                put("payload", payload)
            }.toString()
        }
        require(passphrase != null && passphrase.size >= MIN_EXPORT_PASSPHRASE_LENGTH) {
            "A passphrase of at least $MIN_EXPORT_PASSPHRASE_LENGTH characters is required."
        }
        val blob = ExportCrypto.encrypt(payload.toString().toByteArray(Charsets.UTF_8), passphrase)
        return buildJsonObject {
            put("format", JsonPrimitive(BACKUP_FORMAT))
            put("version", JsonPrimitive(BACKUP_VERSION))
            put("encrypted", JsonPrimitive(true))
            put("kdf", JsonPrimitive(ExportCrypto.KDF_ALGORITHM))
            put("iterations", JsonPrimitive(ExportCrypto.ITERATIONS))
            put("salt", JsonPrimitive(Base64.getEncoder().encodeToString(blob.salt)))
            put("iv", JsonPrimitive(Base64.getEncoder().encodeToString(blob.iv)))
            put("ciphertext", JsonPrimitive(Base64.getEncoder().encodeToString(blob.ciphertext)))
        }.toString()
    }

    fun decodeEnvelope(text: String, passphrase: CharArray?): ParsedBackup {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
            throw BackupException("Backup file is too large.")
        }
        // Audit #7 AUD-001: reject hostile nesting BEFORE the
        // recursive-descent parser sees it — its failure mode
        // is a StackOverflowError, which catch (e: Exception)
        // below cannot convert into a BackupException.
        if (JsonDepth.exceeds(text, MAX_JSON_DEPTH)) {
            throw BackupException("Backup file is too deeply nested.")
        }
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: StackOverflowError) {
            throw BackupException("Backup file is too deeply nested.")
        } catch (e: Exception) {
            throw BackupException("Backup file is not valid JSON.")
        }
        if (root["format"]?.jsonPrimitive?.contentOrNull != BACKUP_FORMAT) {
            throw BackupException("Not a TermBridge backup file.")
        }
        // Audit #7 AUD-002: a quoted "1" is not the number 1 —
        // strict primitive typing at the envelope level too.
        val version = try {
            (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.int
        } catch (e: Exception) {
            null
        }
        if (version != BACKUP_VERSION) {
            throw BackupException("Unsupported backup version.")
        }
        val encryptedElement = root["encrypted"]
        val encrypted = if (encryptedElement is JsonPrimitive && !encryptedElement.isString) {
            try {
                encryptedElement.content.toBooleanStrict()
            } catch (e: Exception) {
                throw BackupException("Backup file has an invalid encrypted flag.")
            }
        } else {
            throw BackupException("Backup file has an invalid encrypted flag.")
        }
        val payloadText: String
        if (encrypted) {
            checkKeys(root, envelopeEncryptedKeys, envelopeEncryptedKeys, "envelope")
            val iterations = try {
                (root["iterations"] as? JsonPrimitive)?.takeIf { !it.isString }?.int
            } catch (e: Exception) {
                null
            }
            if (root["kdf"]?.jsonPrimitive?.contentOrNull != ExportCrypto.KDF_ALGORITHM ||
                iterations != ExportCrypto.ITERATIONS
            ) {
                throw BackupException("Unsupported backup encryption parameters.")
            }
            if (passphrase == null || passphrase.size < MIN_IMPORT_PASSPHRASE_LENGTH) {
                throw BackupException("A passphrase is required to open this backup.")
            }
            val blob = try {
                ExportCiphertext(
                    salt = Base64.getDecoder().decode(root.reqString("salt", "envelope")),
                    iv = Base64.getDecoder().decode(root.reqString("iv", "envelope")),
                    ciphertext = Base64.getDecoder()
                        .decode(root.reqString("ciphertext", "envelope"))
                )
            } catch (e: IllegalArgumentException) {
                throw BackupException("Backup file has invalid encrypted data.")
            }
            val plain = try {
                ExportCrypto.decrypt(blob, passphrase)
            } catch (e: ExportCryptoException) {
                throw BackupException(e.message ?: "Wrong passphrase or corrupt backup file.")
            }
            payloadText = String(plain, Charsets.UTF_8)
        } else {
            checkKeys(root, envelopePlainKeys, envelopePlainKeys, "envelope")
            val payload = try {
                root["payload"]?.jsonObject
            } catch (e: Exception) {
                null
            } ?: throw BackupException("Backup file has no payload.")
            payloadText = payload.toString()
        }
        val (document, warnings) = decodeDocument(payloadText, encrypted)
        return ParsedBackup(
            document = document,
            encrypted = encrypted,
            hasCredentials = document.hosts.any { it.credential != null } ||
                document.keychain.any { it.material != null },
            warnings = warnings
        )
    }

    // ---- document encode ----

    private fun encodeDocument(document: BackupDocument): JsonObject = buildJsonObject {
        put("hosts", buildJsonArray {
            for (host in document.hosts) {
                add(buildJsonObject {
                    put("exportId", JsonPrimitive(host.exportId))
                    put("name", JsonPrimitive(host.name))
                    put("hostname", JsonPrimitive(host.hostname))
                    put("port", JsonPrimitive(host.port))
                    put("username", JsonPrimitive(host.username))
                    put("authType", JsonPrimitive(host.authType.name))
                    put("notes", JsonPrimitive(host.notes))
                    put("tags", JsonPrimitive(host.tags))
                    if (host.credential != null) {
                        put("credential", buildJsonObject {
                            put("kind", JsonPrimitive(host.credential.kind.name))
                            put("value", JsonPrimitive(host.credential.value))
                        })
                    } else {
                        put("credential", JsonNull)
                    }
                    put("hasCredential", JsonPrimitive(host.hasCredential))
                    put("buttons", buildJsonArray {
                        for (button in host.buttons) {
                            add(buildJsonObject {
                                put("label", JsonPrimitive(button.label))
                                put("category", JsonPrimitive(button.category))
                                put("sortOrder", JsonPrimitive(button.sortOrder))
                                put("mode", JsonPrimitive(button.mode.name))
                                put("template", JsonPrimitive(button.template))
                                put("steps", buildJsonArray {
                                    for (step in button.steps) add(JsonPrimitive(step))
                                })
                                put("variables", buildJsonArray {
                                    for (variable in button.variables) {
                                        add(buildJsonObject {
                                            put("name", JsonPrimitive(variable.name))
                                            put("defaultValue", JsonPrimitive(variable.defaultValue))
                                            put("required", JsonPrimitive(variable.required))
                                        })
                                    }
                                })
                            })
                        }
                    })
                    if (host.settings != null) {
                        put("settings", buildJsonObject {
                            put("remoteWorkingDirectory", JsonPrimitive(host.settings.remoteWorkingDirectory))
                            put("remoteShell", JsonPrimitive(host.settings.remoteShell.name))
                        })
                    } else {
                        put("settings", JsonNull)
                    }
                    put("envVars", buildJsonArray {
                        for (variable in host.envVars) {
                            add(buildJsonObject {
                                put("name", JsonPrimitive(variable.name))
                                put("value", JsonPrimitive(variable.value))
                            })
                        }
                    })
                    put("tunnels", buildJsonArray {
                        for (tunnel in host.tunnels) {
                            add(buildJsonObject {
                                put("kind", JsonPrimitive(tunnel.kind.name))
                                put("bindAddress", JsonPrimitive(tunnel.bindAddress))
                                put("bindPort", JsonPrimitive(tunnel.bindPort))
                                put("targetHost", JsonPrimitive(tunnel.targetHost))
                                put("targetPort", JsonPrimitive(tunnel.targetPort))
                                put("allowNonLoopback", JsonPrimitive(tunnel.allowNonLoopback))
                                put("enabled", JsonPrimitive(tunnel.enabled))
                                put("label", JsonPrimitive(tunnel.label))
                            })
                        }
                    })
                    if (host.proxy != null) {
                        put("proxy", buildJsonObject {
                            put("kind", JsonPrimitive(host.proxy.kind.name))
                            put("proxyHost", JsonPrimitive(host.proxy.proxyHost))
                            put("proxyPort", JsonPrimitive(host.proxy.proxyPort))
                        })
                    } else {
                        put("proxy", JsonNull)
                    }
                    put("jumpHopExportIds", buildJsonArray {
                        for (hop in host.jumpHopExportIds) add(JsonPrimitive(hop))
                    })
                    if (host.keychainLabel != null) {
                        put("keychainLabel", JsonPrimitive(host.keychainLabel))
                    } else {
                        put("keychainLabel", JsonNull)
                    }
                    put("favorite", JsonPrimitive(host.favorite))
                    put("groupName", JsonPrimitive(host.groupName))
                })
            }
        })
        put("keychain", buildJsonArray {
            for (entry in document.keychain) {
                add(buildJsonObject {
                    put("label", JsonPrimitive(entry.label))
                    put("kind", JsonPrimitive(entry.kind.name))
                    if (entry.material != null) {
                        put("material", buildJsonObject {
                            put("kind", JsonPrimitive(entry.material.kind.name))
                            put("value", JsonPrimitive(entry.material.value))
                        })
                    } else {
                        put("material", JsonNull)
                    }
                    put("hasMaterial", JsonPrimitive(entry.hasMaterial))
                    if (entry.publicKeyText != null) {
                        put("publicKeyText", JsonPrimitive(entry.publicKeyText))
                    } else {
                        put("publicKeyText", JsonNull)
                    }
                    if (entry.fingerprint != null) {
                        put("fingerprint", JsonPrimitive(entry.fingerprint))
                    } else {
                        put("fingerprint", JsonNull)
                    }
                })
            }
        })
        put("snippets", buildJsonArray {
            for (button in document.snippets) {
                add(buildJsonObject {
                    put("label", JsonPrimitive(button.label))
                    put("category", JsonPrimitive(button.category))
                    put("sortOrder", JsonPrimitive(button.sortOrder))
                    put("mode", JsonPrimitive(button.mode.name))
                    put("template", JsonPrimitive(button.template))
                    put("steps", buildJsonArray {
                        for (step in button.steps) add(JsonPrimitive(step))
                    })
                    put("variables", buildJsonArray {
                        for (variable in button.variables) {
                            add(buildJsonObject {
                                put("name", JsonPrimitive(variable.name))
                                put("defaultValue", JsonPrimitive(variable.defaultValue))
                                put("required", JsonPrimitive(variable.required))
                            })
                        }
                    })
                })
            }
        })
        // Item T-03: transcripts (encrypted files, opted-in
        // hosts only — enforced by BackupService on export and
        // re-checked here: a transcript in a PLAIN file is
        // rejected like keychain material is).
        put("transcripts", buildJsonArray {
            for (transcript in document.transcripts) {
                add(buildJsonObject {
                    put("hostLabel", JsonPrimitive(transcript.hostLabel))
                    put("text", JsonPrimitive(transcript.text))
                })
            }
        })
    }

    // ---- document decode ----

    private fun decodeDocument(
        text: String,
        encrypted: Boolean
    ): Pair<BackupDocument, List<String>> {
        val warnings = mutableListOf<String>()
        // Audit #7 AUD-001: same depth guard as the envelope —
        // this parse sees the DECRYPTED text, which the
        // envelope-level guard never inspected.
        if (JsonDepth.exceeds(text, MAX_JSON_DEPTH)) {
            throw BackupException("Backup payload is too deeply nested.")
        }
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: StackOverflowError) {
            throw BackupException("Backup payload is too deeply nested.")
        } catch (e: Exception) {
            throw BackupException("Backup payload is not valid JSON.")
        }
        // "keychain" and "snippets" are allowed but never
        // required: backups written before items T-01/T-02 do
        // not carry those sections.
        checkKeys(root, setOf("hosts", "keychain", "snippets", "transcripts"), setOf("hosts"), "payload")
        val hostArray = try {
            root["hosts"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: throw BackupException("Backup payload has no host list.")
        if (hostArray.size > MAX_HOSTS_IN_BACKUP) {
            throw BackupException("Backup contains too many hosts.")
        }
        val hosts = hostArray.mapIndexed { index, element ->
            decodeHost(element, "host #$index", encrypted)
        }
        val keychain = decodeKeychain(root, encrypted)
        val snippets = decodeSnippets(root)
        val transcripts = decodeTranscripts(root, encrypted)
        val exportIds = hosts.map { it.exportId }
        if (exportIds.size != exportIds.toSet().size) {
            throw BackupException("Backup host ids must be unique.")
        }
        if (exportIds.any { it < 0 }) {
            throw BackupException("Backup host ids must not be negative.")
        }
        // Resolve jump hops now that every host is known. A chain
        // with any missing hop is dropped whole, with a warning:
        // a partial chain is a different route, not a safe subset.
        val known = exportIds.toSet()
        val resolved = hosts.map { host ->
            val hops = host.jumpHopExportIds
            if (hops.isEmpty()) {
                host
            } else if (hops.any { it !in known }) {
                warnings +=
                    "Jump chain for host \"${host.name}\" was skipped: " +
                        "it references a host that is not in this backup."
                host.copy(jumpHopExportIds = emptyList())
            } else {
                val deduped = hops.distinct()
                if (deduped.size != hops.size) {
                    warnings +=
                        "Duplicate jump hop in the chain for host " +
                            "\"${host.name}\" was removed."
                }
                if (hops.contains(host.exportId)) {
                    warnings +=
                        "Jump chain for host \"${host.name}\" was skipped: " +
                            "a host cannot jump through itself."
                    host.copy(jumpHopExportIds = emptyList())
                } else if (deduped.size > JumpChainValidator.MAX_HOPS) {
                    throw BackupException("A jump chain has too many hops.")
                } else {
                    host.copy(jumpHopExportIds = deduped)
                }
            }
        }
        // Resolve keychain links with the same forgiveness as
        // jump chains: a link to an entry that is not in this
        // backup — or whose kind does not match the host — is
        // dropped with a warning, never silently kept (a kept
        // link would fail the host's save at import) and never
        // a reason to reject the whole file.
        val entriesByLabel = keychain.associateBy { it.label }
        val linked = resolved.map { host ->
            val label = host.keychainLabel ?: return@map host
            val entry = entriesByLabel[label]
            when {
                entry == null -> {
                    warnings +=
                        "Keychain link for host \"${host.name}\" was skipped: " +
                            "entry \"$label\" is not in this backup."
                    host.copy(keychainLabel = null)
                }
                entry.kind != host.authType -> {
                    warnings +=
                        "Keychain link for host \"${host.name}\" was skipped: " +
                            "entry \"$label\" does not match the host's authentication type."
                    host.copy(keychainLabel = null)
                }
                else -> host
            }
        }
        return BackupDocument(linked, keychain, snippets, transcripts) to warnings
    }

    /** Item T-03: the transcripts section. Present only in
     *  encrypted files — a transcript in a plain file fails the
     *  whole decode, the same rule as keychain material. */
    private fun decodeTranscripts(
        root: JsonObject,
        encrypted: Boolean
    ): List<com.pishi.termbridge.domain.ExportTranscript> {
        val element = root["transcripts"] ?: return emptyList()
        val array = try {
            element.jsonArray
        } catch (e: Exception) {
            throw BackupException("Backup transcripts section is malformed.")
        }
        if (array.size > com.pishi.termbridge.domain.MAX_TRANSCRIPTS_IN_BACKUP) {
            throw BackupException("Backup contains too many transcripts.")
        }
        if (array.isNotEmpty() && !encrypted) {
            throw BackupException(
                "Session transcripts must not appear in an unencrypted backup."
            )
        }
        return array.map { item ->
            val obj = try {
                item.jsonObject
            } catch (e: Exception) {
                throw BackupException("Backup transcript entry is malformed.")
            }
            // Audit #7 AUD-002b: strict string typing — a JSON
            // null here used to decode as the literal text
            // "null" via .content.
            val label = (obj["hostLabel"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw BackupException("Backup transcript is missing its host label.")
            val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw BackupException("Backup transcript is missing its text.")
            if (label.isBlank()) {
                throw BackupException("Backup transcript has a blank host label.")
            }
            if (label.length > MAX_SHORT_FIELD_CHARS) {
                throw BackupException("Backup transcript host label is too long.")
            }
            if (text.length > com.pishi.termbridge.domain.MAX_TRANSCRIPT_CHARS) {
                throw BackupException("Backup transcript is too large.")
            }
            com.pishi.termbridge.domain.ExportTranscript(label, text)
        }
    }

    private fun decodeKeychain(root: JsonObject, encrypted: Boolean): List<ExportKeychainEntry> {
        val element = root["keychain"] ?: return emptyList()
        val array = try {
            element.jsonArray
        } catch (e: Exception) {
            throw BackupException("Invalid keychain section in payload.")
        }
        if (array.size > MAX_KEYCHAIN_ENTRIES_IN_BACKUP) {
            throw BackupException("Backup contains too many keychain entries.")
        }
        val entries = array.mapIndexed { index, entryElement ->
            val where = "keychain entry #$index"
            val obj = try {
                entryElement.jsonObject
            } catch (e: Exception) {
                throw BackupException("Invalid $where.")
            }
            checkKeys(obj, keychainEntryKeys, keychainEntryKeys, where)
            val label = obj.reqString("label", where, MAX_SHORT_FIELD_CHARS)
            val labelIssues = KeychainValidator.validateLabel(label)
            if (labelIssues.isNotEmpty()) {
                throw BackupException("Invalid label in $where: ${labelIssues.first().message}")
            }
            val kind = try {
                AuthType.valueOf(obj.reqString("kind", where))
            } catch (e: Exception) {
                throw BackupException("Invalid keychain entry type in $where.")
            }
            val hasMaterial = obj.reqBoolean("hasMaterial", where)
            val materialElement = obj["material"]
            val material: ExportCredential? = when {
                materialElement == null || materialElement is JsonNull -> null
                else -> {
                    val materialObj = try {
                        materialElement.jsonObject
                    } catch (e: Exception) {
                        throw BackupException("Invalid keychain material in $where.")
                    }
                    checkKeys(materialObj, credentialKeys, credentialKeys, "$where material")
                    val materialKind = try {
                        AuthType.valueOf(materialObj.reqString("kind", "$where material"))
                    } catch (e: Exception) {
                        throw BackupException("Invalid keychain material type in $where.")
                    }
                    if (materialKind != kind) {
                        throw BackupException(
                            "Keychain material type does not match entry type in $where."
                        )
                    }
                    ExportCredential(
                        materialKind,
                        materialObj.reqString("value", "$where material")
                    )
                }
            }
            if (!encrypted && material != null) {
                throw BackupException(
                    "A non-encrypted backup must not contain keychain material."
                )
            }
            if (material != null) {
                val input = when (material.kind) {
                    AuthType.PASSWORD -> CredentialInput.Password(material.value.toCharArray())
                    AuthType.PRIVATE_KEY -> CredentialInput.PrivateKey(material.value, null)
                }
                val issues = HostValidator.validateCredential(kind, input, false)
                if (issues.isNotEmpty()) {
                    throw BackupException("Invalid keychain material in $where.")
                }
            }
            ExportKeychainEntry(
                label = label,
                kind = kind,
                material = material,
                hasMaterial = hasMaterial,
                publicKeyText = obj.optString("publicKeyText", where),
                fingerprint = obj.optString("fingerprint", where)
            )
        }
        if (entries.size != entries.distinctBy { it.label }.size) {
            throw BackupException("Backup keychain labels must be unique.")
        }
        return entries
    }

    private fun decodeHost(element: kotlinx.serialization.json.JsonElement, where: String, encrypted: Boolean): ExportHost {
        val obj = try {
            element.jsonObject
        } catch (e: Exception) {
            throw BackupException("Invalid $where.")
        }
        checkKeys(obj, hostKeys, hostRequiredKeys, where)
        val exportId = obj.reqInt("exportId", where)
        val name = obj.reqString("name", where, MAX_SHORT_FIELD_CHARS)
        val hostname = obj.reqString("hostname", where, MAX_SHORT_FIELD_CHARS)
        val port = obj.reqInt("port", where)
        val username = obj.reqString("username", where, MAX_SHORT_FIELD_CHARS)
        val authType = try {
            AuthType.valueOf(obj.reqString("authType", where))
        } catch (e: Exception) {
            throw BackupException("Invalid authentication type in $where.")
        }
        val notes = obj.reqString("notes", where)
        val tags = obj.reqString("tags", where, MAX_SHORT_FIELD_CHARS)
        val hasCredential = obj.reqBoolean("hasCredential", where)
        val profile = HostProfile(
            name = name, hostname = hostname, port = port,
            username = username, authType = authType, notes = notes, tags = tags
        )
        val profileIssues = HostValidator.validate(profile)
        if (profileIssues.isNotEmpty()) {
            throw BackupException("Invalid host in $where: ${profileIssues.first().message}")
        }
        val credentialElement = obj["credential"]
        val credential: ExportCredential? = when {
            credentialElement == null || credentialElement is JsonNull -> null
            else -> {
                val credObj = try {
                    credentialElement.jsonObject
                } catch (e: Exception) {
                    throw BackupException("Invalid credential in $where.")
                }
                checkKeys(credObj, credentialKeys, credentialKeys, "$where credential")
                val kind = try {
                    AuthType.valueOf(credObj.reqString("kind", "$where credential"))
                } catch (e: Exception) {
                    throw BackupException("Invalid credential type in $where.")
                }
                val value = credObj.reqString("value", "$where credential")
                if (kind != authType) {
                    throw BackupException("Credential type does not match host type in $where.")
                }
                ExportCredential(kind, value)
            }
        }
        if (!encrypted && credential != null) {
            throw BackupException(
                "A non-encrypted backup must not contain credentials."
            )
        }
        if (credential != null) {
            val input = when (credential.kind) {
                AuthType.PASSWORD -> CredentialInput.Password(credential.value.toCharArray())
                AuthType.PRIVATE_KEY -> CredentialInput.PrivateKey(credential.value, null)
            }
            val credIssues = HostValidator.validateCredential(authType, input, false)
            if (credIssues.isNotEmpty()) {
                throw BackupException("Invalid credential in $where.")
            }
        }
        val buttons = decodeButtons(obj, where)
        val settings = decodeSettings(obj, where)
        val envVars = decodeEnvVars(obj, where, encrypted)
        val tunnels = decodeTunnels(obj, where)
        val proxy = decodeProxy(obj, where)
        val hops = try {
            obj["jumpHopExportIds"]?.jsonArray?.map { element ->
                // Audit #7 AUD-002: quoted ids are not ids.
                val prim = element as? JsonPrimitive
                if (prim == null || prim.isString) {
                    throw BackupException("Invalid jump chain in $where.")
                }
                prim.int
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Invalid jump chain in $where.")
        } ?: throw BackupException("Missing jump chain in $where.")
        return ExportHost(
            exportId = exportId,
            name = name,
            hostname = hostname,
            port = port,
            username = username,
            authType = authType,
            notes = notes,
            tags = tags,
            credential = credential,
            hasCredential = hasCredential,
            buttons = buttons,
            settings = settings,
            envVars = envVars,
            tunnels = tunnels,
            proxy = proxy,
            jumpHopExportIds = hops,
            keychainLabel = obj.optString("keychainLabel", where),
            favorite = obj.optBoolean("favorite", where),
            groupName = obj.optString("groupName", where) ?: ""
        )
    }

    /**
     * Item T-02: the global snippets section reuses the button
     * shape and its full validation (including the secret scan
     * inside [CommandValidator]) via [decodeButtons]; only the
     * count cap differs.
     */
    private fun decodeSnippets(root: JsonObject): List<ExportButton> {
        val element = root["snippets"] ?: return emptyList()
        val array = try {
            element.jsonArray
        } catch (e: Exception) {
            null
        } ?: throw BackupException("Invalid snippets in payload.")
        if (array.size > MAX_SNIPPETS_IN_BACKUP) {
            throw BackupException("Backup contains too many snippets.")
        }
        val wrapper = buildJsonObject { put("buttons", array) }
        return decodeButtons(wrapper, "snippets", MAX_SNIPPETS_IN_BACKUP)
    }

    private fun decodeButtons(
        obj: JsonObject,
        where: String,
        maxCount: Int = MAX_BUTTONS_PER_HOST
    ): List<ExportButton> {
        val array = try {
            obj["buttons"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: throw BackupException("Invalid buttons in $where.")
        if (array.size > maxCount) {
            throw BackupException("Too many command buttons in $where.")
        }
        return array.mapIndexed { index, element ->
            val buttonObj = try {
                element.jsonObject
            } catch (e: Exception) {
                throw BackupException("Invalid button in $where.")
            }
            checkKeys(buttonObj, buttonKeys, buttonKeys, "$where button #$index")
            val mode = try {
                CommandMode.valueOf(buttonObj.reqString("mode", "$where button #$index"))
            } catch (e: Exception) {
                throw BackupException("Invalid button mode in $where.")
            }
            val variables = try {
                buttonObj["variables"]?.jsonArray?.map { variableElement ->
                    val variableObj = variableElement.jsonObject
                    checkKeys(
                        variableObj, variableKeys, variableKeys,
                        "$where button #$index variable"
                    )
                    ExportVariable(
                        name = variableObj.reqString(
                            "name", "$where button #$index variable", MAX_SHORT_FIELD_CHARS
                        ),
                        defaultValue = variableObj.reqString(
                            "defaultValue", "$where button #$index variable"
                        ),
                        required = variableObj.reqBoolean(
                            "required", "$where button #$index variable"
                        )
                    )
                }
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException("Invalid button variables in $where.")
            } ?: throw BackupException("Invalid button variables in $where.")
            val steps = try {
                buttonObj["steps"]?.jsonArray?.map { element ->
                    // Audit #7 AUD-002b: steps are strings; a
                    // null/number/object step is a wrong type,
                    // not the literal text "null".
                    val prim = element as? JsonPrimitive
                    if (prim == null || !prim.isString) {
                        throw BackupException("Invalid button steps in $where.")
                    }
                    if (prim.content.length > MAX_FIELD_CHARS) {
                        throw BackupException("Invalid button steps in $where.")
                    }
                    prim.content
                }
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException("Invalid button steps in $where.")
            } ?: throw BackupException("Invalid button steps in $where.")
            val button = ExportButton(
                label = buttonObj.reqString("label", "$where button #$index", MAX_SHORT_FIELD_CHARS),
                category = buttonObj.reqString(
                    "category", "$where button #$index", MAX_SHORT_FIELD_CHARS
                ),
                sortOrder = buttonObj.reqInt("sortOrder", "$where button #$index"),
                mode = mode,
                template = buttonObj.reqString("template", "$where button #$index"),
                steps = steps,
                variables = variables
            )
            val issues = CommandValidator.validate(
                CommandButton(
                    hostId = 0L, label = button.label, category = button.category,
                    sortOrder = button.sortOrder, mode = button.mode,
                    template = button.template, steps = button.steps,
                    variables = button.variables.map {
                        CommandVariable(it.name, it.defaultValue, it.required)
                    }
                )
            )
            if (issues.isNotEmpty()) {
                throw BackupException(
                    "Invalid button in $where: ${issues.first().message}"
                )
            }
            button
        }
    }

    private fun decodeSettings(obj: JsonObject, where: String): ExportSettings? {
        val element = obj["settings"] ?: return null
        if (element is JsonNull) return null
        val settingsObj = try {
            element.jsonObject
        } catch (e: Exception) {
            throw BackupException("Invalid settings in $where.")
        }
        checkKeys(settingsObj, settingsKeys, settingsKeys, "$where settings")
        val directory = settingsObj.reqString("remoteWorkingDirectory", "$where settings")
        val shell = try {
            RemoteShell.valueOf(settingsObj.reqString("remoteShell", "$where settings"))
        } catch (e: Exception) {
            throw BackupException("Invalid remote shell in $where.")
        }
        val issue = EnvironmentValidator.validateRemoteDirectory(directory, shell)
        if (issue != null) {
            throw BackupException("Invalid working directory in $where.")
        }
        return ExportSettings(directory, shell)
    }

    private fun decodeEnvVars(obj: JsonObject, where: String, encrypted: Boolean): List<ExportEnvVar> {
        val array = try {
            obj["envVars"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: throw BackupException("Invalid environment variables in $where.")
        if (array.size > MAX_ENV_VARS_PER_HOST) {
            throw BackupException("A host in $where has too many environment variables.")
        }
        if (!encrypted && array.isNotEmpty()) {
            throw BackupException(
                "A non-encrypted backup must not contain environment values."
            )
        }
        return array.mapIndexed { index, element ->
            val varObj = try {
                element.jsonObject
            } catch (e: Exception) {
                throw BackupException("Invalid environment variable in $where.")
            }
            checkKeys(varObj, envVarKeys, envVarKeys, "$where env var #$index")
            val name = varObj.reqString("name", "$where env var #$index", MAX_SHORT_FIELD_CHARS)
            val value = varObj.reqString("value", "$where env var #$index")
            val nameIssue = EnvironmentValidator.validateEnvName(name)
            val valueIssue = EnvironmentValidator.validateEnvValue(value)
            if (nameIssue != null || valueIssue != null) {
                throw BackupException("Invalid environment variable in $where.")
            }
            ExportEnvVar(name, value)
        }
    }

    private fun decodeTunnels(obj: JsonObject, where: String): List<ExportTunnel> {
        val array = try {
            obj["tunnels"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: throw BackupException("Invalid tunnels in $where.")
        if (array.size > MAX_TUNNELS_PER_HOST) {
            throw BackupException("A host in $where has too many tunnels.")
        }
        return array.mapIndexed { index, element ->
            val tunnelObj = try {
                element.jsonObject
            } catch (e: Exception) {
                throw BackupException("Invalid tunnel in $where.")
            }
            checkKeys(tunnelObj, tunnelKeys, tunnelKeys, "$where tunnel #$index")
            val kind = try {
                TunnelKind.valueOf(tunnelObj.reqString("kind", "$where tunnel #$index"))
            } catch (e: Exception) {
                throw BackupException("Invalid tunnel kind in $where.")
            }
            val tunnel = ExportTunnel(
                kind = kind,
                bindAddress = tunnelObj.reqString(
                    "bindAddress", "$where tunnel #$index", MAX_SHORT_FIELD_CHARS
                ),
                bindPort = tunnelObj.reqInt("bindPort", "$where tunnel #$index"),
                targetHost = tunnelObj.reqString(
                    "targetHost", "$where tunnel #$index", MAX_SHORT_FIELD_CHARS
                ),
                targetPort = tunnelObj.reqInt("targetPort", "$where tunnel #$index"),
                allowNonLoopback = tunnelObj.reqBoolean(
                    "allowNonLoopback", "$where tunnel #$index"
                ),
                enabled = tunnelObj.reqBoolean("enabled", "$where tunnel #$index"),
                label = tunnelObj.reqString(
                    "label", "$where tunnel #$index", MAX_SHORT_FIELD_CHARS
                )
            )
            val issues = TunnelValidator.validate(
                TunnelConfig(
                    hostId = 0L, kind = tunnel.kind, bindAddress = tunnel.bindAddress,
                    bindPort = tunnel.bindPort, targetHost = tunnel.targetHost,
                    targetPort = tunnel.targetPort,
                    allowNonLoopback = tunnel.allowNonLoopback,
                    enabled = tunnel.enabled, label = tunnel.label
                )
            )
            if (issues.isNotEmpty()) {
                throw BackupException("Invalid tunnel in $where.")
            }
            tunnel
        }
    }

    private fun decodeProxy(obj: JsonObject, where: String): ExportProxy? {
        val element = obj["proxy"] ?: return null
        if (element is JsonNull) return null
        val proxyObj = try {
            element.jsonObject
        } catch (e: Exception) {
            throw BackupException("Invalid proxy in $where.")
        }
        checkKeys(proxyObj, proxyKeys, proxyKeys, "$where proxy")
        val kind = try {
            ProxyKind.valueOf(proxyObj.reqString("kind", "$where proxy"))
        } catch (e: Exception) {
            throw BackupException("Invalid proxy kind in $where.")
        }
        if (kind == ProxyKind.NONE) {
            throw BackupException("A backup proxy must not be NONE.")
        }
        val proxy = ExportProxy(
            kind = kind,
            proxyHost = proxyObj.reqString("proxyHost", "$where proxy", MAX_SHORT_FIELD_CHARS),
            proxyPort = proxyObj.reqInt("proxyPort", "$where proxy")
        )
        val issues = ProxySpecParser.validate(
            com.pishi.termbridge.domain.ProxyConfig(
                hostId = 0L, kind = proxy.kind,
                proxyHost = proxy.proxyHost, proxyPort = proxy.proxyPort
            )
        )
        if (issues.isNotEmpty()) {
            throw BackupException("Invalid proxy in $where.")
        }
        return proxy
    }

    // ---- typed field readers (wrong type -> BackupException, never a raw parser error) ----

    private fun checkKeys(obj: JsonObject, allowed: Set<String>, required: Set<String>, where: String) {
        val unknown = obj.keys - allowed
        if (unknown.isNotEmpty()) {
            throw BackupException("Unknown field in $where: ${unknown.first()}.")
        }
        val missing = required - obj.keys
        if (missing.isNotEmpty()) {
            throw BackupException("Missing field in $where: ${missing.first()}.")
        }
    }

    /** Optional nullable string: absent and JSON null both map
     *  to null (fields added after v1 files exist); any other
     *  non-string type is a hard failure like everywhere else.
     *  Audit #7 AUD-002/003: the primitive must really be a
     *  string (a bare number's .content used to pass), and the
     *  value is length-capped like every decoded string. */
    private fun JsonObject.optString(key: String, where: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val prim = element as? JsonPrimitive
        if (prim == null || !prim.isString) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
        if (prim.content.length > MAX_FIELD_CHARS) {
            throw BackupException("Field $key in $where is too long.")
        }
        return prim.content
    }

    /** Optional boolean with the same contract as [optString]:
     *  absent or JSON null maps to false (fields added after v1
     *  files exist); any non-boolean type — including a quoted
     *  "true" — is a hard failure. */
    private fun JsonObject.optBoolean(key: String, where: String): Boolean {
        val element = this[key] ?: return false
        if (element is JsonNull) return false
        val prim = element as? JsonPrimitive
        if (prim == null || prim.isString) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
        return try {
            prim.content.toBooleanStrict()
        } catch (e: Exception) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
    }

    /** Required string. Audit #7 AUD-002b: JSON null is a wrong
     *  type, never the literal text "null" (JsonNull.content).
     *  Audit #7 AUD-003: [maxLength] caps the decoded value —
     *  short single-line fields pass MAX_SHORT_FIELD_CHARS. */
    private fun JsonObject.reqString(
        key: String,
        where: String,
        maxLength: Int = MAX_FIELD_CHARS
    ): String {
        val element = this[key]
            ?: throw BackupException("Missing field in $where: $key.")
        val prim = element as? JsonPrimitive
        if (prim == null || element is JsonNull || !prim.isString) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
        if (prim.content.length > maxLength) {
            throw BackupException("Field $key in $where is too long.")
        }
        return prim.content
    }

    /** Required int. Audit #7 AUD-002: a quoted "22" is a
     *  string, not an int — JsonPrimitive.int parses string
     *  content, so the primitive's kind is checked first. */
    private fun JsonObject.reqInt(key: String, where: String): Int {
        val element = this[key]
            ?: throw BackupException("Missing field in $where: $key.")
        val prim = element as? JsonPrimitive
        if (prim == null || element is JsonNull || prim.isString) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
        return try {
            prim.int
        } catch (e: Exception) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
    }

    private fun JsonObject.reqBoolean(key: String, where: String): Boolean {
        val element = this[key]
            ?: throw BackupException("Missing field in $where: $key.")
        val prim = element as? JsonPrimitive
        if (prim == null || element is JsonNull || prim.isString) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
        return try {
            prim.content.toBooleanStrict()
        } catch (e: Exception) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
    }
}
