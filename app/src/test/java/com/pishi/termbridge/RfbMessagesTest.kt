package com.pishi.termbridge

import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.domain.RfbMessages
import com.pishi.termbridge.domain.RfbPixelFormat
import com.pishi.termbridge.domain.RfbProtocol
import com.pishi.termbridge.domain.RfbVersion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFB message parsing/building tests — prompt 13. NOT RUN
 * (sandbox Gradle limits).
 *
 * Builder tests are byte-exact: a padding error would
 * desynchronise the whole stream, so "close enough" is not
 * accepted here.
 */
class RfbMessagesTest {

    private fun serverInitBytes(
        width: Int, height: Int, name: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(width)
        data.writeShort(height)
        data.writeByte(32)
        data.writeByte(24)
        data.writeByte(0)
        data.writeByte(1)
        data.writeShort(255)
        data.writeShort(255)
        data.writeShort(255)
        data.writeByte(16)
        data.writeByte(8)
        data.writeByte(0)
        data.writeByte(0)
        data.writeByte(0)
        data.writeByte(0)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        data.writeInt(nameBytes.size)
        data.write(nameBytes)
        data.flush()
        return out.toByteArray()
    }

    @Test
    fun `serverinit parses dimensions format and name`() {
        val input = DataInputStream(
            ByteArrayInputStream(serverInitBytes(1024, 768, "peer"))
        )
        val init = RfbMessages.parseServerInit(input)
        assertEquals(1024, init.width)
        assertEquals(768, init.height)
        assertEquals(RfbPixelFormat.REQUESTED, init.pixelFormat)
        assertEquals("peer", init.desktopName)
    }

    @Test(expected = RfbException::class)
    fun `serverinit rejects absurd dimensions`() {
        val input = DataInputStream(
            ByteArrayInputStream(serverInitBytes(20000, 768, "peer"))
        )
        RfbMessages.parseServerInit(input)
    }

    @Test
    fun `version negotiation selects exact versions`() {
        assertEquals(
            RfbVersion.V3_8,
            RfbProtocol.negotiateVersion("RFB 003.008\n")
        )
        assertEquals(
            RfbVersion.V3_7,
            RfbProtocol.negotiateVersion("RFB 003.007\n")
        )
        assertEquals(
            RfbVersion.V3_3,
            RfbProtocol.negotiateVersion("RFB 003.003\n")
        )
    }

    @Test
    fun `unknown 3x version falls back to 3-3 semantics`() {
        assertEquals(
            RfbVersion.V3_3,
            RfbProtocol.negotiateVersion("RFB 003.006\n")
        )
    }

    @Test(expected = RfbException::class)
    fun `non-3 major version is rejected`() {
        RfbProtocol.negotiateVersion("RFB 004.000\n")
    }

    @Test(expected = RfbException::class)
    fun `malformed version string is rejected`() {
        RfbProtocol.negotiateVersion("hello world!\n")
    }

    @Test
    fun `setpixelformat bytes are exact`() {
        val bytes = RfbMessages.buildSetPixelFormat(RfbPixelFormat.REQUESTED)
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 0,
                32, 24, 0, 1,
                0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
                16, 8, 0, 0, 0, 0
            ),
            bytes
        )
    }

    @Test
    fun `setencodings bytes are exact`() {
        val bytes = RfbMessages.buildSetEncodings(intArrayOf(5, 2, 1, 0))
        assertArrayEquals(
            byteArrayOf(
                2, 0, 0, 4,
                0, 0, 0, 5,
                0, 0, 0, 2,
                0, 0, 0, 1,
                0, 0, 0, 0
            ),
            bytes
        )
    }

    @Test
    fun `update request bytes are exact`() {
        val bytes = RfbMessages.buildUpdateRequest(false, 0, 0, 1024, 768)
        assertArrayEquals(
            byteArrayOf(3, 0, 0, 0, 0, 0, 4, 0, 3, 0),
            bytes
        )
        val incremental = RfbMessages.buildUpdateRequest(true, 0, 0, 2, 2)
        assertArrayEquals(
            byteArrayOf(3, 1, 0, 0, 0, 0, 0, 2, 0, 2),
            incremental
        )
    }

    @Test
    fun `key and pointer event bytes are exact`() {
        assertArrayEquals(
            byteArrayOf(4, 1, 0, 0, 0, 0, 0, 0x61),
            RfbMessages.buildKeyEvent(true, 0x61)
        )
        assertArrayEquals(
            byteArrayOf(5, 1, 0, 100, 0, 200.toByte()),
            RfbMessages.buildPointerEvent(1, 100, 200)
        )
    }
}
