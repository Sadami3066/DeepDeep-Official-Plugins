package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.cli.update.CliChecksums
import io.github.whywood.deepdeep.plugin.cli.update.CliRelease
import io.github.whywood.deepdeep.plugin.cli.update.CliReleasePolicy
import io.github.whywood.deepdeep.plugin.cli.update.CliUpdateThrottle
import io.github.whywood.deepdeep.plugin.cli.update.CliVersions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal typealias FeishuRelease = CliRelease

internal object FeishuUpdate {
    const val GITHUB_LATEST_URL = "https://api.github.com/repos/larksuite/cli/releases/latest"
    const val GITHUB_RELEASE_URL = "https://github.com/larksuite/cli/releases/download"
    const val NPMMIRROR_INDEX_URL = "https://registry.npmmirror.com/-/binary/lark-cli/"
    const val NPMMIRROR_RELEASE_URL = "https://registry.npmmirror.com/-/binary/lark-cli"
    const val CHECK_INTERVAL_MS = CliUpdateThrottle.DEFAULT_INTERVAL_MS

    private val POLICY = CliReleasePolicy(
        archiveNameFor = { version -> "lark-cli-$version-linux-arm64.tar.gz" },
        downloadUrlsFor = { version ->
            listOf(
                "$NPMMIRROR_RELEASE_URL/v$version/${archiveName(version)}",
                "$GITHUB_RELEASE_URL/v$version/${archiveName(version)}",
            )
        },
        checksumsUrlsFor = { version ->
            listOf(
                "$NPMMIRROR_RELEASE_URL/v$version/checksums.txt",
                "$GITHUB_RELEASE_URL/v$version/checksums.txt",
            )
        },
    )

    fun archiveName(version: String): String = "lark-cli-$version-linux-arm64.tar.gz"

    fun checksumsUrls(version: String): List<String> = POLICY.checksumsUrls(version)

    fun release(version: String, sha256: String): FeishuRelease? = POLICY.release(version, sha256)

    fun pinnedRelease(): FeishuRelease = CliRelease(
        version = FEISHU_VERSION,
        archiveName = ARCHIVE_NAME,
        downloadUrls = DOWNLOAD_URLS,
        sha256 = ARCHIVE_SHA256,
    )

    fun normalizeVersion(raw: String): String? = CliVersions.normalize(raw)

    fun compareVersions(left: String, right: String): Int = CliVersions.compare(left, right)

    fun isNewer(candidate: String, current: String): Boolean = CliVersions.isNewer(candidate, current)

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

    fun latestVersion(candidates: Iterable<String>): String? = CliVersions.latest(candidates)

    fun parseChecksums(text: String): Map<String, String> = CliChecksums.parse(text)

    fun releaseFromChecksums(version: String, checksums: Map<String, String>): FeishuRelease? =
        POLICY.release(version, checksums)

    fun shouldCheck(lastCheckedAt: Long, now: Long): Boolean =
        CliUpdateThrottle.shouldCheck(lastCheckedAt, now, CHECK_INTERVAL_MS)

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
