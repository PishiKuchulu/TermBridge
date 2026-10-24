package com.pishi.termbridge

import com.pishi.termbridge.domain.RfbDecoder
import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.domain.RfbFramebuffer
import com.pishi.termbridge.domain.RfbPixelFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Framebuffer-decoder tests with synthetic byte streams —
 * prompt 13. NOT RUN (sandbox Gradle limits).
 *
 * Pixel wire format in these tests is the negotiated format
 * (32-bit true-colour, little-endian, red 16 / green 8 /
 * blue 0): an ARGB colour 0xFFRRGGBB travels as bytes
 * [BB, GG, RR, 0x00].
 *
 * These tests prove the decoders against hand-built streams.
 * They do NOT prove a real server's bytes decode correctly —
 * that is the fixture item (controlled server + device run).
 */
class RfbDecoderTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** Build one update message (after the type byte) and apply it. */
    private fun applyUpdate(bytes: ByteArray, framebuffer: RfbFramebuffer) {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        RfbDecoder.applyUpdate(input, framebuffer)
    }

    /** One update with a single rectangle, built by [body]. */
    private fun singleRectUpdate(
        x: Int, y: Int, width: Int, height: Int, encoding: Int,
        body: DataOutputStream.() -> Unit
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(0) // update header padding
        data.writeShort(1) // one rectangle
        data.writeShort(x)
        data.writeShort(y)
        data.writeShort(width)
        data.writeShort(height)
        data.writeInt(encoding)
        data.body()
        data.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.pixel(argb: Int) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        writeByte(b)
        writeByte(g)
        writeByte(r)
        writeByte(0)
    }

    private fun framebuffer(width: Int, height: Int): RfbFramebuffer =
        RfbFramebuffer(width, height, RfbPixelFormat.REQUESTED)

    @Test
    fun `raw rect fills row-major pixels`() {
        val fb = framebuffer(2, 2)
        val bytes = singleRectUpdate(0, 0, 2, 2, 0) {
            pixel(red)
            pixel(green)
            pixel(blue)
            pixel(white)
        }
        applyUpdate(bytes, fb)
        assertArrayEquals(intArrayOf(red, green, blue, white), fb.pixels)
    }

    @Test
    fun `copyrect copies from elsewhere including overlap`() {
        val fb = framebuffer(4, 4)
        // Fill with a known pattern first.
        val fill = singleRectUpdate(0, 0, 4, 4, 0) {
            for (i in 0 until 16) {
                pixel(0xFF000000.toInt() or i)
            }
        }
        applyUpdate(fill, fb)
        // Copy the 2x2 block at (0,0) onto (2,2).
        val copy = singleRectUpdate(2, 2, 2, 2, 1) {
            writeShort(0)
            writeShort(0)
        }
        applyUpdate(copy, fb)
        // Original block: rows 0..1, cols 0..1 = values 0,1,4,5.
        assertEquals(0xFF000000.toInt() or 0, fb.pixels[2 * 4 + 2])
        assertEquals(0xFF000000.toInt() or 1, fb.pixels[2 * 4 + 3])
        assertEquals(0xFF000000.toInt() or 4, fb.pixels[3 * 4 + 2])
        assertEquals(0xFF000000.toInt() or 5, fb.pixels[3 * 4 + 3])
    }

    @Test
    fun `rre fills background then subrectangles`() {
        val fb = framebuffer(4, 2)
        val bytes = singleRectUpdate(0, 0, 4, 2, 2) {
            writeInt(1) // one subrect
            pixel(red) // background
            pixel(blue) // subrect colour
            writeShort(1) // x
            writeShort(0) // y
            writeShort(2) // w
            writeShort(1) // h
        }
        applyUpdate(bytes, fb)
        assertArrayEquals(
            intArrayOf(red, blue, blue, red, red, red, red, red),
            fb.pixels
        )
    }

    @Test
    fun `corre uses byte coordinates`() {
        val fb = framebuffer(4, 2)
        val bytes = singleRectUpdate(0, 0, 4, 2, 4) {
            writeInt(1)
            pixel(black)
            pixel(white)
            writeByte(2)
            writeByte(1)
            writeByte(2)
            writeByte(1)
        }
        applyUpdate(bytes, fb)
        assertArrayEquals(
            intArrayOf(black, black, black, black, black, black, white, white),
            fb.pixels
        )
    }

    @Test
    fun `hextile raw tile decodes`() {
        val fb = framebuffer(16, 16)
        val bytes = singleRectUpdate(0, 0, 16, 16, 5) {
            writeByte(1) // Raw tile
            for (i in 0 until 256) {
                pixel(0xFF000000.toInt() or (i and 0xFF))
            }
        }
        applyUpdate(bytes, fb)
        assertEquals(0xFF000000.toInt() or 0, fb.pixels[0])
        assertEquals(0xFF000000.toInt() or 255, fb.pixels[255])
    }

    @Test
    fun `hextile background foreground and subrect`() {
        val fb = framebuffer(16, 16)
        val bytes = singleRectUpdate(0, 0, 16, 16, 5) {
            writeByte(2 or 4 or 8) // BG + FG + AnySubrects
            pixel(red) // background
            pixel(blue) // foreground
            writeByte(1) // one subrect
            writeByte((2 shl 4) or 3) // x=2, y=3
            writeByte(((4 - 1) shl 4) or (2 - 1)) // w=4, h=2
        }
        applyUpdate(bytes, fb)
        assertEquals(red, fb.pixels[0])
        assertEquals(blue, fb.pixels[3 * 16 + 2])
        assertEquals(blue, fb.pixels[4 * 16 + 5])
        assertEquals(red, fb.pixels[4 * 16 + 6])
    }

    @Test
    fun `hextile background and foreground carry across tiles`() {
        // 32x16 = two tiles. Tile 0 specifies BG+FG; tile 1
        // specifies neither, so both carry over.
        val fb = framebuffer(32, 16)
        val bytes = singleRectUpdate(0, 0, 32, 16, 5) {
            writeByte(2 or 4 or 8)
            pixel(red)
            pixel(blue)
            writeByte(1)
            writeByte(0) // x=0, y=0
            writeByte(0) // w=1, h=1
            writeByte(8) // tile 1: AnySubrects only
            writeByte(1)
            writeByte(0)
            writeByte(0)
        }
        applyUpdate(bytes, fb)
        assertEquals(blue, fb.pixels[0]) // tile 0 subrect
        assertEquals(red, fb.pixels[15]) // tile 0 background
        assertEquals(blue, fb.pixels[16]) // tile 1 subrect (carried FG)
        assertEquals(red, fb.pixels[31]) // tile 1 background (carried BG)
    }

    @Test
    fun `hextile coloured subrects use per-subrect colours`() {
        val fb = framebuffer(16, 16)
        val bytes = singleRectUpdate(0, 0, 16, 16, 5) {
            writeByte(2 or 8 or 16) // BG + AnySubrects + Coloured
            pixel(black)
            writeByte(2) // two subrects
            pixel(red)
            writeByte(0)
            writeByte(0)
            pixel(green)
            writeByte((1 shl 4) or 0)
            writeByte(0)
        }
        applyUpdate(bytes, fb)
        assertEquals(red, fb.pixels[0])
        assertEquals(green, fb.pixels[1])
        assertEquals(black, fb.pixels[2])
    }

    @Test(expected = RfbException::class)
    fun `hextile tile without background on first tile fails`() {
        val fb = framebuffer(16, 16)
        val bytes = singleRectUpdate(0, 0, 16, 16, 5) {
            writeByte(8) // AnySubrects, no BG, no FG, not coloured
            writeByte(1)
            writeByte(0)
            writeByte(0)
        }
        applyUpdate(bytes, fb)
    }

    @Test(expected = RfbException::class)
    fun `hextile foreground plus coloured subrects fails`() {
        val fb = framebuffer(16, 16)
        val bytes = singleRectUpdate(0, 0, 16, 16, 5) {
            writeByte(2 or 4 or 16)
            pixel(black)
            pixel(white)
        }
        applyUpdate(bytes, fb)
    }

    @Test(expected = RfbException::class)
    fun `hextile unknown mask bit fails`() {
        val fb = framebuffer(16, 16)
        val bytes = singleRectUpdate(0, 0, 16, 16, 5) {
            writeByte(32)
        }
        applyUpdate(bytes, fb)
    }

    @Test(expected = RfbException::class)
    fun `unsupported encoding is rejected not rendered`() {
        val fb = framebuffer(4, 4)
        val bytes = singleRectUpdate(0, 0, 4, 4, 7) { // Tight
            writeByte(0)
        }
        applyUpdate(bytes, fb)
    }

    @Test(expected = EOFException::class)
    fun `truncated raw rect fails on end of stream`() {
        val fb = framebuffer(2, 2)
        val bytes = singleRectUpdate(0, 0, 2, 2, 0) {
            pixel(red) // only one of four pixels present
        }
        applyUpdate(bytes, fb)
    }

    @Test(expected = RfbException::class)
    fun `rectangle outside the framebuffer fails`() {
        val fb = framebuffer(4, 4)
        val bytes = singleRectUpdate(3, 3, 2, 2, 0) {
            pixel(red)
            pixel(red)
            pixel(red)
            pixel(red)
        }
        applyUpdate(bytes, fb)
    }

    @Test(expected = RfbException::class)
    fun `rre count above rect area fails`() {
        val fb = framebuffer(2, 2)
        val bytes = singleRectUpdate(0, 0, 2, 2, 2) {
            writeInt(5) // area is 4
            pixel(black)
        }
        applyUpdate(bytes, fb)
    }

    @Test
    fun `desktopsize rect resizes and discards contents`() {
        val fb = framebuffer(4, 4)
        val fill = singleRectUpdate(0, 0, 4, 4, 0) {
            for (i in 0 until 16) {
                pixel(white)
            }
        }
        applyUpdate(fill, fb)
        assertEquals(white, fb.pixels[0])
        val resize = singleRectUpdate(0, 0, 2, 2, -223) {}
        applyUpdate(resize, fb)
        assertEquals(2, fb.width)
        assertEquals(2, fb.height)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), fb.pixels)
    }
}
