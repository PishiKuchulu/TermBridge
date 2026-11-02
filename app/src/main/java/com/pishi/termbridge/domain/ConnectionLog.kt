package com.pishi.termbridge.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class ConnectionOutcome { CONNECTED, FAILED, CLOSED }

/**
 * One line of connection history. Holds only the host's display name,
 * the outcome and timing — never terminal output, credentials or
 * server-supplied text.
 */
data class ConnectionLogEntry(
    val timestampMs: Long,
    val hostName: String,
    val outcome: ConnectionOutcome,
    /** Session length for CLOSED entries, else 0. */
    val durationMs: Long = 0,
    /** Fixed, secret-free user message for FAILED entries. */
    val detail: String = ""
)

object ConnectionLogCodec {
    const val MAX_ENTRIES = 200

    fun encode(entries: List<ConnectionLogEntry>): String =
        JsonArray(
            entries.take(MAX_ENTRIES).map {
                JsonObject(
                    mapOf(
                        "t" to JsonPrimitive(it.timestampMs),
                        "h" to JsonPrimitive(it.hostName),
                        "o" to JsonPrimitive(it.outcome.name),
                        "d" to JsonPrimitive(it.durationMs),
                        "m" to JsonPrimitive(it.detail)
                    )
                )
            }
        ).toString()

    /** Tolerant: a damaged file yields an empty list, never a crash. */
    fun decode(text: String?): List<ConnectionLogEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                val outcome = ConnectionOutcome.values().firstOrNull {
                    it.name == o["o"]?.jsonPrimitive?.contentOrNull
                } ?: return@mapNotNull null
                ConnectionLogEntry(
                    timestampMs = o["t"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    hostName = o["h"]?.jsonPrimitive?.contentOrNull ?: "",
                    outcome = outcome,
                    durationMs = o["d"]?.jsonPrimitive?.longOrNull ?: 0L,
                    detail = o["m"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }.take(MAX_ENTRIES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Newest first, capped. */
    fun prepend(existing: List<ConnectionLogEntry>, entry: ConnectionLogEntry): List<ConnectionLogEntry> =
        (listOf(entry) + existing).take(MAX_ENTRIES)
}
