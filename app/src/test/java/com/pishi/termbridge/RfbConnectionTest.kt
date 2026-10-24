package com.pishi.termbridge

import com.pishi.termbridge.domain.RfbAuth
import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.domain.RfbVersion
import com.pishi.termbridge.transport.RfbConnector
import com.pishi.termbridge.transport.RfbServerMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted loopback-peer tests for the RFB handshake — prompt 13.
 * NOT RUN (sandbox Gradle limits).
 *
 * HONEST SCOPE, stated on the test itself: the "server" here is
 * a scripted socket peer written in this file. It proves the
 * client's handshake sequencing (version -> security -> init ->
 * format/encodings -> update request -> update) against bytes
 * this test controls. It does NOT prove interop with a real
 * VNC server (TigerVNC/TightVNC/x11vnc) — that is the fixture
 * item, and no claim in this file says otherwise.
 *
 * The VNC-Auth peer check below is deliberately circular in
 * one respect: the peer verifies the response with the same
 * [RfbAuth] code the client used. That validates the wire
 * flow (challenge in, response out, result read), not the
 * cryptography — the crypto rests on the FIPS vector and
 * hand-computed reversal tests in RfbAuthTest.
 */
class RfbConnectionTest {

    /** A scripted RFB server speaking from a byte script. */
    private class ScriptedPeer(
        private val script: (DataInputStream, DataOutputStream) -> Unit
    ) {
        val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private var failure: Throwable? = null

        fun start(): Thread {
            val thread = Thread {
                try {
                    serverSocket.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        script(input, output)
                    }
                } catch (e: Throwable) {
                    // Throwable, not Exception: a failed peer
                    // assertion is an AssertionError (an Error),
                    // and it must be reported, not swallowed by
                    // the thread dying silently.
                    failure = e
                } finally {
                    try {
                        serverSocket.close()
                    } catch (_: Exception) {
                    }
                }
            }
            thread.start()
            return thread
        }

        fun checkNoPeerFailure() {
            failure?.let { throw AssertionError("Peer failed: $it", it) }
        }
    }

    private fun readExact(input: DataInputStream, count: Int) {
        val bytes = ByteArray(count)
        input.readFully(bytes)
    }

    private fun writeServerInit(output: DataOutputStream, name: String) {
        output.writeShort(2)
        output.writeShort(2)
        output.writeByte(32)
        output.writeByte(24)
        output.writeByte(0)
        output.writeByte(1)
        output.writeShort(255)
        output.writeShort(255)
        output.writeShort(255)
        output.writeByte(16)
        output.writeByte(8)
        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(0)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        output.writeInt(nameBytes.size)
        output.write(nameBytes)
        output.flush()
    }

    private fun writeRawUpdate(
        output: DataOutputStream,
        pixels: IntArray
    ) {
        output.writeByte(0) // FramebufferUpdate
        output.writeByte(0) // padding
        output.writeShort(1) // one rect
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(2)
        output.writeShort(2)
        output.writeInt(0) // Raw
        for (argb in pixels) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            output.writeByte(b)
            output.writeByte(g)
            output.writeByte(r)
            output.writeByte(0)
        }
        output.flush()
    }

    private val testPixels = intArrayOf(
        0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
        0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()
    )

    @Test(timeout = 10_000)
    fun `none auth handshake then raw update applies`() = runBlocking {
        val peer = ScriptedPeer { input, output ->
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            readExact(input, 12) // client version
            output.writeByte(1) // one security type
            output.writeByte(1) // None
            output.flush()
            assertEquals(1, input.readUnsignedByte()) // client choice
            output.writeInt(0) // SecurityResult OK
            output.flush()
            assertEquals(1, input.readUnsignedByte()) // ClientInit shared
            writeServerInit(output, "peer")
            readExact(input, 20) // SetPixelFormat
            readExact(input, 20) // SetEncodings (4 types)
            readExact(input, 10) // FramebufferUpdateRequest
            writeRawUpdate(output, testPixels)
        }
        val thread = peer.start()
        val session = RfbConnector.connect(
            hostname = "127.0.0.1",
            port = peer.port,
            password = null,
            allowUnauthenticated = true
        )
        try {
            assertEquals(RfbVersion.V3_8, session.negotiatedVersion)
            assertEquals("peer", session.desktopName)
            session.requestUpdate(false)
            val message = session.readServerMessage()
            assertTrue(message is RfbServerMessage.FramebufferApplied)
            assertArrayEquals(testPixels, session.framebuffer.pixels)
        } finally {
            session.close()
        }
        thread.join(5_000)
        peer.checkNoPeerFailure()
    }

    @Test(timeout = 10_000)
    fun `vnc auth handshake completes and password is wiped`() = runBlocking {
        val password = "secret".toCharArray()
        val challenge = ByteArray(16) { (it * 3 + 1).toByte() }
        val peer = ScriptedPeer { input, output ->
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            readExact(input, 12)
            output.writeByte(1)
            output.writeByte(2) // VNC Auth
            output.flush()
            assertEquals(2, input.readUnsignedByte())
            output.write(challenge)
            output.flush()
            val response = ByteArray(16)
            input.readFully(response)
            // Wire-flow check only (same auth code both sides —
            // see the file KDoc for why this is not a crypto test).
            val key = RfbAuth.deriveKey("secret".toByteArray(Charsets.UTF_8))
            assertArrayEquals(
                RfbAuth.encryptChallenge(challenge, key),
                response
            )
            output.writeInt(0) // SecurityResult OK
            output.flush()
            assertEquals(1, input.readUnsignedByte()) // ClientInit
            writeServerInit(output, "peer")
            readExact(input, 20)
            readExact(input, 20)
            readExact(input, 10)
            writeRawUpdate(output, testPixels)
        }
        val thread = peer.start()
        val session = RfbConnector.connect(
            hostname = "127.0.0.1",
            port = peer.port,
            password = password,
            allowUnauthenticated = false
        )
        try {
            session.requestUpdate(false)
            val message = session.readServerMessage()
            assertTrue(message is RfbServerMessage.FramebufferApplied)
            assertArrayEquals(testPixels, session.framebuffer.pixels)
        } finally {
            session.close()
        }
        // The connector wipes the caller's password, pass or fail.
        assertArrayEquals(CharArray(6), password)
        thread.join(5_000)
        peer.checkNoPeerFailure()
    }

    @Test(timeout = 10_000)
    fun `none offered but not allowed fails without connecting`() = runBlocking {
        val peer = ScriptedPeer { input, output ->
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            readExact(input, 12)
            output.writeByte(1)
            output.writeByte(1) // None only
            output.flush()
            // Client must fail before sending a choice; the peer
            // just waits for the socket to close (read returns -1).
            input.read()
        }
        val thread = peer.start()
        try {
            RfbConnector.connect(
                hostname = "127.0.0.1",
                port = peer.port,
                password = null,
                allowUnauthenticated = false
            )
            throw AssertionError("Expected RfbException")
        } catch (e: RfbException) {
            assertEquals("No supported authentication method.", e.message)
        } finally {
            thread.join(5_000)
        }
    }

    @Test(timeout = 10_000)
    fun `vencrypt only fails instead of downgrading`() = runBlocking {
        val peer = ScriptedPeer { input, output ->
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            readExact(input, 12)
            output.writeByte(1)
            output.writeByte(19) // VeNCrypt only
            output.flush()
            input.read()
        }
        val thread = peer.start()
        try {
            RfbConnector.connect(
                hostname = "127.0.0.1",
                port = peer.port,
                password = "secret".toCharArray(),
                allowUnauthenticated = true
            )
            throw AssertionError("Expected RfbException")
        } catch (e: RfbException) {
            assertEquals("No supported authentication method.", e.message)
        } finally {
            thread.join(5_000)
        }
    }
}
