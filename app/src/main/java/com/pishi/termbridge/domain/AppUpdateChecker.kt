package com.pishi.termbridge.domain

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-app update check (manual only — nothing here runs in the
 * background): asks GitHub for the newest published release of
 * this app and compares its tag with the running version.
 *
 * The signed-release workflow publishes exactly one APK asset
 * per release, named [APK_ASSET_NAME]; the check looks for
 * that asset and hands its download URL back, so the UI can
 * offer it as a plain browser download. Installing stays a
 * system flow — the app holds no install permission and never
 * downloads the APK itself.
 *
 * The repository is public, so the unauthenticated call
 * needs no credential. Any failure along the way (offline,
 * HTTP error, oversized or malformed payload) is the honest
 * [CheckFailed] ("check unavailable") — never a crash and
 * never a fake "up to date" (audit #7 AUD-014).
 */
sealed interface UpdateCheckResult {
    /** A strictly newer release exists; [tag] is its raw tag
     *  (e.g. "v0.2.0") and [downloadUrl] the APK asset URL. */
    data class UpdateAvailable(val tag: String, val downloadUrl: String) :
        UpdateCheckResult

    /** The running version is at least as new as the newest
     *  published release. */
    data object UpToDate : UpdateCheckResult

    /** No verdict could be reached (offline, HTTP error,
     *  malformed or unexpected payload, missing asset). */
    data object CheckFailed : UpdateCheckResult
}

/**
 * The network seam: returns the `releases/latest` response
 * body on HTTP 200, and null on any failure (non-200 status,
 * timeout, I/O error). Implementations must not throw.
 */
fun interface LatestReleaseFetcher {
    fun fetchLatestReleaseJson(): String?
}

/** Production fetcher against the GitHub API. */
class GitHubLatestReleaseFetcher(
    private val endpoint: String = LATEST_RELEASE_URL
) : LatestReleaseFetcher {
    override fun fetchLatestReleaseJson(): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TermBridge-Android")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                // Audit #7 AUD-004: capped read — the response
                // size is whatever the server (or a redirect
                // target) says it is, so it is never trusted.
                connection.inputStream.use { input ->
                    readCapped(input, MAX_RESPONSE_BYTES)
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/PishiKuchulu/TermBridge/releases/latest"

        /** A releases/latest body is tens of KB; 256 KB is a
         *  generous ceiling, not an expectation. */
        const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val TIMEOUT_MS = 8_000
    }
}

/**
 * Reads [input] to EOF as UTF-8 text, returning null when the
 * stream carries more than [maxBytes] (audit #7 AUD-004): the
 * caller treats null as a failed fetch, so an endless or
 * oversized response degrades to "check unavailable" instead
 * of exhausting memory. The stream is NOT closed here; the
 * caller owns it.
 */
fun readCapped(input: InputStream, maxBytes: Int): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

object AppUpdateChecker {
    /** The one APK asset name the signed-release workflow publishes. */
    const val APK_ASSET_NAME = "TermBridge-release.apk"

    /**
     * Normalizes a version or tag to its numeric components:
     * a leading "v" is dropped, any "-suffix" (e.g.
     * "-foundation") or "+build" part is dropped, and
     * non-numeric components count as 0. "v0.1.0-foundation"
     * and "0.1" both become comparable component lists.
     */
    fun normalizeVersion(raw: String): List<Int> {
        val core = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (core.isBlank()) return emptyList()
        return core.split('.').map { part -> part.trim().toIntOrNull() ?: 0 }
    }

    /** Semver-style comparison of two version strings:
     *  negative when [first] is older, 0 when equal (missing
     *  components count as 0), positive when [first] is newer. */
    fun compareVersions(first: String, second: String): Int {
        val a = normalizeVersion(first)
        val b = normalizeVersion(second)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** True when [candidate] is strictly newer than [current]. */
    fun isNewer(current: String, candidate: String): Boolean =
        compareVersions(candidate, current) > 0

    /**
     * Pure verdict for one payload: null or unparseable input,
     * a missing tag, or a payload without the APK asset all
     * yield [UpdateCheckResult.CheckFailed]; otherwise the tag
     * is compared against [currentVersion].
     */
    fun evaluate(currentVersion: String, payloadJson: String?): UpdateCheckResult {
        if (payloadJson.isNullOrBlank()) return UpdateCheckResult.CheckFailed
        // Audit #7 AUD-004 follow-through: even a size-capped
        // payload can nest deeply, so the AUD-001 depth guard
        // runs here too, before the parser recurses.
        if (JsonDepth.exceeds(payloadJson, MAX_JSON_DEPTH)) {
            return UpdateCheckResult.CheckFailed
        }
        val root: JsonObject = try {
            Json.parseToJsonElement(payloadJson).jsonObject
        } catch (_: StackOverflowError) {
            return UpdateCheckResult.CheckFailed
        } catch (_: Exception) {
            return UpdateCheckResult.CheckFailed
        }
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return UpdateCheckResult.CheckFailed
        val assets = try {
            root["assets"]?.jsonArray
        } catch (_: Exception) {
            null
        } ?: return UpdateCheckResult.CheckFailed
        val apk = assets.firstOrNull { element ->
            element.jsonObject["name"]?.jsonPrimitive?.contentOrNull == APK_ASSET_NAME
        } ?: return UpdateCheckResult.CheckFailed
        val downloadUrl = apk.jsonObject["browser_download_url"]
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return UpdateCheckResult.CheckFailed
        return if (isNewer(currentVersion, tag)) {
            UpdateCheckResult.UpdateAvailable(tag, downloadUrl)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** Runs one check through [fetcher]; a misbehaving
     *  fetcher degrades to [UpdateCheckResult.CheckFailed]. */
    fun check(currentVersion: String, fetcher: LatestReleaseFetcher): UpdateCheckResult {
        val payload = try {
            fetcher.fetchLatestReleaseJson()
        } catch (_: Exception) {
            null
        }
        return evaluate(currentVersion, payload)
    }
}
