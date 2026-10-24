package com.pishi.termbridge.transport

import com.pishi.termbridge.domain.RfbAuth
import com.pishi.termbridge.domain.RfbDecoder
import com.pishi.termbridge.domain.RfbEncoding
import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.domain.RfbFramebuffer
import com.pishi.termbridge.domain.RfbMessages
import com.pishi.termbridge.domain.RfbPixelFormat
import com.pishi.termbridge.domain.RfbProtocol
import com.pishi.termbridge.domain.RfbSecurityType
import com.pishi.termbridge.domain.RfbServerMessageType
import com.pishi.termbridge.domain.RfbVersion
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Server messages delivered by [RfbSession.readServerMessage].
 * Clipboard text is carried, never logged; a future UI decides
 * whether to show it. Bell carries no data.
 */
sealed class RfbServerMessage {
    /** A FramebufferUpdate was applied to [RfbSession.framebuffer]. */
    data object FramebufferApplied : RfbServerMessage()

    /** Colour map changed; the framebuffer already holds it. */
    data object ColourMapApplied : RfbServerMessage()

    data object Bell : RfbServerMessage()

    data class ServerCutText(val text: String) : RfbServerMessage()
}

/**
 * A live RFB session: socket, streams, negotiated version, and
 * the framebuffer that updates land in. Created only by
 * [RfbConnector.connect]; the caller owns the session and must
 * call [close] (the reader loop's EOF also closes it).
 *
 * This class is NOT wired to any screen or ViewModel in prompt
 * 13: a session has never been proven against a real server
 * (see docs/VNC_NOTES.md), so no connect button exists that
 * could present it as working. Its first real use is the
 * prompt-13 fixture: a controlled Linux VNC server plus a
 * device run. Until then it is protocol groundwork, tested
 * only against synthetic streams and a scripted loopback peer
 * in unit tests (both labelled as such, never as a real-server
 * test).
 */
class RfbSession internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val negotiatedVersion: RfbVersion,
    val desktopName: String,
    val framebuffer: RfbFramebuffer
) {
    /** Request a full (non-incremental) or incremental update. */
    fun requestUpdate(incremental: Boolean) {
        val bytes = RfbMessages.buildUpdateRequest(
            incremental, 0, 0, framebuffer.width, framebuffer.height
        )
        output.write(bytes)
        output.flush()
    }

    /**
     * Read and apply one server message. Blocks on the socket
     * read timeout; EOF or a protocol error closes the session
     * and propagates as [RfbException] (EOF is wrapped so
     * callers see one error type, not a raw stream exception).
     */
    fun readServerMessage(): RfbServerMessage {
        try {
            return when (val type = input.readUnsignedByte()) {
                RfbServerMessageType.FRAMEBUFFER_UPDATE -> {
                    RfbDecoder.applyUpdate(input, framebuffer)
                    RfbServerMessage.FramebufferApplied
                }
                RfbServerMessageType.SET_COLOUR_MAP_ENTRIES -> {
                    input.skipBytes(1)
                    val first = input.readUnsignedShort()
                    val count = input.readUnsignedShort()
                    if (count < 0 || count > 256 || first + count > 256) {
                        throw RfbException("Colour map range is out of range.")
                    }
                    val colours = IntArray(count)
                    for (i in 0 until count) {
                        val r = input.readUnsignedShort() shr 8
                        val g = input.readUnsignedShort() shr 8
                        val b = input.readUnsignedShort() shr 8
                        colours[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    framebuffer.setColourMap(first, colours)
                    RfbServerMessage.ColourMapApplied
                }
                RfbServerMessageType.BELL -> RfbServerMessage.Bell
                RfbServerMessageType.SERVER_CUT_TEXT -> {
                    input.skipBytes(3)
                    val length = input.readInt()
                    if (length < 0 || length > RfbProtocol.MAX_CUT_TEXT_BYTES) {
                        throw RfbException("Clipboard text is out of range.")
                    }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    RfbServerMessage.ServerCutText(
                        String(bytes, Charsets.UTF_8)
                    )
                }
                else -> throw RfbException("Unknown server message type.")
            }
        } catch (e: EOFException) {
            close()
            throw RfbException("Connection closed by the server.")
        }
    }

    /** Send a pointer event (button mask bit 0 = left, 1 = middle, 2 = right). */
    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        output.write(RfbMessages.buildPointerEvent(buttonMask, x, y))
        output.flush()
    }

    /** Send a key press (down = true) or release, by X keysym. */
    fun sendKey(down: Boolean, keysym: Int) {
        output.write(RfbMessages.buildKeyEvent(down, keysym))
        output.flush()
    }

    /** Send clipboard text to the server. */
    fun sendCutText(text: String) {
        output.write(RfbMessages.buildClientCutText(text))
        output.flush()
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
            // Close is best-effort; it must not mask a prior error.
        }
    }
}

/**
 * RFB connector: TCP connect, version negotiation, security
 * handshake, initialisation, pixel-format and encoding setup.
 *
 * Authentication rules (fail-closed, no silent downgrade):
 * - The server's offered list (3.7/3.8) or single type (3.3)
 *   must contain a type this client can perform. VNC Auth is
 *   used when a password is supplied; None is used ONLY when
 *   [allowUnauthenticated] is true — a server offering only
 *   None to a caller that did not opt in fails, it does not
 *   silently connect unauthenticated.
 * - A server offering only VeNCrypt/TLS/Tight/X.509 fails:
 *   those types are not implemented here, and falling back to
 *   a weaker type the server did not offer would be a
 *   downgrade, not a negotiation.
 * - SecurityResult != 0 fails with a fixed message; the
 *   server's reason string is read and discarded, never shown
 *   (it is untrusted server text, and fixed messages keep the
 *   error surface predictable).
 * - The password is wiped in a finally block, whatever the
 *   outcome; the derived DES key is wiped as soon as the
 *   response is sent.
 *
 * All blocking socket I/O runs on Dispatchers.IO. Any failure
 * after socket creation closes the socket before propagating,
 * so a failed attempt never leaks a connection.
 */
object RfbConnector {

    suspend fun connect(
        hostname: String,
        port: Int = RfbProtocol.DEFAULT_PORT,
        password: CharArray?,
        allowUnauthenticated: Boolean,
        connectTimeoutMs: Int = 15_000,
        socketTimeoutMs: Int = 30_000
    ): RfbSession = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(hostname, port), connectTimeoutMs)
            socket.soTimeout = socketTimeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            try {
                handshake(socket, input, output, password, allowUnauthenticated)
            } catch (e: EOFException) {
                throw RfbException("Connection closed by the server.")
            }
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
                // Cleanup must not mask the original error.
            }
            // Fixed message: socket exception text is not secret,
            // but the project convention is one predictable error
            // surface, and callers only branch on the type.
            if (e is RfbException) throw e
            throw RfbException("Connection failed.")
        } finally {
            RfbAuth.wipe(password)
        }
    }

    private fun handshake(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        password: CharArray?,
        allowUnauthenticated: Boolean
    ): RfbSession {
        // 1. Version: server speaks first, 12 bytes exactly.
        val versionBytes = ByteArray(RfbProtocol.VERSION_LENGTH)
        input.readFully(versionBytes)
        val version = RfbProtocol.negotiateVersion(
            String(versionBytes, Charsets.US_ASCII)
        )
        output.write(version.wireString.toByteArray(Charsets.US_ASCII))
        output.flush()

        // 2. Security handshake, per the negotiated version.
        if (version.usesSecurityList) {
            val count = input.readUnsignedByte()
            if (count == 0) {
                discardReason(input)
                throw RfbException("Server refused the connection.")
            }
            val offered = IntArray(count) { input.readUnsignedByte() }
            val chosen = chooseSecurity(offered, password, allowUnauthenticated)
            output.writeByte(chosen)
            output.flush()
            authenticate(input, output, version, chosen, password)
        } else {
            val offered = input.readInt()
            if (offered == RfbSecurityType.INVALID) {
                discardReason(input)
                throw RfbException("Server refused the connection.")
            }
            val chosen = chooseSecurity(intArrayOf(offered), password, allowUnauthenticated)
            authenticate(input, output, version, chosen, password)
        }

        // 3. Initialisation: shared desktop, then ServerInit.
        output.writeByte(1)
        output.flush()
        val init = RfbMessages.parseServerInit(input)

        // 4. Negotiated format + encodings, then the session.
        output.write(RfbMessages.buildSetPixelFormat(RfbPixelFormat.REQUESTED))
        output.write(RfbMessages.buildSetEncodings(RfbEncoding.ADVERTISED))
        output.flush()
        val framebuffer = RfbFramebuffer(
            init.width, init.height, RfbPixelFormat.REQUESTED
        )
        // The socket is owned by the session from here on; the
        // caller must close it. (The streams wrap it, so closing
        // the socket closes them.)
        return RfbSession(
            socket = socket,
            input = input,
            output = output,
            negotiatedVersion = version,
            desktopName = init.desktopName,
            framebuffer = framebuffer
        )
    }

    /**
     * Pick the security type: VNC Auth when a password is
     * supplied and offered, else None only when explicitly
     * allowed. Anything else is a failure — including a server
     * that offers VNC Auth while no password was supplied (the
     * client cannot invent one) and a server that offers only
     * unimplemented types.
     */
    private fun chooseSecurity(
        offered: IntArray,
        password: CharArray?,
        allowUnauthenticated: Boolean
    ): Int {
        if (password != null && offered.contains(RfbSecurityType.VNC_AUTH)) {
            return RfbSecurityType.VNC_AUTH
        }
        if (allowUnauthenticated && offered.contains(RfbSecurityType.NONE)) {
            return RfbSecurityType.NONE
        }
        throw RfbException("No supported authentication method.")
    }

    /**
     * Run the chosen authentication and read SecurityResult —
     * always in 3.7/3.8, and in 3.3 only for VNC Auth (3.3 +
     * None has no SecurityResult; reading one there would eat
     * the first byte of ClientInit's reply, desynchronising
     * the stream).
     */
    private fun authenticate(
        input: DataInputStream,
        output: DataOutputStream,
        version: RfbVersion,
        chosen: Int,
        password: CharArray?
    ) {
        when (chosen) {
            RfbSecurityType.NONE -> {
                if (version != RfbVersion.V3_3) {
                    readSecurityResult(input)
                }
            }
            RfbSecurityType.VNC_AUTH -> {
                val challenge = ByteArray(RfbAuth.CHALLENGE_BYTES)
                input.readFully(challenge)
                // Encode without building an intermediate String:
                // a String copy of the password would linger
                // unwipable until GC (the prompt-04 residual).
                // The byte array below is wiped in the finally.
                val passwordBytes = if (password == null) {
                    ByteArray(0)
                } else {
                    val stream = java.io.ByteArrayOutputStream()
                    java.io.OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(password)
                    }
                    stream.toByteArray()
                }
                val key = RfbAuth.deriveKey(passwordBytes)
                try {
                    val response = RfbAuth.encryptChallenge(challenge, key)
                    output.write(response)
                    output.flush()
                } finally {
                    RfbAuth.wipe(key)
                    RfbAuth.wipe(passwordBytes)
                }
                readSecurityResult(input)
            }
            else -> throw RfbException("No supported authentication method.")
        }
    }

    private fun readSecurityResult(input: DataInputStream) {
        val result = input.readInt()
        if (result != 0) {
            discardReason(input)
            throw RfbException("Authentication failed.")
        }
    }

    /** Read and discard a server failure-reason string, if any. */
    private fun discardReason(input: DataInputStream) {
        try {
            val length = input.readInt()
            if (length in 1..1_048_576) {
                input.skipBytes(length)
            }
        } catch (_: Exception) {
            // The connection is already failed; a missing or
            // truncated reason must not replace the real error.
        }
    }

}
