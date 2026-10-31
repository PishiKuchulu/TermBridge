package com.pishi.termbridge

import com.pishi.termbridge.domain.RfbKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class RfbKeysTest {
    @Test
    fun asciiUsesItsOwnCodePoint() {
        assertEquals('a'.code, RfbKeys.keysymForCodePoint('a'.code))
        assertEquals('~'.code, RfbKeys.keysymForCodePoint('~'.code))
        assertEquals(0xE9, RfbKeys.keysymForCodePoint(0xE9))
    }

    @Test
    fun specialCharactersMapToNamedKeys() {
        assertEquals(RfbKeys.ENTER, RfbKeys.keysymForCodePoint('\n'.code))
        assertEquals(RfbKeys.TAB, RfbKeys.keysymForCodePoint('\t'.code))
        assertEquals(RfbKeys.BACKSPACE, RfbKeys.keysymForCodePoint(8))
    }

    @Test
    fun otherUnicodeUsesTheUnicodeKeysymForm() {
        // Persian letter BEH (U+0628).
        assertEquals(0x01000628, RfbKeys.keysymForCodePoint(0x0628))
    }

    @Test
    fun strayControlCharactersAreNeverSent() {
        assertEquals(0, RfbKeys.keysymForCodePoint(0x03))
        assertEquals(0, RfbKeys.keysymForCodePoint(0x1B))
        assertEquals(0, RfbKeys.keysymForCodePoint(0x7F))
    }
}
