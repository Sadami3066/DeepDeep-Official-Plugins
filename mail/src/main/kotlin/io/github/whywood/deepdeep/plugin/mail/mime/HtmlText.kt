package io.github.whywood.deepdeep.plugin.mail.mime

internal object HtmlText {
    fun toPlainText(html: String): String {
        if (html.isEmpty()) return ""
        val withoutScripts = SCRIPT.replace(STYLE.replace(html, " "), " ")
        val builder = StringBuilder(withoutScripts.length)
        var index = 0
        while (index < withoutScripts.length) {
            val char = withoutScripts[index]
            if (char == '<') {
                if (withoutScripts.startsWith("<!--", index)) {
                    val end = withoutScripts.indexOf("-->", index)
                    index = if (end < 0) withoutScripts.length else end + 3
                    continue
                }
                val end = withoutScripts.indexOf('>', index)
                if (end < 0) break
                handleTag(builder, withoutScripts.substring(index + 1, end).trim())
                index = end + 1
                continue
            }
            builder.append(char)
            index++
        }
        return cleanup(decodeEntities(builder.toString()))
    }

    fun preview(text: String, limit: Int = 200): String {
        val flattened = WHITESPACE.replace(text, " ").trim()
        return if (flattened.length > limit) flattened.take(limit) else flattened
    }

    private fun handleTag(builder: StringBuilder, tagText: String) {
        if (tagText.isEmpty() || tagText.startsWith("!") || tagText.startsWith("?")) return
        val closing = tagText.startsWith('/')
        val name = tagText.trimStart('/')
            .substringBefore(' ')
            .substringBefore('\t')
            .substringBefore('/')
            .lowercase()
        when (name) {
            "br" -> builder.append('\n')
            "p", "div", "tr", "li", "ul", "ol", "table", "blockquote", "section", "article",
            "header", "footer", "h1", "h2", "h3", "h4", "h5", "h6" -> builder.append('\n')
            "td", "th" -> builder.append(' ')
            "img" -> if (!closing) {
                val alt = ALT.find(tagText)?.let { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }
                }
                if (!alt.isNullOrBlank()) builder.append(alt).append(' ')
            }
            else -> Unit
        }
    }

    private fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '&') {
                builder.append(char)
                index++
                continue
            }
            val end = value.indexOf(';', index + 1)
            if (end < 0 || end - index > 10) {
                builder.append(char)
                index++
                continue
            }
            val entity = value.substring(index + 1, end)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                builder.append(char)
                index++
            } else {
                builder.append(decoded)
                index = end + 1
            }
        }
        return builder.toString()
    }

    private fun decodeEntity(entity: String): String? {
        if (entity.startsWith("#")) {
            val code = if (entity.startsWith("#x", ignoreCase = true)) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull()
            }
            if (code == null || code <= 0 || code > 0x10FFFF) return null
            return String(Character.toChars(code))
        }
        return NAMED_ENTITIES[entity.lowercase()]
    }

    private fun cleanup(value: String): String {
        val lines = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = StringBuilder()
        var blankLines = 0
        for (line in lines) {
            val trimmed = SPACES.replace(line, " ").trim()
            if (trimmed.isEmpty()) {
                blankLines += 1
                continue
            }
            if (result.isNotEmpty()) {
                result.append('\n')
                if (blankLines > 0) result.append('\n')
            }
            result.append(trimmed)
            blankLines = 0
        }
        return result.toString()
    }

    private val SCRIPT = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE = Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ALT = Regex("alt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("[ \\t\\f\\u000B]+")
    private val WHITESPACE = Regex("[\\s\\u00a0]+")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "rsquo" to "’",
        "lsquo" to "‘",
        "rdquo" to "”",
        "ldquo" to "“",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "middot" to "·",
        "bull" to "•",
        "deg" to "°",
        "laquo" to "«",
        "raquo" to "»",
    )
}
