package com.pishi.termbridge.domain

/**
 * Framebuffer model for the RFB protocol core — prompt 13.
 *
 * Pixels are stored as ARGB ints (alpha always 0xFF: RFB has no
 * alpha channel). The wire pixel format is whatever the server
 * sent in ServerInit, replaced by the format the client requests
 * with SetPixelFormat — the connection always requests the
 * 32-bit true-colour format below, so in practice decoding uses
 * that format; the conversion here still honours any format a
 * test or a future caller supplies, including 16-bit
 * true-colour and 8-bit colour-mapped formats.
 *
 * Bounds are enforced, not clamped silently: a rectangle or
 * subrectangle extending outside the framebuffer is a protocol
 * error ([RfbException]), because silently cropping would hide
 * a desynchronised stream — and a desynchronised stream renders
 * garbage from then on. The only silent discard is a
 * DesktopSize resize, where the spec itself says previous
 * contents are invalidated.
 */
data class RfbPixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColour: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int
) {
    /** Bytes per pixel on the wire: 1, 2 or 4. */
    val bytesPerPixel: Int
        get() = when (bitsPerPixel) {
            8 -> 1
            16 -> 2
            32 -> 4
            else -> throw RfbException("Unsupported pixel depth.")
        }

    companion object {
        /**
         * The format the client requests with SetPixelFormat:
         * 32-bit, 24-bit depth, little-endian, true colour,
         * 8 bits per channel (red 16, green 8, blue 0). This is
         * the format every decoder test uses, and the format the
         * connection negotiates — so wire pixels are simply
         * little-endian 0x00RRGGBB values.
         */
        val REQUESTED = RfbPixelFormat(
            bitsPerPixel = 32,
            depth = 24,
            bigEndian = false,
            trueColour = true,
            redMax = 255,
            greenMax = 255,
            blueMax = 255,
            redShift = 16,
            greenShift = 8,
            blueShift = 0
        )
    }

    /**
     * Combine raw wire bytes (already in wire order) into one
     * pixel value, honouring the format's endianness flag.
     * Only the first [bytesPerPixel] bytes are read.
     */
    fun readPixelValue(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        if (bigEndian) {
            for (i in 0 until bytesPerPixel) {
                value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF).toLong()
            }
        } else {
            for (i in bytesPerPixel - 1 downTo 0) {
                value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF).toLong()
            }
        }
        return value
    }

    /**
     * Convert a wire pixel value to ARGB. True-colour values are
     * extracted with the shifts/maxes and scaled to 0–255 per
     * channel; colour-mapped values are indices into [colourMap]
     * (each entry an ARGB int). A zero max means "channel absent"
     * and scales to 0, never a divide-by-zero.
     */
    fun toArgb(value: Long, colourMap: IntArray): Int {
        if (!trueColour) {
            val index = value.toInt()
            if (index < 0 || index >= colourMap.size) {
                throw RfbException("Colour map index is out of range.")
            }
            return colourMap[index]
        }
        val red = scaleChannel((value shr redShift).toInt() and redMax, redMax)
        val green = scaleChannel((value shr greenShift).toInt() and greenMax, greenMax)
        val blue = scaleChannel((value shr blueShift).toInt() and blueMax, blueMax)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun scaleChannel(raw: Int, max: Int): Int =
        if (max <= 0) 0 else (raw * 255) / max
}

/**
 * One framebuffer: dimensions, pixel format, colour map, and
 * the ARGB pixel array in row-major order. All writes are
 * bounds-checked; out-of-range writes throw rather than crop.
 */
class RfbFramebuffer(
    width: Int,
    height: Int,
    val format: RfbPixelFormat
) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    /** Colour map for non-true-colour formats; empty otherwise. */
    var colourMap: IntArray = IntArray(0)
        private set

    var pixels: IntArray = IntArray(width * height)
        private set

    init {
        RfbProtocol.checkDimensions(width, height)
    }

    /** Replace the colour map (SetColourMapEntries message). */
    fun setColourMap(firstColour: Int, colours: IntArray) {
        if (firstColour < 0 || firstColour + colours.size > 256) {
            throw RfbException("Colour map range is out of range.")
        }
        if (colourMap.size < 256) {
            colourMap = IntArray(256)
        }
        colours.copyInto(colourMap, firstColour)
    }

    /** Resize for a DesktopSize rectangle: contents are
     *  discarded, per the spec's invalidation rule. */
    fun resize(newWidth: Int, newHeight: Int) {
        RfbProtocol.checkDimensions(newWidth, newHeight)
        width = newWidth
        height = newHeight
        pixels = IntArray(newWidth * newHeight)
    }

    fun setPixel(x: Int, y: Int, argb: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw RfbException("Pixel is outside the framebuffer.")
        }
        pixels[y * width + x] = argb
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        checkRect(x, y, w, h)
        for (row in y until y + h) {
            pixels.fill(argb, row * width + x, row * width + x + w)
        }
    }

    /**
     * Copy a rectangle from elsewhere in the framebuffer.
     * Overlap-safe: the source region is snapshotted first, so
     * a copy onto itself (or overlapping itself) copies the
     * original pixels, not partially-overwritten ones.
     */
    fun copyRect(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) {
        checkRect(srcX, srcY, w, h)
        checkRect(x, y, w, h)
        val snapshot = IntArray(w * h)
        for (row in 0 until h) {
            pixels.copyInto(
                snapshot, row * w,
                (srcY + row) * width + srcX, (srcY + row) * width + srcX + w
            )
        }
        for (row in 0 until h) {
            snapshot.copyInto(
                pixels, (y + row) * width + x, row * w, row * w + w
            )
        }
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || x < 0 || y < 0 ||
            x + w > width || y + h > height
        ) {
            throw RfbException("Rectangle is outside the framebuffer.")
        }
    }
}
