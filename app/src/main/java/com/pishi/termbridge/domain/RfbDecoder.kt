package com.pishi.termbridge.domain

import java.io.DataInput

/**
 * Framebuffer-update decoding — prompt 13.
 *
 * Implemented encodings (the client advertises exactly these):
 * Raw (0), CopyRect (1), RRE (2), CoRRE (4), Hextile (5).
 * Everything else — Tight (7), ZRLE (16), TRLE (15), zlib (6),
 * zlibhex (8), Cursor (-239) and any unknown number — is
 * REJECTED with [RfbException], never rendered approximately.
 * A server only sends encodings the client advertised (plus Raw
 * always), so a rejection here means the stream is already
 * desynchronised or hostile; continuing would draw garbage.
 *
 * Fail-closed rules applied throughout:
 * - Rectangles and subrectangles outside the framebuffer (or
 *   their parent rect/tile) are rejected, not cropped.
 * - RRE/CoRRE subrectangle counts above the rect's pixel count
 *   are rejected (each subrect covers at least one pixel).
 * - Hextile masks with unknown bits, or ForegroundSpecified
 *   combined with SubrectsColoured, are rejected.
 * - A Hextile tile that needs a carried-over background or
 *   foreground it does not have (first tile, or after a raw
 *   tile) is rejected.
 * - A truncated stream raises EOFException from the input and
 *   aborts the update; the caller closes the connection.
 *
 * [applyUpdate] consumes one FramebufferUpdate message AFTER
 * its message-type byte has been read by the caller (see
 * [transport.RfbSession.readServerMessage]).
 */
object RfbDecoder {

    private const val TILE_SIZE = 16

    /** Hextile subencoding mask bits. */
    private const val HEX_RAW = 1
    private const val HEX_BG_SPECIFIED = 2
    private const val HEX_FG_SPECIFIED = 4
    private const val HEX_ANY_SUBRECTS = 8
    private const val HEX_SUBRECTS_COLOURED = 16
    private const val HEX_KNOWN_MASK = 31

    fun applyUpdate(input: DataInput, framebuffer: RfbFramebuffer) {
        val count = RfbMessages.parseUpdateHeader(input)
        repeat(count) {
            val rect = RfbMessages.parseRectHeader(input)
            applyRect(input, framebuffer, rect)
        }
    }

    private fun applyRect(
        input: DataInput,
        framebuffer: RfbFramebuffer,
        rect: RfbRectHeader
    ) {
        // DesktopSize is a resize, not pixel data: no bytes
        // follow the header, and previous contents are discarded.
        if (rect.encoding == RfbEncoding.DESKTOP_SIZE) {
            framebuffer.resize(rect.width, rect.height)
            return
        }
        if (!RfbEncoding.isImplemented(rect.encoding)) {
            throw RfbException("Unsupported encoding in update.")
        }
        if (rect.width <= 0 || rect.height <= 0 ||
            rect.x < 0 || rect.y < 0 ||
            rect.x + rect.width > framebuffer.width ||
            rect.y + rect.height > framebuffer.height
        ) {
            throw RfbException("Rectangle is outside the framebuffer.")
        }
        when (rect.encoding) {
            RfbEncoding.RAW -> decodeRaw(input, framebuffer, rect)
            RfbEncoding.COPY_RECT -> {
                val srcX = input.readUnsignedShort()
                val srcY = input.readUnsignedShort()
                framebuffer.copyRect(
                    srcX, srcY, rect.x, rect.y, rect.width, rect.height
                )
            }
            RfbEncoding.RRE -> decodeRre(input, framebuffer, rect, wideCoords = true)
            RfbEncoding.CORRE -> decodeRre(input, framebuffer, rect, wideCoords = false)
            RfbEncoding.HEXTILE -> decodeHextile(input, framebuffer, rect)
        }
    }

    private fun readPixel(input: DataInput, framebuffer: RfbFramebuffer): Int {
        val format = framebuffer.format
        val bytes = ByteArray(format.bytesPerPixel)
        input.readFully(bytes)
        val value = format.readPixelValue(bytes, 0)
        return format.toArgb(value, framebuffer.colourMap)
    }

    private fun decodeRaw(
        input: DataInput,
        framebuffer: RfbFramebuffer,
        rect: RfbRectHeader
    ) {
        for (row in 0 until rect.height) {
            for (col in 0 until rect.width) {
                framebuffer.setPixel(
                    rect.x + col, rect.y + row,
                    readPixel(input, framebuffer)
                )
            }
        }
    }

    /**
     * RRE and CoRRE share one layout: U32 subrect count,
     * background pixel, then subrects (pixel + x, y, w, h).
     * The only difference is coordinate width: U16 for RRE,
     * U8 for CoRRE.
     */
    private fun decodeRre(
        input: DataInput,
        framebuffer: RfbFramebuffer,
        rect: RfbRectHeader,
        wideCoords: Boolean
    ) {
        val count = input.readInt()
        if (count < 0 || count.toLong() > rect.width.toLong() * rect.height.toLong()) {
            throw RfbException("Subrectangle count is out of range.")
        }
        val background = readPixel(input, framebuffer)
        framebuffer.fillRect(rect.x, rect.y, rect.width, rect.height, background)
        repeat(count) {
            val colour = readPixel(input, framebuffer)
            val sx: Int
            val sy: Int
            val sw: Int
            val sh: Int
            if (wideCoords) {
                sx = input.readUnsignedShort()
                sy = input.readUnsignedShort()
                sw = input.readUnsignedShort()
                sh = input.readUnsignedShort()
            } else {
                sx = input.readUnsignedByte()
                sy = input.readUnsignedByte()
                sw = input.readUnsignedByte()
                sh = input.readUnsignedByte()
            }
            if (sw <= 0 || sh <= 0 ||
                sx < 0 || sy < 0 ||
                sx + sw > rect.width || sy + sh > rect.height
            ) {
                throw RfbException("Subrectangle is outside its rectangle.")
            }
            framebuffer.fillRect(rect.x + sx, rect.y + sy, sw, sh, colour)
        }
    }

    /**
     * Hextile: the rect is split into 16x16 tiles (edge tiles
     * smaller). Per tile: a mask byte, then — unless Raw — an
     * optional background, an optional foreground, and optional
     * subrectangles. Background/foreground carry over from the
     * previous tile unless the mask specifies them, EXCEPT after
     * a raw tile (carry is invalid there) and a foreground may
     * never be carried over a SubrectsColoured tile.
     */
    private fun decodeHextile(
        input: DataInput,
        framebuffer: RfbFramebuffer,
        rect: RfbRectHeader
    ) {
        var background = 0
        var backgroundValid = false
        var foreground = 0
        var foregroundValid = false
        var tileY = 0
        while (tileY < rect.height) {
            val tileH = minOf(TILE_SIZE, rect.height - tileY)
            var tileX = 0
            while (tileX < rect.width) {
                val tileW = minOf(TILE_SIZE, rect.width - tileX)
                val mask = input.readUnsignedByte()
                if (mask and HEX_KNOWN_MASK.inv() != 0) {
                    throw RfbException("Unknown Hextile subencoding.")
                }
                if (mask and HEX_RAW != 0) {
                    // Raw tile: pixel data follows; all other
                    // bits are irrelevant, and carry-over dies.
                    for (row in 0 until tileH) {
                        for (col in 0 until tileW) {
                            framebuffer.setPixel(
                                rect.x + tileX + col,
                                rect.y + tileY + row,
                                readPixel(input, framebuffer)
                            )
                        }
                    }
                    backgroundValid = false
                    foregroundValid = false
                } else {
                    if (mask and HEX_BG_SPECIFIED != 0) {
                        background = readPixel(input, framebuffer)
                        backgroundValid = true
                    } else if (!backgroundValid) {
                        throw RfbException(
                            "Hextile tile has no background colour."
                        )
                    }
                    if (mask and HEX_FG_SPECIFIED != 0) {
                        if (mask and HEX_SUBRECTS_COLOURED != 0) {
                            throw RfbException(
                                "Hextile tile specifies both foreground and coloured subrectangles."
                            )
                        }
                        foreground = readPixel(input, framebuffer)
                        foregroundValid = true
                    }
                    framebuffer.fillRect(
                        rect.x + tileX, rect.y + tileY, tileW, tileH, background
                    )
                    if (mask and HEX_ANY_SUBRECTS != 0) {
                        val subCount = input.readUnsignedByte()
                        val coloured = mask and HEX_SUBRECTS_COLOURED != 0
                        repeat(subCount) {
                            val colour = if (coloured) {
                                readPixel(input, framebuffer)
                            } else {
                                if (!foregroundValid) {
                                    throw RfbException(
                                        "Hextile tile has no foreground colour."
                                    )
                                }
                                foreground
                            }
                            val xy = input.readUnsignedByte()
                            val wh = input.readUnsignedByte()
                            val sx = xy ushr 4
                            val sy = xy and 0x0F
                            val sw = (wh ushr 4) + 1
                            val sh = (wh and 0x0F) + 1
                            if (sx + sw > tileW || sy + sh > tileH) {
                                throw RfbException(
                                    "Hextile subrectangle is outside its tile."
                                )
                            }
                            framebuffer.fillRect(
                                rect.x + tileX + sx, rect.y + tileY + sy,
                                sw, sh, colour
                            )
                        }
                        if (coloured) {
                            // A coloured tile's subrects carry no
                            // single foreground: the next tile
                            // must specify its own.
                            foregroundValid = false
                        }
                    }
                }
                tileX += TILE_SIZE
            }
            tileY += TILE_SIZE
        }
    }
}
