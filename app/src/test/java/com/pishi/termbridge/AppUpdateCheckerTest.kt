package com.pishi.termbridge

import com.pishi.termbridge.domain.AppUpdateChecker
import com.pishi.termbridge.domain.LatestReleaseFetcher
import com.pishi.termbridge.domain.UpdateCheckResult
import com.pishi.termbridge.domain.readCapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app update check: version comparison matrix and
 * `releases/latest` payload verdicts. The fixture mirrors
 * the REAL published shape: the signed-release workflow
 * creates the release from the version tag and attaches
 * exactly one asset, `TermBridge-release.apk`.
 */
class AppUpdateCheckerTest {

    // ---- Version comparison matrix ----

    @Test
    fun equalVersionsCompareZero() {
        assertEquals(0, AppUpdateChecker.compareVersions("0.1.0", "0.1.0"))
        assertEquals(0, AppUpdateChecker.compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertEquals(0, AppUpdateChecker.compareVersions("v0.1.0", "0.1.0"))
        assertEquals(0, AppUpdateChecker.compareVersions("0.1.0", "v0.1.0"))
        assertTrue(AppUpdateChecker.isNewer("0.1.0", "v0.2.0"))
    }

    @Test
    fun dashSuffixIsIgnored() {
        assertEquals(
            0,
            AppUpdateChecker.compareVersions("0.1.0-foundation", "0.1.0")
        )
        assertTrue(AppUpdateChecker.isNewer("0.1.0-foundation", "0.1.1"))
        assertFalse(AppUpdateChecker.isNewer("0.1.0-foundation", "0.1.0"))
    }

    @Test
    fun olderAndNewerOrderNumerically() {
        assertTrue(AppUpdateChecker.compareVersions("0.1.0", "0.2.0") < 0)
        assertTrue(AppUpdateChecker.compareVersions("0.2.0", "0.1.0") > 0)
        assertTrue(AppUpdateChecker.compareVersions("0.9.9", "0.10.0") < 0)
        assertTrue(AppUpdateChecker.isNewer("1.9.0", "2.0.0"))
        assertFalse(AppUpdateChecker.isNewer("2.0.0", "1.9.0"))
        assertFalse(AppUpdateChecker.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun missingComponentsCountAsZero() {
        assertEquals(0, AppUpdateChecker.compareVersions("1.0", "1.0.0"))
        assertEquals(0, AppUpdateChecker.compareVersions("1", "1.0.0"))
        assertTrue(AppUpdateChecker.compareVersions("1.0", "1.0.1") < 0)
        assertTrue(AppUpdateChecker.isNewer("1.0", "1.1"))
    }

    // ---- Payload verdicts ----

    /** Realistic `releases/latest` payload for tag [tag],
     *  shaped like the GitHub API response for the release
     *  the signed workflow publishes (one APK asset plus a
     *  checksum sidecar, to prove the APK is the one picked). */
    private fun releasePayload(tag: String): String = """
        {
          "url": "https://api.github.com/repos/PishiKuchulu/TermBridge/releases/1",
          "html_url": "https://github.com/PishiKuchulu/TermBridge/releases/tag/$tag",
          "tag_name": "$tag",
          "name": "TermBridge $tag",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "TermBridge-release.apk.sha256",
              "browser_download_url": "https://github.com/PishiKuchulu/TermBridge/releases/download/$tag/TermBridge-release.apk.sha256"
            },
            {
              "name": "TermBridge-release.apk",
              "content_type": "application/vnd.android.package-archive",
              "size": 12345678,
              "browser_download_url": "https://github.com/PishiKuchulu/TermBridge/releases/download/$tag/TermBridge-release.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun newerReleaseIsUpdateAvailableWithApkUrl() {
        val result = AppUpdateChecker.evaluate("0.1.0-foundation", releasePayload("v0.2.0"))
        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                tag = "v0.2.0",
                downloadUrl =
                    "https://github.com/PishiKuchulu/TermBridge/releases/download/v0.2.0/TermBridge-release.apk"
            ),
            result
        )
    }

    @Test
    fun assetDigestIsCarriedIntoUpdateAvailable() {
        val hex = "ab12cd34" + "ef56ab78".repeat(7)
        val payload = releasePayload("v0.2.0").replace(
            "\"content_type\": \"application/vnd.android.package-archive\",",
            "\"content_type\": \"application/vnd.android.package-archive\",\n" +
                "              \"digest\": \"sha256:${hex.uppercase()}\","
        )
        val result = AppUpdateChecker.evaluate("0.1.0-foundation", payload)
        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                tag = "v0.2.0",
                downloadUrl =
                    "https://github.com/PishiKuchulu/TermBridge/releases/download/v0.2.0/TermBridge-release.apk",
                expectedSha256 = hex
            ),
            result
        )
    }

    @Test
    fun missingDigestLeavesUpdateUnverified() {
        val result = AppUpdateChecker.evaluate(
            "0.1.0-foundation", releasePayload("v0.2.0")
        )
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals(
            null,
            (result as UpdateCheckResult.UpdateAvailable).expectedSha256
        )
    }

    @Test
    fun assetDigestParsingIsStrict() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, AppUpdateChecker.parseAssetDigest("sha256:$hex"))
        assertEquals(
            hex,
            AppUpdateChecker.parseAssetDigest("sha256:${hex.uppercase()}")
        )
        assertEquals(hex, AppUpdateChecker.parseAssetDigest(hex))
        assertEquals(null, AppUpdateChecker.parseAssetDigest(null))
        assertEquals(null, AppUpdateChecker.parseAssetDigest(""))
        assertEquals(null, AppUpdateChecker.parseAssetDigest("sha512:$hex"))
        assertEquals(null, AppUpdateChecker.parseAssetDigest("sha256:xyz"))
        assertEquals(null, AppUpdateChecker.parseAssetDigest("sha256:${hex.dropLast(1)}"))
    }

    @Test
    fun sameReleaseIsUpToDate() {
        assertEquals(
            UpdateCheckResult.UpToDate,
            AppUpdateChecker.evaluate("0.1.0-foundation", releasePayload("v0.1.0"))
        )
    }

    @Test
    fun olderReleaseIsUpToDate() {
        assertEquals(
            UpdateCheckResult.UpToDate,
            AppUpdateChecker.evaluate("0.3.0", releasePayload("v0.2.0"))
        )
    }

    @Test
    fun missingApkAssetFailsTheCheck() {
        val payload = """
            {
              "tag_name": "v9.9.9",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://example.com/source.zip"
                }
              ]
            }
        """.trimIndent()
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", payload)
        )
    }

    @Test
    fun missingTagFailsTheCheck() {
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", """{"assets": []}""")
        )
    }

    @Test
    fun malformedJsonFailsTheCheck() {
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", "this is not json")
        )
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", """{"tag_name": """)
        )
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", null)
        )
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", "")
        )
    }

    // ---- Fetcher seam ----

    @Test
    fun checkUsesTheFetcherPayload() {
        val fetcher = LatestReleaseFetcher { releasePayload("v1.0.0") }
        val result = AppUpdateChecker.check("0.1.0-foundation", fetcher)
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun nullPayloadFromFetcherFailsTheCheck() {
        val fetcher = LatestReleaseFetcher { null }
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.check("0.1.0", fetcher)
        )
    }

    @Test
    fun throwingFetcherFailsTheCheck() {
        val fetcher = LatestReleaseFetcher { throw RuntimeException("boom") }
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.check("0.1.0", fetcher)
        )
    }

    // ---- Audit #7 AUD-004: capped response read + depth guard ----

    @Test
    fun cappedReadReturnsShortBody() {
        val body = "{\"tag_name\":\"v9.9.9\"}"
        assertEquals(body, readCapped(body.byteInputStream(), 1024))
    }

    @Test
    fun cappedReadAcceptsExactlyTheCap() {
        val body = "x".repeat(1024)
        assertEquals(body, readCapped(body.byteInputStream(), 1024))
    }

    @Test
    fun cappedReadRejectsOversizedBody() {
        val body = "x".repeat(5000)
        assertEquals(null, readCapped(body.byteInputStream(), 1024))
    }

    @Test
    fun deeplyNestedPayloadFailsTheCheck() {
        val nested = "[".repeat(500) + "]".repeat(500)
        assertEquals(
            UpdateCheckResult.CheckFailed,
            AppUpdateChecker.evaluate("0.1.0", nested)
        )
    }
}
