package io.github.whywood.deepdeep.plugin.cli.update

import java.util.Base64

data class CliRelease(
    val version: String,
    val archiveName: String,
    val downloadUrls: List<String>,
    val sha256: String = "",
    val sha512Base64: String? = null,
)

class CliReleasePolicy(
    private val archiveNameFor: (version: String) -> String,
    private val downloadUrlsFor: (version: String) -> List<String>,
    private val checksumsUrlsFor: (version: String) -> List<String>,
    private val digestLength: Int = CliChecksums.SHA256_LENGTH,
) {
    fun archiveName(version: String): String = archiveNameFor(version)

    fun downloadUrls(version: String): List<String> = downloadUrlsFor(version)

    fun checksumsUrls(version: String): List<String> = checksumsUrlsFor(version)

    fun parseChecksums(text: String): Map<String, String> = CliChecksums.parse(text, digestLength)

    fun release(version: String, digest: String): CliRelease? {
        val normalized = CliVersions.normalize(version) ?: return null
        val sha = digest.lowercase().takeIf { CliChecksums.isDigest(it, digestLength) } ?: return null
        return CliRelease(
            version = normalized,
            archiveName = archiveName(normalized),
            downloadUrls = downloadUrls(normalized),
            sha256 = sha,
        )
    }

    fun release(version: String, checksums: Map<String, String>): CliRelease? {
        val normalized = CliVersions.normalize(version) ?: return null
        val digest = CliChecksums.digestOf(checksums, archiveName(normalized), digestLength) ?: return null
        return release(normalized, digest)
    }

    fun releaseWithSha512(version: String, sha512Base64: String): CliRelease? {
        val normalized = CliVersions.normalize(version) ?: return null
        val digest = normalizeSha512(sha512Base64) ?: return null
        return CliRelease(
            version = normalized,
            archiveName = archiveName(normalized),
            downloadUrls = downloadUrls(normalized),
            sha512Base64 = digest,
        )
    }

    private fun normalizeSha512(raw: String): String? {
        val decoded = runCatching { Base64.getDecoder().decode(raw.trim()) }.getOrNull() ?: return null
        if (decoded.size != 64) return null
        return Base64.getEncoder().encodeToString(decoded)
    }
}
