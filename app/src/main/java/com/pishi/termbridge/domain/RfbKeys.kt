package com.pishi.termbridge.domain

/**
 * X11 keysyms for RFB key events. Printable ASCII/Latin-1 characters
 * use their own code point as the keysym; any other Unicode character
 * (for example Persian letters) uses the `0x01000000 + codepoint`
 * form that X servers accept as a Unicode keysym.
 */
object RfbKeys {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val ENTER = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val DELETE = 0xFFFF
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9
    const val SUPER_L = 0xFFEB

    fun keysymForCodePoint(codePoint: Int): Int = when {
        codePoint == '\n'.code || codePoint == '\r'.code -> ENTER
        codePoint == '\t'.code -> TAB
        codePoint == 0x08 -> BACKSPACE
        codePoint < 0x20 || codePoint == 0x7F -> 0 // other controls are not sent
        codePoint in 0x20..0x7E || codePoint in 0xA0..0xFF -> codePoint
        else -> 0x01000000 + codePoint
    }
}
