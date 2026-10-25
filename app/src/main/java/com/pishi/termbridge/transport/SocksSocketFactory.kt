package com.pishi.termbridge.transport

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * Prompt 10 SOCKS proxy socket factory. sshj's SocketClient calls
 * the no-arg createSocket() and then connects the returned socket
 * itself (verified against the 0.40.0 bytecode: connect() uses
 * socketFactory.createSocket() and then socket.connect with the
 * configured timeout). A Socket built with a SOCKS Proxy performs
 * the SOCKS handshake in the JDK on connect — no hand-rolled
 * protocol code, and no sshj proxy API is needed because none
 * exists in 0.40.0.
 *
 * Limits, stated plainly: unauthenticated SOCKS only (see
 * ProxyConfig — authenticated SOCKS would need the JVM-global
 * Authenticator). No layered-socket handling: plain
 * SocketFactory defines no layered createSocket overload, and
 * sshj never asks for one on this path.
 */
class SocksSocketFactory(proxyHost: String, proxyPort: Int) : SocketFactory() {
    private val proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress(proxyHost, proxyPort)
    )

    override fun createSocket(): Socket = Socket(proxy)

    override fun createSocket(host: String, port: Int): Socket =
        Socket(proxy).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = Socket(proxy).apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(address: InetAddress, port: Int): Socket =
        Socket(proxy).apply { connect(InetSocketAddress(address, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = Socket(proxy).apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}
