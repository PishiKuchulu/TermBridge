package com.pishi.termbridge.settings

import android.content.Context
import com.pishi.termbridge.domain.CommandAdmission
import com.pishi.termbridge.domain.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Item T-11: the on-device store of remembered commands for
 * autocomplete. SharedPreferences, hand-built JSON (the app
 * has no serialization plugin), capped at [MAX_ENTRIES] with
 * least-recently-used eviction. Every entry passed
 * [CommandAdmission] before it arrived here — the store
 * re-checks on record anyway, so no caller can sneak a
 * flagged line in. Nothing here leaves the device and the
 * store is NOT part of the backup format.
 */
class CommandHistoryStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("termbridge_cmd_history", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    /** Records one command the user ran (admission inside). */
    fun record(text: String, nowMs: Long = System.currentTimeMillis()) {
        val admitted = CommandAdmission.admit(text) ?: return
        mutate { list ->
            val existing = list.firstOrNull { it.text == admitted }
            val updated = HistoryEntry(
                text = admitted,
                uses = (existing?.uses ?: 0) + 1,
                lastUsedMs = nowMs
            )
            cap(list.filter { it.text != admitted } + updated)
        }
    }

    /** Merges imported history lines; returns how many were new. */
    fun recordImported(lines: List<String>, nowMs: Long = System.currentTimeMillis()): Int {
        var added = 0
        mutate { list ->
            val byText = list.associateBy { it.text }.toMutableMap()
            for (line in lines) {
                val admitted = CommandAdmission.admit(line) ?: continue
                val existing = byText[admitted]
                if (existing == null) added++
                byText[admitted] = HistoryEntry(
                    text = admitted,
                    uses = existing?.uses ?: 1,
                    lastUsedMs = maxOf(existing?.lastUsedMs ?: 0L, nowMs)
                )
            }
            cap(byText.values.toList())
        }
        return added
    }

    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun cap(list: List<HistoryEntry>): List<HistoryEntry> =
        if (list.size <= MAX_ENTRIES) {
            list
        } else {
            list.sortedByDescending { it.lastUsedMs }.take(MAX_ENTRIES)
        }

    private inline fun mutate(transform: (List<HistoryEntry>) -> List<HistoryEntry>) {
        val next = transform(_entries.value)
        _entries.value = next
        persist(next)
    }

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val text = obj["t"]?.jsonPrimitive?.content ?: return@mapNotNull null
                HistoryEntry(
                    text = text,
                    uses = obj["u"]?.jsonPrimitive?.int ?: 1,
                    lastUsedMs = obj["l"]?.jsonPrimitive?.long ?: 0L
                )
            }
        } catch (_: Exception) {
            // A damaged store degrades to empty, never a crash.
            emptyList()
        }
    }

    private fun persist(entries: List<HistoryEntry>) {
        val json = buildJsonArray {
            for (entry in entries) {
                add(buildJsonObject {
                    put("t", JsonPrimitive(entry.text))
                    put("u", JsonPrimitive(entry.uses))
                    put("l", JsonPrimitive(entry.lastUsedMs))
                })
            }
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "entries_json"
        const val MAX_ENTRIES = 500
    }
}
