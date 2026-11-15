package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.cli.update.CliHttp
import io.github.whywood.deepdeep.plugin.cli.update.CliUpdateCheck
import io.github.whywood.deepdeep.plugin.cli.update.CliUpdateSource
import io.github.whywood.deepdeep.plugin.cli.update.CliVersions

internal object FeishuReleaseSource : CliUpdateSource {
    private val GITHUB_HEADERS = mapOf(
        "Accept" to "application/vnd.github+json",
        "User-Agent" to "DeepDeep-Feishu-Plugin",
    )

    override suspend fun discover(network: NetworkService?, installedVersion: String?): CliUpdateCheck {
        val service = network ?: return CliUpdateCheck(latestVersion = null, release = null, failed = true)
        discoverGithub(service)?.let { return result(it, installedVersion) }
        discoverNpmmirror(service)?.let { return result(it, installedVersion) }
        return CliUpdateCheck(latestVersion = null, release = null, failed = true)
    }

    private suspend fun discoverGithub(network: NetworkService): FeishuRelease? {
        val body = CliHttp.getText(
            network = network,
            url = FeishuUpdate.GITHUB_LATEST_URL,
            headers = GITHUB_HEADERS,
            timeoutMs = UPDATE_CHECK_TIMEOUT_MS,
        ) ?: return null
        val version = FeishuUpdate.parseGithubLatestVersion(body) ?: return null
        return FeishuUpdate.releaseFromChecksums(version, fetchChecksums(network, version))
    }

    private suspend fun discoverNpmmirror(network: NetworkService): FeishuRelease? {
        val body = CliHttp.getText(
            network = network,
            url = FeishuUpdate.NPMMIRROR_INDEX_URL,
            timeoutMs = UPDATE_CHECK_TIMEOUT_MS,
        ) ?: return null
        val version = FeishuUpdate.latestVersion(FeishuUpdate.parseNpmmirrorVersions(body)) ?: return null
        return FeishuUpdate.releaseFromChecksums(version, fetchChecksums(network, version))
    }

    private suspend fun fetchChecksums(network: NetworkService, version: String): Map<String, String> {
        for (url in FeishuUpdate.checksumsUrls(version)) {
            val body = CliHttp.getText(network, url, timeoutMs = UPDATE_CHECK_TIMEOUT_MS) ?: continue
            val checksums = FeishuUpdate.parseChecksums(body)
            if (checksums.isNotEmpty()) return checksums
        }
        return emptyMap()
    }

    private fun result(release: FeishuRelease, installedVersion: String?): CliUpdateCheck {
        val latest = installedVersion?.let { CliVersions.latest(listOf(release.version, it)) } ?: release.version
        val available = installedVersion?.let { release.takeIf { candidate -> CliVersions.isNewer(candidate.version, it) } }
        return CliUpdateCheck(latestVersion = latest, release = available, failed = false)
    }
}
