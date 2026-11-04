package com.pishi.termbridge

import com.pishi.termbridge.domain.KeyModifiers
import com.pishi.termbridge.domain.LatchState
import com.pishi.termbridge.domain.ModifierLatch
import com.pishi.termbridge.domain.SpecialKey
import com.pishi.termbridge.domain.TerminalKeyEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item T-09: the extra-keys encoding contract. Plain sequences
 * must match what TerminalSession has always sent (arrows are
 * CSI here — the app does not track DECCKM); modified keys use
 * the xterm modifier parameter.
 */
class TerminalKeysTest {

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun encoded(key: SpecialKey, modifiers: KeyModifiers = KeyModifiers()): ByteArray =
        TerminalKeyEncoder.encode(key, modifiers)

    @Test
    fun plainCursorKeysMatchExistingSequences() {
        assertArrayEquals(ascii("\u001B[A"), encoded(SpecialKey.ARROW_UP))
        assertArrayEquals(ascii("\u001B[B"), encoded(SpecialKey.ARROW_DOWN))
        assertArrayEquals(ascii("\u001B[C"), encoded(SpecialKey.ARROW_RIGHT))
        assertArrayEquals(ascii("\u001B[D"), encoded(SpecialKey.ARROW_LEFT))
        assertArrayEquals(ascii("\u001B[H"), encoded(SpecialKey.HOME))
        assertArrayEquals(ascii("\u001B[F"), encoded(SpecialKey.END))
    }

    @Test
    fun plainFunctionKeys() {
        assertArrayEquals(ascii("\u001BOP"), encoded(SpecialKey.F1))
        assertArrayEquals(ascii("\u001BOS"), encoded(SpecialKey.F4))
        assertArrayEquals(ascii("\u001B[15~"), encoded(SpecialKey.F5))
        assertArrayEquals(ascii("\u001B[17~"), encoded(SpecialKey.F6))
        assertArrayEquals(ascii("\u001B[24~"), encoded(SpecialKey.F12))
    }

    @Test
    fun plainEditingKeys() {
        assertArrayEquals(ascii("\u001B[2~"), encoded(SpecialKey.INSERT))
        assertArrayEquals(ascii("\u001B[3~"), encoded(SpecialKey.DELETE))
        assertArrayEquals(ascii("\u001B[5~"), encoded(SpecialKey.PAGE_UP))
        assertArrayEquals(ascii("\u001B[6~"), encoded(SpecialKey.PAGE_DOWN))
        assertArrayEquals(byteArrayOf(0x1B), encoded(SpecialKey.ESCAPE))
        assertArrayEquals(byteArrayOf(0x09), encoded(SpecialKey.TAB))
        assertArrayEquals(byteArrayOf('\r'.code.toByte()), encoded(SpecialKey.ENTER))
        assertArrayEquals(byteArrayOf(0x7F), encoded(SpecialKey.BACKSPACE))
    }

    @Test
    fun modifiedKeysUseXtermParameter() {
        val ctrl = KeyModifiers(ctrl = true)
        val alt = KeyModifiers(alt = true)
        val ctrlAlt = KeyModifiers(ctrl = true, alt = true)
        assertArrayEquals(ascii("\u001B[1;5A"), encoded(SpecialKey.ARROW_UP, ctrl))
        assertArrayEquals(ascii("\u001B[1;3H"), encoded(SpecialKey.HOME, alt))
        assertArrayEquals(ascii("\u001B[1;7D"), encoded(SpecialKey.ARROW_LEFT, ctrlAlt))
        assertArrayEquals(ascii("\u001B[3;5~"), encoded(SpecialKey.DELETE, ctrl))
        assertArrayEquals(ascii("\u001B[15;5~"), encoded(SpecialKey.F5, ctrl))
        assertArrayEquals(ascii("\u001B[1;5P"), encoded(SpecialKey.F1, ctrl))
        assertArrayEquals(ascii("\u001B[1;2B"), encoded(SpecialKey.ARROW_DOWN, KeyModifiers(shift = true)))
    }

    @Test
    fun shiftTabIsBacktab() {
        assertArrayEquals(ascii("\u001B[Z"), encoded(SpecialKey.TAB, KeyModifiers(shift = true)))
        assertArrayEquals(ascii("\u001B[Z"), encoded(SpecialKey.BACKTAB))
    }

    @Test
    fun ctrlBackspaceIsBsNotDel() {
        assertArrayEquals(
            byteArrayOf(0x08),
            encoded(SpecialKey.BACKSPACE, KeyModifiers(ctrl = true))
        )
        // Alt backspace = ESC + DEL (meta-backspace, word erase).
        assertArrayEquals(
            byteArrayOf(0x1B, 0x7F),
            encoded(SpecialKey.BACKSPACE, KeyModifiers(alt = true))
        )
    }

    @Test
    fun xtermParamValues() {
        assertEquals(0, KeyModifiers().xtermParam)
        assertEquals(2, KeyModifiers(shift = true).xtermParam)
        assertEquals(3, KeyModifiers(alt = true).xtermParam)
        assertEquals(5, KeyModifiers(ctrl = true).xtermParam)
        assertEquals(7, KeyModifiers(ctrl = true, alt = true).xtermParam)
        assertEquals(8, KeyModifiers(ctrl = true, alt = true, shift = true).xtermParam)
    }

    @Test
    fun encodeCharControlForms() {
        val ctrl = KeyModifiers(ctrl = true)
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.encodeChar('c', ctrl))
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.encodeChar('C', ctrl))
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeyEncoder.encodeChar('[', ctrl))
        assertArrayEquals(byteArrayOf(0x00), TerminalKeyEncoder.encodeChar(' ', ctrl))
        assertArrayEquals(byteArrayOf(0x00), TerminalKeyEncoder.encodeChar('@', ctrl))
        assertArrayEquals(byteArrayOf(0x1F), TerminalKeyEncoder.encodeChar('_', ctrl))
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeyEncoder.encodeChar('?', ctrl))
    }

    @Test
    fun encodeCharAltPrefixesEscape() {
        assertArrayEquals(
            byteArrayOf(0x1B, 'a'.code.toByte()),
            TerminalKeyEncoder.encodeChar('a', KeyModifiers(alt = true))
        )
        assertArrayEquals(
            byteArrayOf(0x1B, 0x01),
            TerminalKeyEncoder.encodeChar('a', KeyModifiers(ctrl = true, alt = true))
        )
    }

    @Test
    fun encodeCharPlainIsUtf8() {
        assertArrayEquals(
            "a".toByteArray(Charsets.UTF_8),
            TerminalKeyEncoder.encodeChar('a')
        )
        assertArrayEquals(
            "é".toByteArray(Charsets.UTF_8),
            TerminalKeyEncoder.encodeChar('é')
        )
        // No control form: Ctrl leaves the character alone.
        assertArrayEquals(
            "/".toByteArray(Charsets.UTF_8),
            TerminalKeyEncoder.encodeChar('/', KeyModifiers(ctrl = true))
        )
    }

    @Test
    fun latchCyclesAndConsumes() {
        val latch = ModifierLatch()
        assertEquals(LatchState.OFF, latch.ctrl)
        latch.pressCtrl()
        assertEquals(LatchState.ONCE, latch.ctrl)
        // ONCE applies once, then releases itself on consume.
        assertTrue(latch.consume().ctrl)
        assertEquals(LatchState.OFF, latch.ctrl)
        assertFalse(latch.consume().ctrl)
        // Second press locks: consume does not release it.
        latch.pressCtrl()
        latch.pressCtrl()
        assertEquals(LatchState.LOCKED, latch.ctrl)
        assertTrue(latch.consume().ctrl)
        assertEquals(LatchState.LOCKED, latch.ctrl)
        // Third press turns it off.
        latch.pressCtrl()
        assertEquals(LatchState.OFF, latch.ctrl)
    }

    @Test
    fun latchModifiersAreIndependent() {
        val latch = ModifierLatch()
        latch.pressCtrl()
        latch.pressAlt()
        latch.pressAlt()
        val consumed = latch.consume()
        assertTrue(consumed.ctrl)
        assertTrue(consumed.alt)
        assertEquals(LatchState.OFF, latch.ctrl)
        assertEquals(LatchState.LOCKED, latch.alt)
        latch.clear()
        assertEquals(LatchState.OFF, latch.alt)
    }
}
