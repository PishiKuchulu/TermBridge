package com.pishi.termbridge

import com.pishi.termbridge.domain.ApkDownloadResult
import com.pishi.termbridge.domain.ApkDownloader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app update downloads (0.1.3): the downloader against a
 * scripted loopback HTTP peer — status handling, the size
 * cap, and the fail-closed digest check (a mismatch deletes
 * the file and is never offered for install).
 */
class ApkDownloaderTest {

    /** Serves one canned HTTP response, then closes. */
    private class HttpPeer(
        private val statusLine: String,
        private val body: ByteArray
    ) {
        val serverSocket =
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        @Volatile var failure: Throwable? = null

        private val thread = Thread {
            try {
                serverSocket.soTimeout = 10_000
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    // Consume the request head (up to CRLFCRLF).
                    val expect =
                        intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)
                    var matched = 0
                    while (matched < 4) {
                        val b = input.read()
                        if (b < 0) break
                        matched =
                            if (b == expect[matched]) matched + 1 else 0
                    }
                    val head = "HTTP/1.1 $statusLine\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    val output = socket.getOutputStream()
                    output.write(head.toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                runCatching { serverSocket.close() }
            }
        }.apply { isDaemon = true }

        fun start(): HttpPeer = apply { thread.start() }

        fun await() {
            thread.join(12_000)
            assertFalse("HTTP peer did not finish", thread.isAlive)
            failure?.let { throw AssertionError("HTTP peer failed", it) }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun tempDest(): File =
        File.createTempFile("tb-update-test", ".apk").apply { delete() }

    @Test(timeout = 20_000)
    fun downloadCompletesAndVerifiesDigest() {
        val body = "fake-apk-bytes-for-test".toByteArray()
        val peer = HttpPeer("200 OK", body).start()
        val dest = tempDest()
        var lastProgress = -1L
        val result = ApkDownloader().download(
            "http://127.0.0.1:${peer.port}/TermBridge-release.apk",
            dest,
            expectedSha256 = sha256Hex(body),
            onProgress = { downloaded, _ -> lastProgress = downloaded }
        )
        peer.await()
        assertEquals(
            ApkDownloadResult.Done(sha256Hex(body), true, body.size.toLong()),
            result
        )
        assertTrue(dest.exists())
        assertTrue(body.contentEquals(dest.readBytes()))
        assertEquals(body.size.toLong(), lastProgress)
        dest.delete()
    }

    @Test(timeout = 20_000)
    fun downloadWithoutDigestIsDoneButUnverified() {
        val body = "unverified-body".toByteArray()
        val peer = HttpPeer("200 OK", body).start()
        val dest = tempDest()
        val result = ApkDownloader().download(
            "http://127.0.0.1:${peer.port}/x.apk", dest, expectedSha256 = null
        )
        peer.await()
        assertEquals(
            ApkDownloadResult.Done(sha256Hex(body), false, body.size.toLong()),
            result
        )
        dest.delete()
    }

    @Test(timeout = 20_000)
    fun digestMismatchDeletesTheFile() {
        val body = "tampered-body".toByteArray()
        val peer = HttpPeer("200 OK", body).start()
        val dest = tempDest()
        val result = ApkDownloader().download(
            "http://127.0.0.1:${peer.port}/x.apk",
            dest,
            expectedSha256 = sha256Hex("other-bytes".toByteArray())
        )
        peer.await()
        assertEquals(ApkDownloadResult.DigestMismatch, result)
        assertFalse("mismatched file must be deleted", dest.exists())
    }

    @Test(timeout = 20_000)
    fun oversizedDownloadIsCappedAndDeleted() {
        val body = "0123456789abcdef".toByteArray()
        val peer = HttpPeer("200 OK", body).start()
        val dest = tempDest()
        val result = ApkDownloader(maxBytes = 8).download(
            "http://127.0.0.1:${peer.port}/x.apk", dest, expectedSha256 = null
        )
        peer.await()
        assertEquals(ApkDownloadResult.TooLarge, result)
        assertFalse("partial file must be deleted", dest.exists())
    }

    @Test(timeout = 20_000)
    fun httpErrorIsFailedNotPartial() {
        val peer = HttpPeer("404 Not Found", ByteArray(0)).start()
        val dest = tempDest()
        val result = ApkDownloader().download(
            "http://127.0.0.1:${peer.port}/x.apk", dest, expectedSha256 = null
        )
        peer.await()
        assertEquals(ApkDownloadResult.Failed, result)
        assertFalse(dest.exists())
    }
}
