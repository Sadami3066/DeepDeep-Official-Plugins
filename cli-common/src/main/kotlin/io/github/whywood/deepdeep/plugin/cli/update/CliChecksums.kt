package io.github.whywood.superpi.plugin.cli.update

object CliChecksums {
    const val SHA256_LENGTH: Int = 64

    const val SHA512_LENGTH: Int = 128

    fun parse(text: String, digestLength: Int = SHA256_LENGTH): Map<String, String> {
        val pattern = Regex("""^([0-9a-fA-F]{$digestLength})\s+\*?(\S+)$""")
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val match = pattern.matchEntire(line.trim()) ?: return@forEach
            result[match.groupValues[2]] = match.groupValues[1].lowercase()
        }
        return result
    }

    fun digestOf(checksums: Map<String, String>, fileName: String, digestLength: Int = SHA256_LENGTH): String? =
        checksums[fileName]?.lowercase()?.takeIf { digest -> isDigest(digest, digestLength) }

    fun isDigest(value: String, digestLength: Int = SHA256_LENGTH): Boolean =
        value.length == digestLength && value.all { it in '0'..'9' || it in 'a'..'f' }
}
