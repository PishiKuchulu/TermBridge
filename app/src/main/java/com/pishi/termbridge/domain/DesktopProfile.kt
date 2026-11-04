package com.pishi.termbridge.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Item F-03b: a saved remote-desktop (VNC) endpoint.
 *
 * Deliberately endpoint-only: the VNC password is NEVER part
 * of a profile — it is typed per connection, exactly like an
 * SSH key passphrase. A profile may link a saved SSH host
 * ([sshHostId]) for the connect-through-SSH flow; that
 * host's own credential stays in its Keystore-encrypted
 * store, referenced by id only.
 */
data class DesktopProfile(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int = 5900,
    val sshHostId: Long? = null
)

/**
 * Manual-JSON codec for profile lists (the app has no
 * serialization compiler plugin — all JSON is hand-built,
 * as in BackupCodec). Damaged entries are dropped, never
 * fatal, matching the custom-theme store's rule.
 */
object DesktopProfileCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(profiles: List<DesktopProfile>): String {
        val array = buildJsonArray {
            for (profile in profiles) {
                add(
                    buildJsonObject {
                        put("id", profile.id)
                        put("name", profile.name)
                        put("host", profile.host)
                        put("port", profile.port)
                        profile.sshHostId?.let { put("sshHostId", it) }
                    }
                )
            }
        }
        return json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(), array
        )
    }

    fun decode(text: String?): List<DesktopProfile> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            json.parseToJsonElement(text).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<DesktopProfile>()
        for (element in root) {
            val obj = try {
                element.jsonObject
            } catch (_: Exception) {
                continue
            }
            val profile = decodeOne(obj) ?: continue
            out += profile
        }
        return out
    }

    private fun decodeOne(obj: JsonObject): DesktopProfile? {
        val id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val host = obj["host"]?.jsonPrimitive?.content ?: return null
        val port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5900
        val sshHostId = obj["sshHostId"]?.jsonPrimitive?.content?.toLongOrNull()
        if (name.isBlank() || host.isBlank() || port !in 1..65535) return null
        return DesktopProfile(id, name, host, port, sshHostId)
    }
}
