package io.github.whywood.deepdeep.plugin.wecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomReleasesTest {
    @Test
    fun parsesRealRegistryMetadata() {
        val parsed = WecomReleases.parseRegistryRelease(wecomFixture("registry-latest.json"), now = 1_700_000_000_000L)
        assertTrue(parsed is WecomReleaseResult.Success)
        val release = (parsed as WecomReleaseResult.Success).release
        assertEquals("1.3.4", release.version)
        assertEquals(
            "https://registry.npmjs.org/@wecom/cli-linux-arm64/-/cli-linux-arm64-1.3.4.tgz",
            release.tarballUrl,
        )
        assertEquals(
            "BUvkJCMiM6aImK1MGjA6TuNslfay9eg/cOrmmvg3RLAlB9xTQUfqdq9YQxFog04wEpnlZSsgNXq6bWQaIvwX9w==",
            release.sha512,
        )
        assertEquals(1_700_000_000_000L, release.checkedAt)
    }

    @Test
    fun registryRejectsUnsafeVersionString() {
        val body = """{"version":"1.3.4'; rm -rf /","dist":{"tarball":"https://registry.npmjs.org/x.tgz","integrity":"sha512-3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw=="}}"""
        val parsed = WecomReleases.parseRegistryRelease(body, 0L)
        assertTrue(parsed is WecomReleaseResult.Failure)
        assertTrue((parsed as WecomReleaseResult.Failure).message.contains("版本号"))
    }

    @Test
    fun registryWithoutSha512IsRejected() {
        val stripped = wecomFixture("registry-latest.json")
            .replace(Regex("\"integrity\"\\s*:\\s*\"[^\"]*\","), "")
        assertFalse(stripped.contains("\"integrity\""))
        val parsed = WecomReleases.parseRegistryRelease(stripped, now = 0L)
        assertTrue(parsed is WecomReleaseResult.Failure)
        assertTrue((parsed as WecomReleaseResult.Failure).message.contains("sha512"))
    }

    @Test
    fun registryWithoutTarballOrVersionIsRejected() {
        assertTrue(WecomReleases.parseRegistryRelease("""{"version":"1.3.4","dist":{}}""", 0L) is WecomReleaseResult.Failure)
        assertTrue(WecomReleases.parseRegistryRelease("""{"dist":{"tarball":"https://x","integrity":"sha512-Zm9v"}}""", 0L) is WecomReleaseResult.Failure)
        assertTrue(WecomReleases.parseRegistryRelease("not json", 0L) is WecomReleaseResult.Failure)
        assertTrue(WecomReleases.parseRegistryRelease("{}", 0L) is WecomReleaseResult.Failure)
    }

    @Test
    fun integrityParsingIgnoresOtherAlgorithms() {
        assertEquals(
            "3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==",
            WecomReleases.parseSha512(
                "sha1-abc sha512-3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==",
            ),
        )
        assertNull(WecomReleases.parseSha512(null))
        assertNull(WecomReleases.parseSha512(""))
        assertNull(WecomReleases.parseSha512("sha256-abc"))
        assertNull(WecomReleases.parseSha512("sha512-notbase64!!"))
        assertNull(WecomReleases.parseSha512("sha512-YWJj"))
    }

    @Test
    fun versionComparisonDetectsUpdates() {
        assertTrue(WecomReleases.isNewer("1.3.5", "1.3.4"))
        assertTrue(WecomReleases.isNewer("1.4.0", "1.3.9"))
        assertTrue(WecomReleases.isNewer("2.0.0", "1.9.9"))
        assertTrue(WecomReleases.isNewer("1.3.4", null))
        assertFalse(WecomReleases.isNewer("1.3.4", "1.3.4"))
        assertFalse(WecomReleases.isNewer("1.3.4", "1.3.5"))
        assertTrue(WecomReleases.compareVersions("v1.3.10", "1.3.9") > 0)
        assertEquals(0, WecomReleases.compareVersions("1.3.4", "1.3.4"))
        assertEquals(0, WecomReleases.compareVersions("v1.3.4", "1.3.4"))
    }

    @Test
    fun downloadUrlsIncludeMirrorFallback() {
        val urls = WecomCli.releaseDownloadUrls(
            "https://registry.npmjs.org/@wecom/cli-linux-arm64/-/cli-linux-arm64-1.3.4.tgz",
            "1.3.4",
        )
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://registry.npmjs.org/"))
        assertTrue(urls[1].contains("registry.npmmirror.com"))
        assertEquals("wecom-cli-linux-arm64-1.3.4.tgz", WecomCli.archiveName("1.3.4"))
    }

    @Test
    fun registryReleaseMapsToSharedSha512Release() {
        val release = (WecomReleases.parseRegistryRelease(wecomFixture("registry-latest.json"), 0L) as WecomReleaseResult.Success).release
        val shared = WecomReleases.toCliRelease(release)
        assertEquals("1.3.4", shared.version)
        assertEquals("wecom-cli-linux-arm64-1.3.4.tgz", shared.archiveName)
        assertEquals(release.sha512, shared.sha512Base64)
        assertEquals("", shared.sha256)
        assertEquals(2, shared.downloadUrls.size)
        assertEquals(release.tarballUrl, shared.downloadUrls.first())
        val restored = WecomReleases.fromCheck(shared, checkedAt = release.checkedAt)
        assertEquals(release, restored)
    }

    @Test
    fun pinnedReleaseUsesSha256WithoutSha512() {
        val pinned = WecomReleases.pinnedRelease()
        assertEquals(WecomCli.VERSION, pinned.version)
        assertEquals(WecomCli.ARCHIVE_NAME, pinned.archiveName)
        assertEquals(WecomCli.ARCHIVE_SHA256, pinned.sha256)
        assertNull(pinned.sha512Base64)
        assertEquals(WecomCli.DOWNLOAD_URLS, pinned.downloadUrls)
        assertEquals(64, pinned.sha256.length)
    }
}
