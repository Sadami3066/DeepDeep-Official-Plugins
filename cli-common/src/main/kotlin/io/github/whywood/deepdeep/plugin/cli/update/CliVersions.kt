package io.github.whywood.deepdeep.plugin.cli.update

object CliVersions {
    private val VERSION_PATTERN = Regex("""^(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        return if (VERSION_PATTERN.matches(trimmed)) trimmed else null
    }

    fun compare(left: String, right: String): Int {
        val a = normalize(left)?.split('.')?.map { it.toInt() } ?: return left.compareTo(right)
        val b = normalize(right)?.split('.')?.map { it.toInt() } ?: return left.compareTo(right)
        for (index in 0 until 3) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun latest(candidates: Iterable<String>): String? =
        candidates.filter { normalize(it) != null }.maxWithOrNull { left, right ->
            compare(left, right)
        }
}
