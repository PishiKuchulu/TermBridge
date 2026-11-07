package com.pishi.termbridge

import com.pishi.termbridge.transport.SocksSocketFactory
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Audit #7 AUD-023: SocksSocketFactory had no tests at all.
 *
 * The factory hand-rolls no protocol: it builds JDK sockets on
 * a SOCKS proxy and the JDK performs the SOCKS5 handshake on
 * connect. What these tests pin is therefore (a) the wiring —
 * the no-arg socket sshj uses really goes through the
 * configured proxy when sshj connects it — and (b) the wire
 * integration, against a scripted loopback SOCKS5 peer in this
 * file: the greeting the JDK sends, the CONNECT request bytes
 * for the requested target, and the failure shapes (server
 * refusal, no acceptable auth method) surfacing as
 * [IOException] instead of a hung or half-open socket.
 *
 * Honest scope: the peer is scripted, so this proves the
 * factory + JDK handshake against controlled bytes, not
 * interop with a production SOCKS server.
 */
class SocksSocketFactoryTest {

    /** A one-shot scripted SOCKS5 peer on loopback. */
    private class FakeSocksServer(
        private val connectReplyCode: Int = 0,
        private val acceptNoAuth: Boolean = true
    ) {
        val serverSocket =
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        @Volatile var greeting: ByteArray? = null
        @Volatile var connectRequest: ByteArray? = null
        @Volatile var failure: Throwable? = null

        private val thread = Thread {
            try {
                serverSocket.soTimeout = 10_000
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    // Greeting: VER, NMETHODS, methods...
                    val head = readFully(input, 2)
                    val methods = readFully(input, head[1].toInt())
                    greeting = head + methods
                    if (!acceptNoAuth || !methods.contains(0)) {
                        output.write(byteArrayOf(5, 0xFF.toByte()))
                        output.flush()
                        return@use
                    }
                    output.write(byteArrayOf(5, 0))
                    output.flush()
                    // CONNECT: VER, CMD, RSV, ATYP, addr, port.
                    val reqHead = readFully(input, 4)
                    val addrLen = when (reqHead[3].toInt()) {
                        1 -> 4
                        4 -> 16
                        3 -> readFully(input, 1)[0].toInt()
                        else -> 0
                    }
                    connectRequest =
                        reqHead + readFully(input, addrLen + 2)
                    output.write(
                        byteArrayOf(
                            5, connectReplyCode.toByte(), 0, 1,
                            0, 0, 0, 0, 0, 0
                        )
                    )
                    output.flush()
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                runCatching { serverSocket.close() }
            }
        }.apply { isDaemon = true }

        fun start(): FakeSocksServer = apply { thread.start() }

        fun await() {
            thread.join(12_000)
            assertFalse("fake SOCKS peer did not finish", thread.isAlive)
            failure?.let { throw AssertionError("fake SOCKS peer failed", it) }
        }

        private fun readFully(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) throw IOException("EOF after $read of $n bytes")
                read += r
            }
            return buf
        }
    }

    @Test(timeout = 20_000)
    fun noArgSocketConnectsThroughTheProxyLikeSshjUsesIt() {
        // sshj's pattern (see the factory's class doc): take
        // the no-arg socket, then connect() it to the target —
        // the proxy handshake must happen inside that connect.
        // (Socket.proxy() cannot assert the wiring instead: it
        // does not exist in Android's java.net.Socket.)
        val server = FakeSocksServer().start()
        val socket = SocksSocketFactory("127.0.0.1", server.port)
            .createSocket()
        try {
            assertFalse(socket.isConnected)
            socket.connect(InetSocketAddress("127.0.0.1", 2222))
            assertTrue(socket.isConnected)
        } finally {
            socket.close()
        }
        server.await()
        assertArrayEquals(
            byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0x08, 0xAE.toByte()),
            server.connectRequest
        )
    }

    @Test(timeout = 20_000)
    fun connectSendsGreetingAndConnectForTheTarget() {
        val server = FakeSocksServer().start()
        val socket = SocksSocketFactory("127.0.0.1", server.port)
            .createSocket("127.0.0.1", 2222)
        try {
            assertTrue(socket.isConnected)
        } finally {
            socket.close()
        }
        server.await()
        val greeting = server.greeting!!
        assertEquals(5, greeting[0].toInt())
        assertEquals(greeting.size - 2, greeting[1].toInt())
        assertTrue(
            "no-auth must be offered",
            greeting.drop(2).contains(0.toByte())
        )
        assertArrayEquals(
            byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0x08, 0xAE.toByte()),
            server.connectRequest
        )
    }

    @Test(timeout = 20_000)
    fun connectRefusalSurfacesAsIOException() {
        val server = FakeSocksServer(connectReplyCode = 5).start()
        try {
            SocksSocketFactory("127.0.0.1", server.port)
                .createSocket("127.0.0.1", 2222)
            fail("a refused CONNECT must throw")
        } catch (expected: IOException) {
            // The JDK maps a non-zero REP to a SocketException.
        }
        server.await()
    }

    @Test(timeout = 20_000)
    fun noAcceptableAuthMethodSurfacesAsIOException() {
        val server = FakeSocksServer(acceptNoAuth = false).start()
        try {
            SocksSocketFactory("127.0.0.1", server.port)
                .createSocket("127.0.0.1", 2222)
            fail("a 0xFF method reply must throw")
        } catch (expected: IOException) {
            // No usable authentication method: connect fails.
        }
        server.await()
    }
}
