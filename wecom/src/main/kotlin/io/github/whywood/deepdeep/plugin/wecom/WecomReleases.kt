package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    const val MIRROR_BASE = "https://registry.npmmirror.com/@wecom/cli-linux-arm64/-"
    const val THROTTLE_MS = 24L * 60L * 60L * 1000L
    const val INTEGRITY_PREFIX = "sha512-"

    fun parseRegistryRelease(body: String, now: Long): WecomReleaseResult {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return WecomReleaseResult.Failure("更新信息格式无法识别")
        val version = root.stringOrNull("version")
            ?: return WecomReleaseResult.Failure("更新信息缺少版本号")
        if (!VERSION_PATTERN.matches(version)) {
            return WecomReleaseResult.Failure("更新信息版本号格式不合法")
        }
        val dist = root["dist"] as? JsonObject
            ?: return WecomReleaseResult.Failure("更新信息缺少安装包信息")
        val tarball = dist.stringOrNull("tarball")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return WecomReleaseResult.Failure("更新信息缺少安装包地址")
        val integrity = dist.stringOrNull("integrity")
        val sha512 = parseSha512(integrity)
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

    private val VERSION_PATTERN = Regex("^[0-9][0-9A-Za-z._+-]*$")

    fun parseSha512(integrity: String?): String? {
        if (integrity.isNullOrBlank()) return null
        val entries = integrity.split(Regex("\\s+"))
        val sha512Entry = entries.firstOrNull { it.startsWith(INTEGRITY_PREFIX) } ?: return null
        val value = sha512Entry.removePrefix(INTEGRITY_PREFIX)
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (decoded.size != 64) return null
        return Base64.getEncoder().encodeToString(decoded)
    }

    fun sha512Base64(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-512")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            Base64.getEncoder().encodeToString(digest.digest())
        }.getOrNull()
    }

    fun verifySha512(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return false
        val actual = sha512Base64(file) ?: return false
        return actual == expected
    }

    fun downloadUrls(primaryTarball: String, version: String): List<String> {
        val mirror = "$MIRROR_BASE/cli-linux-arm64-$version.tgz"
        return listOf(primaryTarball, mirror).distinct()
    }

    fun isNewer(latest: String, installed: String?): Boolean {
        if (installed.isNullOrBlank()) return true
        return compareVersions(latest, installed) > 0
    }

    fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val leftPart = leftParts.getOrNull(index)
            val rightPart = rightParts.getOrNull(index)
            val compared = comparePart(leftPart, rightPart)
            if (compared != 0) return compared
        }
        return 0
    }

    private fun comparePart(left: String?, right: String?): Int {
        if (left == null && right == null) return 0
        if (left == null) return if (right?.toIntOrNull() == 0) 0 else -1
        if (right == null) return if (left.toIntOrNull() == 0) 0 else 1
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> 1
            rightNumber != null -> -1
            else -> left.compareTo(right)
        }
    }

    private fun versionParts(version: String): List<String> = version
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { part -> part.takeWhile { it.isDigit() }.ifBlank { part } }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal class WecomReleaseStore(private val host: PluginHost) {
    suspend fun read(): WecomRelease? {
        val stored = runCatching { host.settings().getJson(KEY) }.getOrNull() ?: return null
        return runCatching {
            WecomRelease(
                version = stored.stringOrNull("version") ?: return null,
                tarballUrl = stored.stringOrNull("tarballUrl") ?: return null,
                sha512 = stored.stringOrNull("sha512") ?: return null,
                checkedAt = stored.stringOrNull("checkedAt")?.toLongOrNull() ?: 0L,
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

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val KEY = "cli-release"
    }
}
