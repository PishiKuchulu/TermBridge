package com.pishi.termbridge.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * JSON import/export for custom terminal colour schemes
 * (item T-08). One object per scheme:
 *
 *     {"name": "My scheme", "background": "#101418",
 *      "foreground": "#E6E6E6", "cursor": "#FFCC00",
 *      "ansi": ["#000000", … exactly 16 …]}
 *
 * Colours are `#RRGGBB` (alpha forced opaque) or `#AARRGGBB`.
 * Decoding is strict and total: any defect (bad hex, wrong
 * ANSI count, blank or over-long name, unknown fields are
 * ignored) yields null rather than a half-valid scheme.
 * The scheme id is derived from the name + colours and is
 * stable for identical content; once stored, an entry keeps
 * its id even if the display name is edited later.
 */
object CustomThemeCodec {
    const val MAX_NAME_LENGTH = 40
    private val json = Json { ignoreUnknownKeys = true }

    fun parseColor(text: String): Long? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) 0xFF000000L or value else value
    }

    fun formatColor(argb: Long): String {
        val a = (argb shr 24) and 0xFF
        return if (a == 0xFFL) {
            "#%06X".format(argb and 0xFFFFFF)
        } else {
            "#%08X".format(argb)
        }
    }

    fun idFor(name: String, background: Long, ansi: List<Long>): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(24)
            .ifBlank { "scheme" }
        var hash = background
        for (color in ansi) hash = hash * 31 + color
        return "custom-$slug-${(hash and 0xFFFF).toString(16).padStart(4, '0')}"
    }

    fun encode(theme: TerminalTheme): String {
        val obj = buildJsonObject {
            if (theme.id.startsWith("custom-")) put("id", theme.id)
            put("name", theme.label)
            put("background", formatColor(theme.background))
            put("foreground", formatColor(theme.foreground))
            put("cursor", formatColor(theme.cursor))
            put("ansi", buildJsonArray { for (c in theme.ansi) add(JsonPrimitive(formatColor(c))) })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    fun decode(text: String): TerminalTheme? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        return decodeObject(root)
    }

    fun decodeObject(root: JsonObject): TerminalTheme? {
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
            ?: return null
        val background = (root["background"] as? JsonPrimitive)?.contentOrNull
            ?.let { parseColor(it) } ?: return null
        val foreground = (root["foreground"] as? JsonPrimitive)?.contentOrNull
            ?.let { parseColor(it) } ?: return null
        val cursor = (root["cursor"] as? JsonPrimitive)?.contentOrNull
            ?.let { parseColor(it) } ?: return null
        val ansiArray = (root["ansi"] as? JsonArray) ?: return null
        if (ansiArray.size != 16) return null
        val ansi = ansiArray.map { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.let { parseColor(it) } ?: return null
        }
        // A stored id is honoured only in the custom namespace,
        // so an imported file can never shadow a built-in id.
        val storedId = (root["id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf {
                it.startsWith("custom-") && it.length <= 48 &&
                    it.all { c -> c.isLetterOrDigit() || c == '-' }
            }
        return TerminalTheme(
            id = storedId ?: idFor(name, background, ansi),
            label = name,
            background = background,
            foreground = foreground,
            cursor = cursor,
            ansi = ansi
        )
    }

    fun encodeAll(themes: List<TerminalTheme>): String {
        val array = buildJsonArray {
            for (theme in themes) add(json.parseToJsonElement(encode(theme)))
        }
        return array.toString()
    }

    /** Storage decode: a damaged entry is dropped, not fatal. */
    fun decodeAll(text: String): List<TerminalTheme> {
        val array = try {
            json.parseToJsonElement(text).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let { decodeObject(it) }
        }
    }
}
