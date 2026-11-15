package io.github.whywood.deepdeep.plugin.feishu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class FeishuRelease(
    val version: String,
    val archiveName: String,
    val downloadUrls: List<String>,
    val sha256: String,
)

internal object FeishuUpdate {
    const val GITHUB_LATEST_URL = "https://api.github.com/repos/larksuite/cli/releases/latest"
    const val GITHUB_RELEASE_URL = "https://github.com/larksuite/cli/releases/download"
    const val NPMMIRROR_INDEX_URL = "https://registry.npmmirror.com/-/binary/lark-cli/"
    const val NPMMIRROR_RELEASE_URL = "https://registry.npmmirror.com/-/binary/lark-cli"
    const val CHECKSUMS_FILE = "checksums.txt"
    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    const val ARCHIVE_MAX_BYTES = 512L * 1024L * 1024L

    private val VERSION_PATTERN = Regex("""^(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")
    private val CHECKSUM_PATTERN = Regex("""^([0-9a-fA-F]{64})\s+\*?(\S+)$""")

    fun archiveName(version: String): String = "lark-cli-$version-linux-arm64.tar.gz"

    fun checksumsUrls(version: String): List<String> = listOf(
        "$NPMMIRROR_RELEASE_URL/v$version/$CHECKSUMS_FILE",
        "$GITHUB_RELEASE_URL/v$version/$CHECKSUMS_FILE",
    )

    fun archiveUrls(version: String): List<String> = listOf(
        "$NPMMIRROR_RELEASE_URL/v$version/${archiveName(version)}",
        "$GITHUB_RELEASE_URL/v$version/${archiveName(version)}",
    )

    fun release(version: String, sha256: String): FeishuRelease? {
        val normalized = normalizeVersion(version) ?: return null
        val digest = sha256.lowercase()
        if (!digest.matches(Regex("""^[0-9a-f]{64}$"""))) return null
        return FeishuRelease(
            version = normalized,
            archiveName = archiveName(normalized),
            downloadUrls = archiveUrls(normalized),
            sha256 = digest,
        )
    }

    fun pinnedRelease(): FeishuRelease = FeishuRelease(
        version = FEISHU_VERSION,
        archiveName = ARCHIVE_NAME,
        downloadUrls = DOWNLOAD_URLS,
        sha256 = ARCHIVE_SHA256,
    )

    fun normalizeVersion(raw: String): String? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        return if (VERSION_PATTERN.matches(trimmed)) trimmed else null
    }

    fun compareVersions(left: String, right: String): Int {
        val a = normalizeVersion(left)?.split('.')?.map { it.toInt() } ?: return left.compareTo(right)
        val b = normalizeVersion(right)?.split('.')?.map { it.toInt() } ?: return left.compareTo(right)
        for (index in 0 until 3) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

    fun parseGithubLatestVersion(body: String): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
        normalizeVersion(tag)
    }.getOrNull()

    fun parseNpmmirrorVersions(body: String): List<String> = runCatching {
        Json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val name = (element as? kotlinx.serialization.json.JsonObject)
                ?.get("name")?.jsonPrimitive?.contentOrNull
                ?.trimEnd('/') ?: return@mapNotNull null
            normalizeVersion(name)
        }
    }.getOrElse { emptyList() }

    fun latestVersion(candidates: Iterable<String>): String? =
        candidates.filter { normalizeVersion(it) != null }.maxWithOrNull { left, right ->
            compareVersions(left, right)
        }

    fun parseChecksums(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val match = CHECKSUM_PATTERN.matchEntire(line.trim()) ?: return@forEach
            result[match.groupValues[2]] = match.groupValues[1].lowercase()
        }
        return result
    }

    fun releaseFromChecksums(version: String, checksums: Map<String, String>): FeishuRelease? {
        val normalized = normalizeVersion(version) ?: return null
        val digest = checksums[archiveName(normalized)] ?: return null
        return release(normalized, digest)
    }

    fun shouldCheck(lastCheckedAt: Long, now: Long): Boolean =
        lastCheckedAt <= 0L || now - lastCheckedAt >= CHECK_INTERVAL_MS

    fun statusTextKey(
        installedVersion: String?,
        latestVersion: String?,
        checking: Boolean,
        failed: Boolean,
        pinnedVersion: String,
    ): FeishuUpdateStatus = when {
        installedVersion == null -> FeishuUpdateStatus.NOT_INSTALLED
        checking -> FeishuUpdateStatus.CHECKING
        latestVersion != null && isNewer(latestVersion, installedVersion) -> FeishuUpdateStatus.AVAILABLE
        latestVersion != null -> FeishuUpdateStatus.LATEST
        failed -> FeishuUpdateStatus.FAILED
        isNewer(pinnedVersion, installedVersion) -> FeishuUpdateStatus.AVAILABLE
        else -> FeishuUpdateStatus.LATEST
    }
}

internal enum class FeishuUpdateStatus {
    NOT_INSTALLED,
    CHECKING,
    LATEST,
    AVAILABLE,
    FAILED,
}
