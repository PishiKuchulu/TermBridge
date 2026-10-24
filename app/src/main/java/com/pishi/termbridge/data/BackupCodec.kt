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
import com.pishi.termbridge.domain.ExportProxy
import com.pishi.termbridge.domain.ExportSettings
import com.pishi.termbridge.domain.ExportTunnel
import com.pishi.termbridge.domain.ExportVariable
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import com.pishi.termbridge.domain.JumpChainValidator
import com.pishi.termbridge.domain.MAX_BUTTONS_PER_HOST
import com.pishi.termbridge.domain.MAX_ENV_VARS_PER_HOST
import com.pishi.termbridge.domain.MAX_HOSTS_IN_BACKUP
import com.pishi.termbridge.domain.MAX_TUNNELS_PER_HOST
import com.pishi.termbridge.domain.MIN_EXPORT_PASSPHRASE_LENGTH
import com.pishi.termbridge.domain.MAX_BACKUP_BYTES
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
 *   "jumpHopExportIds":[1,2] }]}
 *
 * Fail-closed rules, all enforced here (never in the UI):
 * - Wrong format string, wrong version, malformed JSON, wrong
 *   types, missing required fields, or ANY unknown field at any
 *   level -> whole file rejected.
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
        "settings", "envVars", "tunnels", "proxy", "jumpHopExportIds"
    )
    private val credentialKeys = setOf("kind", "value")
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
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BackupException("Backup file is not valid JSON.")
        }
        if (root["format"]?.jsonPrimitive?.contentOrNull != BACKUP_FORMAT) {
            throw BackupException("Not a TermBridge backup file.")
        }
        val version = try {
            root["version"]?.jsonPrimitive?.int
        } catch (e: Exception) {
            null
        }
        if (version != BACKUP_VERSION) {
            throw BackupException("Unsupported backup version.")
        }
        val encrypted = root["encrypted"]?.let { element ->
            try {
                element.jsonPrimitive.content.toBooleanStrict()
            } catch (e: Exception) {
                throw BackupException("Backup file has an invalid encrypted flag.")
            }
        } ?: throw BackupException("Backup file has an invalid encrypted flag.")
        val payloadText: String
        if (encrypted) {
            checkKeys(root, envelopeEncryptedKeys, envelopeEncryptedKeys, "envelope")
            if (root["kdf"]?.jsonPrimitive?.contentOrNull != ExportCrypto.KDF_ALGORITHM ||
                root["iterations"]?.jsonPrimitive?.int != ExportCrypto.ITERATIONS
            ) {
                throw BackupException("Unsupported backup encryption parameters.")
            }
            if (passphrase == null || passphrase.size < MIN_EXPORT_PASSPHRASE_LENGTH) {
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
            hasCredentials = document.hosts.any { it.credential != null },
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
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BackupException("Backup payload is not valid JSON.")
        }
        checkKeys(root, setOf("hosts"), setOf("hosts"), "payload")
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
        return BackupDocument(resolved) to warnings
    }

    private fun decodeHost(element: kotlinx.serialization.json.JsonElement, where: String, encrypted: Boolean): ExportHost {
        val obj = try {
            element.jsonObject
        } catch (e: Exception) {
            throw BackupException("Invalid $where.")
        }
        checkKeys(obj, hostKeys, hostKeys, where)
        val exportId = obj.reqInt("exportId", where)
        val name = obj.reqString("name", where)
        val hostname = obj.reqString("hostname", where)
        val port = obj.reqInt("port", where)
        val username = obj.reqString("username", where)
        val authType = try {
            AuthType.valueOf(obj.reqString("authType", where))
        } catch (e: Exception) {
            throw BackupException("Invalid authentication type in $where.")
        }
        val notes = obj.reqString("notes", where)
        val tags = obj.reqString("tags", where)
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
            obj["jumpHopExportIds"]?.jsonArray?.map { it.jsonPrimitive.int }
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
            jumpHopExportIds = hops
        )
    }

    private fun decodeButtons(obj: JsonObject, where: String): List<ExportButton> {
        val array = try {
            obj["buttons"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: throw BackupException("Invalid buttons in $where.")
        if (array.size > MAX_BUTTONS_PER_HOST) {
            throw BackupException("A host in $where has too many command buttons.")
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
                        name = variableObj.reqString("name", "$where button #$index variable"),
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
                buttonObj["steps"]?.jsonArray?.map { it.jsonPrimitive.content }
            } catch (e: Exception) {
                throw BackupException("Invalid button steps in $where.")
            } ?: throw BackupException("Invalid button steps in $where.")
            val button = ExportButton(
                label = buttonObj.reqString("label", "$where button #$index"),
                category = buttonObj.reqString("category", "$where button #$index"),
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
            val name = varObj.reqString("name", "$where env var #$index")
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
                bindAddress = tunnelObj.reqString("bindAddress", "$where tunnel #$index"),
                bindPort = tunnelObj.reqInt("bindPort", "$where tunnel #$index"),
                targetHost = tunnelObj.reqString("targetHost", "$where tunnel #$index"),
                targetPort = tunnelObj.reqInt("targetPort", "$where tunnel #$index"),
                allowNonLoopback = tunnelObj.reqBoolean(
                    "allowNonLoopback", "$where tunnel #$index"
                ),
                enabled = tunnelObj.reqBoolean("enabled", "$where tunnel #$index"),
                label = tunnelObj.reqString("label", "$where tunnel #$index")
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
            proxyHost = proxyObj.reqString("proxyHost", "$where proxy"),
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

    private fun JsonObject.reqString(key: String, where: String): String {
        val element = this[key]
            ?: throw BackupException("Missing field in $where: $key.")
        return try {
            element.jsonPrimitive.content
        } catch (e: Exception) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
    }

    private fun JsonObject.reqInt(key: String, where: String): Int {
        val element = this[key]
            ?: throw BackupException("Missing field in $where: $key.")
        return try {
            element.jsonPrimitive.int
        } catch (e: Exception) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
    }

    private fun JsonObject.reqBoolean(key: String, where: String): Boolean {
        val element = this[key]
            ?: throw BackupException("Missing field in $where: $key.")
        return try {
            element.jsonPrimitive.content.toBooleanStrict()
        } catch (e: Exception) {
            throw BackupException("Field $key in $where has the wrong type.")
        }
    }
}
