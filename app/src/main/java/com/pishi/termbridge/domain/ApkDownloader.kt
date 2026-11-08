package com.pishi.termbridge.domain

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Outcome of one in-app update download ([ApkDownloader]). */
sealed interface ApkDownloadResult {
    /** The file is complete at the destination. [sha256Hex]
     *  is its digest; [digestVerified] is true only when an
     *  expected digest was supplied AND matched — a download
     *  without an expected digest is Done but unverified. */
    data class Done(
        val sha256Hex: String,
        val digestVerified: Boolean,
        val bytes: Long
    ) : ApkDownloadResult

    /** The stream exceeded the size cap; partial file deleted. */
    data object TooLarge : ApkDownloadResult

    /** The completed file's digest differs from the expected
     *  one; the file was deleted, never offered for install. */
    data object DigestMismatch : ApkDownloadResult

    /** Network or I/O failure; partial file deleted. */
    data object Failed : ApkDownloadResult
}

/**
 * Downloads a release APK to a local file, hashing as it
 * streams (in-app update, 0.1.3 — the owner asked for the
 * Anvex-style flow: download and install without leaving the
 * app). Plain HTTP(S) via HttpURLConnection, mirroring
 * [GitHubLatestReleaseFetcher]: the asset URL redirects to
 * GitHub's object storage, which the connection follows.
 *
 * Fail-closed throughout: any failure deletes the partial
 * file, and a digest mismatch deletes the completed one —
 * a bad download is never left where the installer could
 * pick it up.
 */
class ApkDownloader(
    private val maxBytes: Long = MAX_APK_BYTES
) {
    /**
     * Downloads [url] into [dest]. [onProgress] is called
     * after each chunk with (bytesSoFar, totalBytes), where
     * totalBytes is -1 when the server sent no length.
     * Blocking; call from an IO dispatcher.
     */
    fun download(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): ApkDownloadResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TermBridge-Android")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return ApkDownloadResult.Failed
            }
            val total = connection.contentLengthLong
            dest.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > maxBytes) {
                            runCatching { dest.delete() }
                            return ApkDownloadResult.TooLarge
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(count, total)
                    }
                }
            }
            // Manual hex (no String.format: its digits can
            // localize — the AUD-007 lesson).
            val sha256Hex = digest.digest().toHex()
            if (expectedSha256 != null && expectedSha256 != sha256Hex) {
                runCatching { dest.delete() }
                return ApkDownloadResult.DigestMismatch
            }
            ApkDownloadResult.Done(sha256Hex, expectedSha256 != null, count)
        } catch (_: IOException) {
            runCatching { dest.delete() }
            ApkDownloadResult.Failed
        } catch (_: RuntimeException) {
            runCatching { dest.delete() }
            ApkDownloadResult.Failed
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /** Release APKs are ~7 MB; 64 MB is a ceiling against
         *  a hostile or broken endpoint, not an expectation. */
        const val MAX_APK_BYTES: Long = 64L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (byte in this) {
        val v = byte.toInt() and 0xFF
        sb.append(digits[v shr 4])
        sb.append(digits[v and 0x0F])
    }
    return sb.toString()
}
