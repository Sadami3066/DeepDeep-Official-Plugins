package io.github.whywood.superpi.plugin.wecom

import io.github.whywood.superpi.plugin.api.NetworkService
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.cli.longValue
import io.github.whywood.superpi.plugin.cli.stringValue
import io.github.whywood.superpi.plugin.cli.update.CliHttp
import io.github.whywood.superpi.plugin.cli.update.CliRelease
import io.github.whywood.superpi.plugin.cli.update.CliUpdateCheck
import io.github.whywood.superpi.plugin.cli.update.CliUpdateSource
import io.github.whywood.superpi.plugin.cli.update.CliUpdateThrottle
import io.github.whywood.superpi.plugin.cli.update.CliVersions
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class WecomRelease(
    val version: String,
    val tarballUrl: String,
    val sha512: String,
    val checkedAt: Long,
)

internal sealed interface WecomReleaseResult {
    data class Success(val release: WecomRelease) : WecomReleaseResult

    data class Failure(val message: String) : WecomReleaseResult
}

internal object WecomReleases {
    const val REGISTRY_URL = "https://registry.npmjs.org/@wecom/cli-linux-arm64/latest"
    const val THROTTLE_MS = CliUpdateThrottle.DEFAULT_INTERVAL_MS
    const val INTEGRITY_PREFIX = "sha512-"

    fun parseRegistryRelease(body: String, now: Long): WecomReleaseResult {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return WecomReleaseResult.Failure("更新信息格式无法识别")
        val rawVersion = root.stringValue("version")
            ?: return WecomReleaseResult.Failure("更新信息缺少版本号")
        val version = CliVersions.normalize(rawVersion)
            ?: return WecomReleaseResult.Failure("更新信息版本号格式不合法")
        val dist = root["dist"] as? JsonObject
            ?: return WecomReleaseResult.Failure("更新信息缺少安装包信息")
        val tarball = dist.stringValue("tarball")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return WecomReleaseResult.Failure("更新信息缺少安装包地址")
        val sha512 = parseSha512(dist.stringValue("integrity"))
            ?: return WecomReleaseResult.Failure("更新信息缺少 sha512 校验值，已拒绝更新")
        return WecomReleaseResult.Success(
            WecomRelease(
                version = version,
                tarballUrl = tarball,
                sha512 = sha512,
                checkedAt = now,
            ),
        )
    }

    fun parseSha512(integrity: String?): String? {
        if (integrity.isNullOrBlank()) return null
        val entries = integrity.split(Regex("\\s+"))
        val sha512Entry = entries.firstOrNull { it.startsWith(INTEGRITY_PREFIX) } ?: return null
        val value = sha512Entry.removePrefix(INTEGRITY_PREFIX)
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (decoded.size != 64) return null
        return Base64.getEncoder().encodeToString(decoded)
    }

    fun isNewer(latest: String, installed: String?): Boolean {
        if (installed.isNullOrBlank()) return true
        return CliVersions.isNewer(latest, installed)
    }

    fun compareVersions(left: String, right: String): Int = CliVersions.compare(left, right)

    fun toCliRelease(release: WecomRelease): CliRelease = CliRelease(
        version = release.version,
        archiveName = WecomCli.archiveName(release.version),
        downloadUrls = WecomCli.releaseDownloadUrls(release.tarballUrl, release.version),
        sha512Base64 = release.sha512,
    )

    fun pinnedRelease(): CliRelease = CliRelease(
        version = WecomCli.VERSION,
        archiveName = WecomCli.ARCHIVE_NAME,
        downloadUrls = WecomCli.DOWNLOAD_URLS,
        sha256 = WecomCli.ARCHIVE_SHA256,
    )

    fun fromCheck(release: CliRelease, checkedAt: Long): WecomRelease? {
        val sha512 = release.sha512Base64 ?: return null
        val tarballUrl = release.downloadUrls.firstOrNull() ?: return null
        return WecomRelease(
            version = release.version,
            tarballUrl = tarballUrl,
            sha512 = sha512,
            checkedAt = checkedAt,
        )
    }
}

internal class WecomReleaseSource : CliUpdateSource {
    var failure: String? = null
        private set

    override suspend fun discover(network: NetworkService?, installedVersion: String?): CliUpdateCheck {
        failure = null
        val service = network ?: return CliUpdateCheck(latestVersion = null, release = null, failed = true)
        val body = CliHttp.getText(service, WecomReleases.REGISTRY_URL, timeoutMs = UPDATE_CHECK_TIMEOUT_MS)
            ?: return CliUpdateCheck(latestVersion = null, release = null, failed = true)
        return when (val parsed = WecomReleases.parseRegistryRelease(body, System.currentTimeMillis())) {
            is WecomReleaseResult.Success -> CliUpdateCheck(
                latestVersion = parsed.release.version,
                release = WecomReleases.toCliRelease(parsed.release),
                failed = false,
            )
            is WecomReleaseResult.Failure -> {
                failure = parsed.message
                CliUpdateCheck(latestVersion = null, release = null, failed = true)
            }
        }
    }

    private companion object {
        const val UPDATE_CHECK_TIMEOUT_MS = 10_000L
    }
}

internal class WecomReleaseStore(private val host: PluginHost) {
    suspend fun read(): WecomRelease? {
        val stored = runCatching { host.settings().getJson(KEY) }.getOrNull() ?: return null
        return runCatching {
            WecomRelease(
                version = stored.stringValue("version") ?: return null,
                tarballUrl = stored.stringValue("tarballUrl") ?: return null,
                sha512 = stored.stringValue("sha512") ?: return null,
                checkedAt = stored.longValue("checkedAt") ?: 0L,
            )
        }.getOrNull()
    }

    suspend fun write(release: WecomRelease) {
        runCatching {
            host.settings().putJson(
                KEY,
                buildJsonObject {
                    put("version", release.version)
                    put("tarballUrl", release.tarballUrl)
                    put("sha512", release.sha512)
                    put("checkedAt", release.checkedAt)
                },
            )
        }
    }

    suspend fun clear() {
        runCatching { host.settings().remove(KEY) }
    }

    private companion object {
        const val KEY = "cli-release"
    }
}
