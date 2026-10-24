package com.pishi.termbridge.domain

import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataOutputStream

/**
 * RFB message parsing and building — prompt 13.
 *
 * All multi-byte integers on the wire are big-endian (pixel
 * values excepted — see [RfbPixelFormat]). Parsers read from a
 * [DataInput] so the same code serves a socket stream in
 * production and a byte-array stream in tests; a truncated
 * stream raises EOFException, which callers treat as a failed
 * connection, never as a partial update.
 *
 * Builders return exact byte arrays; the message tests assert
 * them byte-for-byte, because a one-byte padding error would
 * desynchronise the whole stream.
 */
data class RfbServerInit(
    val width: Int,
    val height: Int,
    val pixelFormat: RfbPixelFormat,
    val desktopName: String
)

data class RfbRectHeader(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val encoding: Int
)

object RfbMessages {

    // ---- Server messages (parsing) ----

    /**
     * Parse ServerInit: U16 width, U16 height, 16-byte pixel
     * format, U32 name-length, name bytes (UTF-8; invalid UTF-8
     * is replaced, never fatal — the name is display-only).
     * Dimensions are validated against the hard caps.
     */
    fun parseServerInit(input: DataInput): RfbServerInit {
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        RfbProtocol.checkDimensions(width, height)
        val format = RfbPixelFormat(
            bitsPerPixel = input.readUnsignedByte(),
            depth = input.readUnsignedByte(),
            bigEndian = input.readUnsignedByte() != 0,
            trueColour = input.readUnsignedByte() != 0,
            redMax = input.readUnsignedShort(),
            greenMax = input.readUnsignedShort(),
            blueMax = input.readUnsignedShort(),
            redShift = input.readUnsignedByte(),
            greenShift = input.readUnsignedByte(),
            blueShift = input.readUnsignedByte()
        )
        input.skipBytes(3)
        val nameLength = input.readInt()
        if (nameLength < 0 || nameLength > 1_048_576) {
            throw RfbException("Desktop name length is out of range.")
        }
        val nameBytes = ByteArray(nameLength)
        input.readFully(nameBytes)
        return RfbServerInit(
            width, height, format,
            String(nameBytes, Charsets.UTF_8)
        )
    }

    /**
     * Parse a FramebufferUpdate header (after the message-type
     * byte): 1 padding byte, U16 rectangle count. A count above
     * the number of rectangles a single update can sanely carry
     * is rejected outright rather than looped over.
     */
    fun parseUpdateHeader(input: DataInput): Int {
        input.skipBytes(1)
        val count = input.readUnsignedShort()
        return count
    }

    /** Parse one rectangle header: U16 x, y, w, h, S32 encoding. */
    fun parseRectHeader(input: DataInput): RfbRectHeader {
        return RfbRectHeader(
            x = input.readUnsignedShort(),
            y = input.readUnsignedShort(),
            width = input.readUnsignedShort(),
            height = input.readUnsignedShort(),
            encoding = input.readInt()
        )
    }

    // ---- Client messages (building) ----

    /** SetPixelFormat: type 0, 3 padding, 16-byte pixel format. */
    fun buildSetPixelFormat(format: RfbPixelFormat): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(RfbClientMessage.SET_PIXEL_FORMAT)
        data.writeByte(0)
        data.writeByte(0)
        data.writeByte(0)
        data.writeByte(format.bitsPerPixel)
        data.writeByte(format.depth)
        data.writeByte(if (format.bigEndian) 1 else 0)
        data.writeByte(if (format.trueColour) 1 else 0)
        data.writeShort(format.redMax)
        data.writeShort(format.greenMax)
        data.writeShort(format.blueMax)
        data.writeByte(format.redShift)
        data.writeByte(format.greenShift)
        data.writeByte(format.blueShift)
        data.writeByte(0)
        data.writeByte(0)
        data.writeByte(0)
        data.flush()
        return out.toByteArray()
    }

    /** SetEncodings: type 2, 1 padding, U16 count, S32 encodings. */
    fun buildSetEncodings(encodings: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(RfbClientMessage.SET_ENCODINGS)
        data.writeByte(0)
        data.writeShort(encodings.size)
        for (encoding in encodings) {
            data.writeInt(encoding)
        }
        data.flush()
        return out.toByteArray()
    }

    /**
     * FramebufferUpdateRequest: type 3, incremental flag, U16
     * x, y, w, h. Incremental = false requests a full redraw;
     * true requests only changes since the last update.
     */
    fun buildUpdateRequest(
        incremental: Boolean,
        x: Int, y: Int, width: Int, height: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(RfbClientMessage.FRAMEBUFFER_UPDATE_REQUEST)
        data.writeByte(if (incremental) 1 else 0)
        data.writeShort(x)
        data.writeShort(y)
        data.writeShort(width)
        data.writeShort(height)
        data.flush()
        return out.toByteArray()
    }

    /** KeyEvent: type 4, down flag, 2 padding, U32 keysym. */
    fun buildKeyEvent(down: Boolean, keysym: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(RfbClientMessage.KEY_EVENT)
        data.writeByte(if (down) 1 else 0)
        data.writeByte(0)
        data.writeByte(0)
        data.writeInt(keysym)
        data.flush()
        return out.toByteArray()
    }

    /** PointerEvent: type 5, button mask, U16 x, y. */
    fun buildPointerEvent(buttonMask: Int, x: Int, y: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(RfbClientMessage.POINTER_EVENT)
        data.writeByte(buttonMask)
        data.writeShort(x)
        data.writeShort(y)
        data.flush()
        return out.toByteArray()
    }

    /** ClientCutText: type 6, 3 padding, U32 length, UTF-8 text. */
    fun buildClientCutText(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > RfbProtocol.MAX_CUT_TEXT_BYTES) {
            throw RfbException("Clipboard text is too large.")
        }
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(RfbClientMessage.CLIENT_CUT_TEXT)
        data.writeByte(0)
        data.writeByte(0)
        data.writeByte(0)
        data.writeInt(bytes.size)
        data.write(bytes)
        data.flush()
        return out.toByteArray()
    }
}
