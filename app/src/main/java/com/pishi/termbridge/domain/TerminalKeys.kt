package com.pishi.termbridge.domain

/**
 * Key encoding for the terminal extra-keys bar (item T-09):
 * the pure key → byte mapping, kept in domain/ so it is fully
 * unit-tested without a session. The bar and the ViewModel
 * never build escape sequences themselves.
 *
 * Conventions (xterm, matching what TerminalSession already
 * sends for the keys it has):
 * - Plain special keys use their classic sequences (arrows are
 *   CSI, as the existing sendArrow* helpers send — this app
 *   does not track DECCKM, so no SS3 cursor variants exist
 *   anywhere, deliberately consistently).
 * - A modified key uses the xterm modifier parameter:
 *   1 + (Shift=1, Alt=2, Ctrl=4) — e.g. Ctrl = 5, Alt = 3,
 *   Ctrl+Alt = 7 — as `CSI 1 ; <mod> <final>` for cursor-style
 *   keys and `CSI <code> ; <mod> ~` for tilde keys. F1–F4 have
 *   no tilde form; plain they are SS3 (ESC O P/Q/R/S) and with
 *   any modifier they use `CSI 1 ; <mod> P/Q/R/S`.
 * - Alt on a plain character or key prefixes ESC (the
 *   long-standing meta convention), except where the modifier
 *   parameter form exists above.
 */
enum class SpecialKey {
    ESCAPE, TAB, BACKTAB, ENTER, BACKSPACE,
    ARROW_UP, ARROW_DOWN, ARROW_RIGHT, ARROW_LEFT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}

/** Modifier flags for one key press. */
data class KeyModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false
) {
    /** The xterm modifier parameter, or 0 when unmodified. */
    val xtermParam: Int
        get() {
            var bits = 0
            if (shift) bits += 1
            if (alt) bits += 2
            if (ctrl) bits += 4
            return if (bits == 0) 0 else bits + 1
        }
}

object TerminalKeyEncoder {
    private const val ESC: Byte = 0x1B

    fun encode(key: SpecialKey, modifiers: KeyModifiers = KeyModifiers()): ByteArray {
        val mod = modifiers.xtermParam
        return when (key) {
            SpecialKey.ESCAPE -> withAlt(byteArrayOf(ESC), modifiers)
            SpecialKey.TAB ->
                if (modifiers.shift) csi("Z") else withAlt(byteArrayOf(0x09), modifiers)
            SpecialKey.BACKTAB -> csi("Z")
            SpecialKey.ENTER -> withAlt(byteArrayOf('\r'.code.toByte()), modifiers)
            SpecialKey.BACKSPACE ->
                withAlt(byteArrayOf(if (modifiers.ctrl) 0x08 else 0x7F), modifiers)
            SpecialKey.ARROW_UP -> cursorKey("A", mod)
            SpecialKey.ARROW_DOWN -> cursorKey("B", mod)
            SpecialKey.ARROW_RIGHT -> cursorKey("C", mod)
            SpecialKey.ARROW_LEFT -> cursorKey("D", mod)
            SpecialKey.HOME -> cursorKey("H", mod)
            SpecialKey.END -> cursorKey("F", mod)
            SpecialKey.INSERT -> tildeKey(2, mod)
            SpecialKey.DELETE -> tildeKey(3, mod)
            SpecialKey.PAGE_UP -> tildeKey(5, mod)
            SpecialKey.PAGE_DOWN -> tildeKey(6, mod)
            SpecialKey.F1 -> functionKey("P", mod)
            SpecialKey.F2 -> functionKey("Q", mod)
            SpecialKey.F3 -> functionKey("R", mod)
            SpecialKey.F4 -> functionKey("S", mod)
            SpecialKey.F5 -> tildeKey(15, mod)
            SpecialKey.F6 -> tildeKey(17, mod)
            SpecialKey.F7 -> tildeKey(18, mod)
            SpecialKey.F8 -> tildeKey(19, mod)
            SpecialKey.F9 -> tildeKey(20, mod)
            SpecialKey.F10 -> tildeKey(21, mod)
            SpecialKey.F11 -> tildeKey(23, mod)
            SpecialKey.F12 -> tildeKey(24, mod)
        }
    }

    /**
     * One typed character under latched modifiers (the bar's
     * Ctrl/Alt also apply to the soft keyboard's next
     * character). Ctrl maps letters and the documented symbol
     * row to their control bytes (Ctrl-@ = NUL … Ctrl-_ =
     * 0x1F, Ctrl-Space = NUL); a character with no control
     * form keeps its plain bytes under Ctrl. Alt prefixes ESC.
     * With no modifiers this is exactly the UTF-8 bytes of the
     * character.
     */
    fun encodeChar(c: Char, modifiers: KeyModifiers = KeyModifiers()): ByteArray {
        var bytes: ByteArray? = null
        if (modifiers.ctrl) {
            val control: Int? = when (c) {
                in 'a'..'z' -> c.code - 'a'.code + 1
                in 'A'..'Z' -> c.code - 'A'.code + 1
                '@' -> 0x00
                ' ' -> 0x00
                '[' -> 0x1B
                '\\' -> 0x1C
                ']' -> 0x1D
                '^' -> 0x1E
                '_' -> 0x1F
                '?' -> 0x7F
                else -> null
            }
            if (control != null) bytes = byteArrayOf(control.toByte())
        }
        val base = bytes ?: c.toString().toByteArray(Charsets.UTF_8)
        return withAlt(base, modifiers)
    }

    private fun cursorKey(final: String, mod: Int): ByteArray =
        if (mod == 0) csi(final) else csi("1;$mod$final")

    private fun functionKey(final: String, mod: Int): ByteArray =
        if (mod == 0) ss3(final) else csi("1;$mod$final")

    private fun tildeKey(code: Int, mod: Int): ByteArray =
        if (mod == 0) csi("$code~") else csi("$code;${mod}~")

    private fun withAlt(bytes: ByteArray, modifiers: KeyModifiers): ByteArray =
        if (modifiers.alt) byteArrayOf(ESC) + bytes else bytes

    private fun csi(body: String): ByteArray =
        byteArrayOf(ESC, '['.code.toByte()) + body.toByteArray(Charsets.US_ASCII)

    private fun ss3(body: String): ByteArray =
        byteArrayOf(ESC, 'O'.code.toByte()) + body.toByteArray(Charsets.US_ASCII)
}

/** Sticky/lock latch states for one modifier of the bar. */
enum class LatchState { OFF, ONCE, LOCKED }

/**
 * The bar's Ctrl/Alt latch machine: tap cycles
 * OFF → ONCE → LOCKED → OFF. A ONCE latch applies to exactly
 * the next key and then releases; a LOCKED latch applies
 * until tapped off. Pure and deterministic — the UI holds one
 * instance (via the ViewModel) and renders state from it.
 */
class ModifierLatch {
    var ctrl: LatchState = LatchState.OFF
        private set
    var alt: LatchState = LatchState.OFF
        private set

    fun pressCtrl() {
        ctrl = cycle(ctrl)
    }

    fun pressAlt() {
        alt = cycle(alt)
    }

    /** The modifiers for the key being sent NOW. Reading them
     *  consumes ONCE latches (that is the point of sticky
     *  keys); LOCKED latches survive. */
    fun consume(): KeyModifiers {
        val result = KeyModifiers(
            ctrl = ctrl != LatchState.OFF,
            alt = alt != LatchState.OFF
        )
        if (ctrl == LatchState.ONCE) ctrl = LatchState.OFF
        if (alt == LatchState.ONCE) alt = LatchState.OFF
        return result
    }

    /** Peek without consuming (rendering, tests). */
    fun current(): KeyModifiers = KeyModifiers(
        ctrl = ctrl != LatchState.OFF,
        alt = alt != LatchState.OFF
    )

    fun clear() {
        ctrl = LatchState.OFF
        alt = LatchState.OFF
    }

    private fun cycle(state: LatchState): LatchState = when (state) {
        LatchState.OFF -> LatchState.ONCE
        LatchState.ONCE -> LatchState.LOCKED
        LatchState.LOCKED -> LatchState.OFF
    }
}
