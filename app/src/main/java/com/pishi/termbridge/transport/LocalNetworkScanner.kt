package com.pishi.termbridge.transport

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A device that answered on one of the probed service ports. */
data class DiscoveredDevice(val address: String, val ports: Set<Int>)

/**
 * Looks for open TCP service ports on a CALLER-SUPPLIED list of local
 * addresses (the caller derives it from the phone's own subnet,
 * capped at a /24). A plain connect with a short timeout: no payload
 * is ever sent and nothing leaves the local network.
 */
class LocalNetworkScanner(
    private val connectTimeoutMs: Int = 400,
    private val parallelism: Int = 48
) {
    suspend fun scan(
        addresses: List<String>,
        ports: List<Int>,
        onProgress: (done: Int, total: Int) -> Unit,
        onFound: (address: String, port: Int) -> Unit
    ) {
        val gate = Semaphore(parallelism)
        val total = addresses.size * ports.size
        var done = 0
        coroutineScope {
            addresses.flatMap { address ->
                ports.map { port ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val open = probe(address, port)
                            synchronized(this@LocalNetworkScanner) { done += 1 }
                            if (open) onFound(address, port)
                            onProgress(synchronized(this@LocalNetworkScanner) { done }, total)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun probe(address: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
