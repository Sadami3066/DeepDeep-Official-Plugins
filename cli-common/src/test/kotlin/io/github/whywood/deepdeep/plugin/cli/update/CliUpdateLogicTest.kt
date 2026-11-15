package io.github.whywood.deepdeep.plugin.cli.update

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliVersionsTest {
    @Test
    fun normalizesTagStyleVersions() {
        assertEquals("1.0.100", CliVersions.normalize("v1.0.100"))
        assertEquals("1.0.100", CliVersions.normalize(" 1.0.100 "))
        assertNull(CliVersions.normalize("1.0"))
        assertNull(CliVersions.normalize("1.0.100.1"))
        assertNull(CliVersions.normalize("latest"))
    }

    @Test
    fun comparesNumerically() {
        assertTrue(CliVersions.isNewer("1.0.100", "1.0.98"))
        assertFalse(CliVersions.isNewer("1.0.98", "1.0.100"))
        assertEquals(0, CliVersions.compare("1.0.100", "1.0.100"))
        assertTrue(CliVersions.compare("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun picksLatestCandidate() {
        assertEquals("1.0.100", CliVersions.latest(listOf("1.0.98", "1.0.100", "1.0.99")))
        assertNull(CliVersions.latest(listOf("latest", "not-a-version")))
    }
}

class CliChecksumsTest {
    private val checksums = """
        7f5d442e5da3ae62b03a5b552880b651637b16b2eed91b588afaca6d72a6643a  cli-1.0.100-darwin-amd64.tar.gz
        166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d  cli-1.0.100-linux-arm64.tar.gz
    """.trimIndent()

    @Test
    fun parsesChecksumsAndFindsDigest() {
        val parsed = CliChecksums.parse(checksums)
        assertEquals(2, parsed.size)
        assertEquals(
            "166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d",
            CliChecksums.digestOf(parsed, "cli-1.0.100-linux-arm64.tar.gz"),
        )
        assertNull(CliChecksums.digestOf(parsed, "cli-1.0.100-linux-riscv64.tar.gz"))
    }

    @Test
    fun acceptsStarPrefixUppercaseAndRejectsBadDigests() {
        val parsed = CliChecksums.parse(
            "166E95FFAEC34F82123F50316480250329884C09E71A6F92313047E2C5C3FA5D *cli-linux.tar.gz",
        )
        assertEquals(
            "166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d",
            CliChecksums.digestOf(parsed, "cli-linux.tar.gz"),
        )
        assertTrue(CliChecksums.parse("zzzz  cli-linux.tar.gz").isEmpty())
        assertTrue(CliChecksums.parse("abc  cli-linux.tar.gz").isEmpty())
        assertFalse(CliChecksums.isDigest("abc"))
    }

    @Test
    fun supportsSha512Length() {
        val digest = "a".repeat(128)
        val parsed = CliChecksums.parse("$digest  package.tgz", CliChecksums.SHA512_LENGTH)
        assertEquals(digest, CliChecksums.digestOf(parsed, "package.tgz", CliChecksums.SHA512_LENGTH))
        assertTrue(CliChecksums.parse(digest + "  package.tgz").isEmpty())
    }
}

class CliReleasePolicyTest {
    private val policy = CliReleasePolicy(
        archiveNameFor = { "lark-cli-$it-linux-arm64.tar.gz" },
        downloadUrlsFor = { listOf("https://mirror/$it/archive", "https://github/$it/archive") },
        checksumsUrlsFor = { listOf("https://mirror/$it/checksums.txt") },
    )

    @Test
    fun buildsReleaseFromChecksums() {
        val release = policy.release(
            "v1.0.100",
            CliChecksums.parse("166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d  lark-cli-1.0.100-linux-arm64.tar.gz"),
        )
        assertNotNull(release)
        assertEquals("1.0.100", release!!.version)
        assertEquals("lark-cli-1.0.100-linux-arm64.tar.gz", release.archiveName)
        assertEquals(listOf("https://mirror/1.0.100/archive", "https://github/1.0.100/archive"), release.downloadUrls)
    }

    @Test
    fun buildsReleaseWithSha512Integrity() {
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(ByteArray(64) { 7 }))
        val release = policy.releaseWithSha512("v1.0.100", digest)
        assertNotNull(release)
        assertEquals("1.0.100", release!!.version)
        assertEquals("", release.sha256)
        assertEquals(digest, release.sha512Base64)
        assertTrue(release.downloadUrls.first().endsWith("/1.0.100/archive"))
        assertNull(policy.releaseWithSha512("1.0.100", "not-base64"))
        assertNull(policy.releaseWithSha512("1.0", digest))
    }

    @Test
    fun rejectsMissingArchiveOrBadVersion() {
        val onlyOtherPlatform = CliChecksums.parse(
            "166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d  lark-cli-1.0.100-darwin-arm64.tar.gz",
        )
        assertNull(policy.release("1.0.100", onlyOtherPlatform))
        assertNull(policy.release("1.0", CliChecksums.parse("166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d  x")))
        assertNull(policy.release("1.0.100", "not-a-digest"))
    }
}

class CliUpdateThrottleTest {
    @Test
    fun throttlesFor24Hours() {
        val now = 1_000_000_000_000L
        assertTrue(CliUpdateThrottle.shouldCheck(0, now))
        assertFalse(CliUpdateThrottle.shouldCheck(now - CliUpdateThrottle.DEFAULT_INTERVAL_MS + 1, now))
        assertTrue(CliUpdateThrottle.shouldCheck(now - CliUpdateThrottle.DEFAULT_INTERVAL_MS, now))
        assertTrue(CliUpdateThrottle.shouldCheck(now - 1000, now, intervalMs = 1000))
    }
}
