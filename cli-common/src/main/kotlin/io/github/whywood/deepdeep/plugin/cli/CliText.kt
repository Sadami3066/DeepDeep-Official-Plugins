package io.github.whywood.deepdeep.plugin.cli

object CliText {
    const val DEFAULT_FACT_CHARS: Int = 240

    const val DEFAULT_LINE_CHARS: Int = 420

    private val ANSI_ESCAPE = Regex(
        """[\u001B\u009B](?:\[[0-9;?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001B\\)?)""",
    )
    private val DOTENV_LINE = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*$""")
    private val HTML_TAG = Regex("""<[^>]{0,400}>""")
    private val BLOCK_BREAK = Regex("""</(?:p|h[1-6]|li|tr|div|blockquote)>""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    fun stripAnsi(text: String): String = text.replace(ANSI_ESCAPE, "")

    fun isDotenvLine(line: String): Boolean = DOTENV_LINE.matches(line.trim())

    fun decodeEntities(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '&') {
                val end = text.indexOf(';', index + 1)
                if (end in (index + 2)..(index + 12)) {
                    val entity = text.substring(index + 1, end)
                    val decoded = when (entity.lowercase()) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos", "#39" -> "'"
                        "nbsp" -> " "
                        else -> null
                    }
                    if (decoded != null) {
                        builder.append(decoded)
                        index = end + 1
                        continue
                    }
                }
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    fun sanitize(raw: String): String {
        var text = stripAnsi(raw).replace(BLOCK_BREAK, "\n")
        text = text.replace(HTML_TAG, " ")
        text = decodeEntities(text)
        return text.replace(WHITESPACE, " ").trim()
    }

    fun cleanDocContent(raw: String): String {
        val withBreaks = raw.replace(BLOCK_BREAK, "\n")
        val text = withBreaks.replace(HTML_TAG, " ")
        return decodeEntities(text)
            .lineSequence()
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun clip(text: String, max: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= max) trimmed else trimmed.take(max).trimEnd() + "…"
    }

    fun oneLine(text: String, max: Int = DEFAULT_FACT_CHARS): String = clip(sanitize(text), max)

    fun multiLine(text: String, max: Int = DEFAULT_LINE_CHARS): String = clip(text, max)
}
