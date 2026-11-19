package io.github.whywood.superpi.plugin.feishu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuUpdateTest {
    private val checksums = """
        7f5d442e5da3ae62b03a5b552880b651637b16b2eed91b588afaca6d72a6643a  lark-cli-1.0.100-darwin-amd64.tar.gz
        d068a27f608335016a8a7fb1b3cd1fccfb96bda92075a027e2bf1c4c23134e30  lark-cli-1.0.100-darwin-arm64.tar.gz
        cd7363f731c9e5dd2becd909837922cffea8aad229d581a84eb13c6aa0ce6808  lark-cli-1.0.100-linux-amd64.tar.gz
        166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d  lark-cli-1.0.100-linux-arm64.tar.gz
        44ac87c24dab31c840abf0c7fe7cd7ad5b88b5b72b876c5fac866527b0d48022  lark-cli-1.0.100-linux-riscv64.tar.gz
    """.trimIndent()

    @Test
    fun pinnedReleaseMatchesPublishedChecksums() {
        val release = FeishuUpdate.pinnedRelease()
        assertEquals("1.0.100", release.version)
        assertEquals("lark-cli-1.0.100-linux-arm64.tar.gz", release.archiveName)
        assertEquals("166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d", release.sha256)
        assertEquals(2, release.downloadUrls.size)
        assertTrue(release.downloadUrls.any { it.startsWith("https://registry.npmmirror.com/") })
        assertTrue(release.downloadUrls.any { it.startsWith("https://github.com/") })
        assertTrue(release.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(FeishuUpdate.isNewer("1.0.100", "1.0.98"))
        assertFalse(FeishuUpdate.isNewer("1.0.98", "1.0.100"))
        assertTrue(FeishuUpdate.isNewer("1.1.0", "1.0.100"))
        assertEquals(0, FeishuUpdate.compareVersions("1.0.100", "1.0.100"))
        assertTrue(FeishuUpdate.compareVersions("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun parsesGithubLatestTag() {
        assertEquals("1.0.100", FeishuUpdate.parseGithubLatestVersion("""{"tag_name":"v1.0.100","draft":false}"""))
        assertEquals("1.0.100", FeishuUpdate.parseGithubLatestVersion("""{"tag_name":"1.0.100"}"""))
        assertNull(FeishuUpdate.parseGithubLatestVersion("""{"message":"API rate limit exceeded"}"""))
        assertNull(FeishuUpdate.parseGithubLatestVersion("not json"))
        assertNull(FeishuUpdate.parseGithubLatestVersion("""{"tag_name":"v1.0"}"""))
    }

    @Test
    fun parsesNpmmirrorListingAndPicksLatest() {
        val body = """
            [
              {"name":"v1.0.98/","type":"dir"},
              {"name":"v1.0.100/","type":"dir"},
              {"name":"v1.0.99/","type":"dir"},
              {"name":"checksums.txt","type":"file"}
            ]
        """.trimIndent()
        val versions = FeishuUpdate.parseNpmmirrorVersions(body)
        assertEquals(listOf("1.0.98", "1.0.100", "1.0.99"), versions)
        assertEquals("1.0.100", FeishuUpdate.latestVersion(versions))
        assertTrue(FeishuUpdate.parseNpmmirrorVersions("""{"ok":false}""").isEmpty())
        assertTrue(FeishuUpdate.parseNpmmirrorVersions("not json").isEmpty())
    }

    @Test
    fun parsesChecksumsAndFindsArm64Digest() {
        val parsed = FeishuUpdate.parseChecksums(checksums)
        assertEquals(5, parsed.size)
        val release = FeishuUpdate.releaseFromChecksums("1.0.100", parsed)
        assertNotNull(release)
        assertEquals("166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d", release?.sha256)
        assertEquals(
            "https://registry.npmmirror.com/-/binary/lark-cli/v1.0.100/lark-cli-1.0.100-linux-arm64.tar.gz",
            release?.downloadUrls?.first(),
        )
        assertEquals(
            "https://github.com/larksuite/cli/releases/download/v1.0.100/lark-cli-1.0.100-linux-arm64.tar.gz",
            release?.downloadUrls?.last(),
        )
    }

    @Test
    fun rejectsChecksumsWithoutArm64AssetOrWithBadDigest() {
        val darwinOnly = "d068a27f608335016a8a7fb1b3cd1fccfb96bda92075a027e2bf1c4c23134e30  lark-cli-1.0.100-darwin-arm64.tar.gz"
        assertNull(FeishuUpdate.releaseFromChecksums("1.0.100", FeishuUpdate.parseChecksums(darwinOnly)))
        val badDigest = "zzzz95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d  lark-cli-1.0.100-linux-arm64.tar.gz"
        assertNull(FeishuUpdate.releaseFromChecksums("1.0.100", FeishuUpdate.parseChecksums(badDigest)))
        val shortDigest = "abc  lark-cli-1.0.100-linux-arm64.tar.gz"
        assertNull(FeishuUpdate.releaseFromChecksums("1.0.100", FeishuUpdate.parseChecksums(shortDigest)))
        assertNull(FeishuUpdate.releaseFromChecksums("1.0", FeishuUpdate.parseChecksums(checksums)))
        assertNotNull(FeishuUpdate.releaseFromChecksums("v1.0.100", FeishuUpdate.parseChecksums(checksums)))
    }

    @Test
    fun checksumsAcceptStarPrefixAndUppercase() {
        val text = "166E95FFAEC34F82123F50316480250329884C09E71A6F92313047E2C5C3FA5D *lark-cli-1.0.100-linux-arm64.tar.gz"
        val release = FeishuUpdate.releaseFromChecksums("1.0.100", FeishuUpdate.parseChecksums(text))
        assertEquals("166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d", release?.sha256)
    }

    @Test
    fun throttlesChecksForTwentyFourHours() {
        val now = 1_000_000_000_000L
        assertTrue(FeishuUpdate.shouldCheck(0, now))
        assertFalse(FeishuUpdate.shouldCheck(now - FeishuUpdate.CHECK_INTERVAL_MS + 1, now))
        assertTrue(FeishuUpdate.shouldCheck(now - FeishuUpdate.CHECK_INTERVAL_MS, now))
    }

    @Test
    fun statusReflectsAvailabilityAndFailures() {
        assertEquals(
            FeishuUpdateStatus.NOT_INSTALLED,
            FeishuUpdate.statusTextKey(null, "1.0.100", false, false, "1.0.100"),
        )
        assertEquals(
            FeishuUpdateStatus.CHECKING,
            FeishuUpdate.statusTextKey("1.0.98", null, true, false, "1.0.100"),
        )
        assertEquals(
            FeishuUpdateStatus.AVAILABLE,
            FeishuUpdate.statusTextKey("1.0.98", "1.0.100", false, false, "1.0.100"),
        )
        assertEquals(
            FeishuUpdateStatus.LATEST,
            FeishuUpdate.statusTextKey("1.0.100", "1.0.100", false, false, "1.0.100"),
        )
        assertEquals(
            FeishuUpdateStatus.FAILED,
            FeishuUpdate.statusTextKey("1.0.100", null, false, true, "1.0.100"),
        )
        assertEquals(
            FeishuUpdateStatus.AVAILABLE,
            FeishuUpdate.statusTextKey("1.0.98", null, false, false, "1.0.100"),
        )
    }

    @Test
    fun githubTagVersionIsUsedForArchiveAndChecksumUrls() {
        assertTrue(FeishuUpdate.checksumsUrls("1.0.100").first().endsWith("/v1.0.100/checksums.txt"))
        assertTrue(FeishuUpdate.checksumsUrls("1.0.100").last().startsWith("https://github.com/larksuite/cli/releases/download/"))
        assertEquals("lark-cli-1.0.101-linux-arm64.tar.gz", FeishuUpdate.archiveName("1.0.101"))
    }
}
