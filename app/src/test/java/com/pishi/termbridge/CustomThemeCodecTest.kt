package com.pishi.termbridge

import com.pishi.termbridge.domain.CustomThemeCodec
import com.pishi.termbridge.domain.TerminalTheme
import com.pishi.termbridge.domain.TerminalThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-08: custom colour scheme JSON codec. */
class CustomThemeCodecTest {

    private val sample = TerminalTheme(
        id = "custom-mine-1234",
        label = "Mine",
        background = 0xFF101418,
        foreground = 0xFFE6E6E6,
        cursor = 0xFFFFCC00,
        ansi = TerminalThemes.CLASSIC.ansi
    )

    @Test
    fun roundTripPreservesEverythingIncludingId() {
        val decoded = CustomThemeCodec.decode(CustomThemeCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun importWithoutIdDerivesACustomId() {
        val text = """
            {"name":"Shared","background":"#101418","foreground":"#E6E6E6",
             "cursor":"#FFCC00","ansi":["#000000","#CC0000","#00AA00","#CCAA00",
             "#3465A4","#CC00CC","#00AAAA","#E6E6E6","#7F7F7F","#FF5555",
             "#55FF55","#FFFF55","#6699FF","#FF55FF","#55FFFF","#FFFFFF"]}
        """.trimIndent()
        val decoded = CustomThemeCodec.decode(text)
        assertTrue(decoded != null)
        assertTrue(decoded!!.id.startsWith("custom-shared-"))
        assertEquals("Shared", decoded.label)
        assertEquals(0xFF101418, decoded.background)
    }

    @Test
    fun rejectsBadHexWrongAnsiCountAndBlankName() {
        val good = CustomThemeCodec.encode(sample)
        assertNull(CustomThemeCodec.decode(good.replace("#101418", "#10ZZ18")))
        assertNull(CustomThemeCodec.decode(good.replace("\"#FFFFFF\"", "\"#FFF\"")))
        val shortAnsi = """
            {"name":"X","background":"#101418","foreground":"#E6E6E6",
             "cursor":"#FFCC00","ansi":["#000000"]}
        """.trimIndent()
        assertNull(CustomThemeCodec.decode(shortAnsi))
        assertNull(CustomThemeCodec.decode(good.replace("\"Mine\"", "\"  \"")))
        assertNull(CustomThemeCodec.decode("not json"))
    }

    @Test
    fun forgedBuiltinIdIsNotHonoured() {
        val text = CustomThemeCodec.encode(sample).replace("custom-mine-1234", "classic")
        val decoded = CustomThemeCodec.decode(text)
        assertTrue(decoded != null)
        assertTrue(decoded!!.id.startsWith("custom-"))
    }

    @Test
    fun storageRoundTripKeepsListAndDropsDamage() {
        val encoded = CustomThemeCodec.encodeAll(listOf(sample, TerminalThemes.DRACULA))
        val decoded = CustomThemeCodec.decodeAll(encoded)
        // Dracula has no custom id: it is stored with a derived
        // custom id only if it went through decode; built-ins
        // should never be in the custom store, and decodeAll of
        // a built-in object yields a custom-namespaced copy.
        assertEquals(2, decoded.size)
        assertEquals(sample, decoded[0])
        assertEquals(emptyList<TerminalTheme>(), CustomThemeCodec.decodeAll("[broken"))
    }

    @Test
    fun colorParseAndFormat() {
        assertEquals(0xFF112233, CustomThemeCodec.parseColor("#112233"))
        assertEquals(0x80112233, CustomThemeCodec.parseColor("80112233"))
        assertNull(CustomThemeCodec.parseColor("#12345"))
        assertEquals("#112233", CustomThemeCodec.formatColor(0xFF112233))
        assertEquals("#80112233", CustomThemeCodec.formatColor(0x80112233))
    }
}
