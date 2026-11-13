package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail

@Composable
internal fun rememberMailBody(host: PluginHost, detail: MailMessageDetail): AnnotatedString {
    val colors = deepDeepColors()
    val imageLabel = host.t("含图片", "Image")
    val onUrl = remember(host) {
        LinkInteractionListener { link ->
            if (link is LinkAnnotation.Url) host.ui().openUrl(link.url)
        }
    }
    return remember(detail, colors, imageLabel, onUrl) {
        MailBodyWriter(colors, imageLabel, onUrl).write(detail)
    }
}

private class MailBodyWriter(
    private val colors: DeepDeepColors,
    private val imageLabel: String,
    private val onUrl: LinkInteractionListener,
) {
    private val builder = AnnotatedString.Builder()
    private val styleStack = ArrayDeque<String>()
    private val orderedCounters = ArrayDeque<Int>()
    private var trailingNewlines = 0
    private var atLineStart = true
    private var pendingSpace = false
    private var quoteDepth = 0
    private var listDepth = 0
    private var inPre = false
    private var pendingLink: PendingLink? = null

    fun write(detail: MailMessageDetail): AnnotatedString {
        val html = detail.htmlBody?.trim().orEmpty()
        if (html.isNotEmpty() && html.contains('<')) {
            writeHtml(html)
        } else {
            writePlain(detail.textBody)
        }
        while (styleStack.isNotEmpty()) {
            styleStack.removeLast()
            builder.pop()
        }
        val result = builder.toAnnotatedString()
        val end = result.text.indexOfLast { it != '\n' } + 1
        return if (end <= 0) AnnotatedString("") else result.subSequence(0, end)
    }

    private fun writePlain(text: String) {
        if (text.isBlank()) return
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        normalized.split('\n').forEachIndexed { index, rawLine ->
            if (index > 0) {
                builder.append('\n')
                atLineStart = true
                trailingNewlines = 1
                pendingSpace = false
            }
            var line = rawLine
            var depth = 0
            while (line.startsWith(">")) {
                depth++
                line = line.removePrefix(">")
            }
            if (depth > 0) line = line.removePrefix(" ")
            val prefix: String
            if (depth > 0) {
                prefix = "│ ".repeat(depth.coerceAtMost(3))
            } else {
                val bullet = PlainBulletRegex.find(line)
                if (bullet != null) {
                    prefix = "• "
                    line = line.substring(bullet.range.last + 1)
                } else {
                    prefix = ""
                }
            }
            val quoted = depth > 0
            val start = builder.length
            if (quoted) builder.pushStyle(SpanStyle(color = colors.inkTertiary))
            builder.append(prefix)
            appendLinks(line)
            if (quoted) builder.pop()
            if (builder.length > start) {
                atLineStart = false
                trailingNewlines = 0
            }
        }
    }

    private fun writeHtml(html: String) {
        val clean = stripHtmlNoise(html)
        var pos = 0
        while (pos < clean.length) {
            val tagStart = clean.indexOf('<', pos)
            if (tagStart < 0) {
                appendText(decodeEntities(clean.substring(pos)))
                break
            }
            if (tagStart > pos) {
                appendText(decodeEntities(clean.substring(pos, tagStart)))
            }
            val tagEnd = clean.indexOf('>', tagStart)
            if (tagEnd < 0) {
                appendText(decodeEntities(clean.substring(tagStart)))
                break
            }
            handleTag(clean.substring(tagStart, tagEnd + 1))
            pos = tagEnd + 1
        }
    }

    private fun handleTag(raw: String) {
        if (raw.length < 3 || raw[1] == '!') return
        val closing = raw[1] == '/'
        val body = raw.substring(if (closing) 2 else 1, raw.length - 1).trim()
        if (body.isEmpty()) return
        val core = body.removeSuffix("/").trim()
        val name = core.takeWhile { it.isLetterOrDigit() }.lowercase()
        if (name.isEmpty()) return
        val attributes = core.substring(name.length)
        if (closing) closeTag(name) else openTag(name, attributes)
    }

    private fun openTag(name: String, attributes: String) {
        when (name) {
            "br" -> appendNewline()
            "hr" -> {
                blockBreak(blankLine = true)
                val start = builder.length
                builder.append("────────")
                builder.addStyle(SpanStyle(color = colors.inkTertiary), start, builder.length)
                blockBreak(blankLine = true)
            }

            "p", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blockBreak(blankLine = true)
                if (name.length == 2 && name[0] == 'h') {
                    val size = if (name[1] == '1' || name[1] == '2') 17.sp else 16.sp
                    pushStyle(
                        name,
                        SpanStyle(fontWeight = FontWeight.Bold, fontSize = size),
                    )
                }
            }

            "div", "section", "article", "header", "footer", "main", "table", "thead", "tbody",
            "tfoot", "tr", "center", "address" -> blockBreak(blankLine = false)

            "ul" -> {
                blockBreak(blankLine = false)
                listDepth++
                orderedCounters.addLast(0)
            }

            "ol" -> {
                blockBreak(blankLine = false)
                listDepth++
                orderedCounters.addLast(1)
            }

            "li" -> {
                blockBreak(blankLine = false)
                ensureLineStart()
                repeat((listDepth - 1).coerceIn(0, 3)) { builder.append("  ") }
                val counter = orderedCounters.lastOrNull() ?: 0
                if (counter > 0) {
                    builder.append("$counter. ")
                    orderedCounters[orderedCounters.lastIndex] = counter + 1
                } else {
                    builder.append("• ")
                }
                atLineStart = false
                trailingNewlines = 0
            }

            "blockquote" -> {
                blockBreak(blankLine = true)
                quoteDepth++
            }

            "pre" -> {
                blockBreak(blankLine = true)
                inPre = true
                pushStyle(
                    "pre",
                    SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground),
                )
            }

            "code", "tt", "kbd", "samp" -> if (!inPre) {
                pushStyle(
                    "code",
                    SpanStyle(fontFamily = FontFamily.Monospace, background = colors.inlineCodeBackground),
                )
            }

            "b", "strong" -> pushStyle("b", SpanStyle(fontWeight = FontWeight.Bold))
            "i", "em", "cite", "dfn" -> pushStyle("i", SpanStyle(fontStyle = FontStyle.Italic))
            "u", "ins" -> pushStyle("u", SpanStyle(textDecoration = TextDecoration.Underline))
            "s", "strike", "del" -> pushStyle("s", SpanStyle(textDecoration = TextDecoration.LineThrough))

            "a" -> {
                val href = attribute(attributes, "href")
                if (!href.isNullOrBlank()) pendingLink = PendingLink(href, builder.length)
            }

            "img" -> appendImagePlaceholder()

            else -> Unit
        }
    }

    private fun closeTag(name: String) {
        when (name) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                popStyle(name)
                blockBreak(blankLine = true)
            }

            "p" -> blockBreak(blankLine = true)
            "table", "div", "section", "article", "header", "footer", "main", "tr", "center", "address" ->
                blockBreak(blankLine = false)
            "ul", "ol" -> {
                if (listDepth > 0) listDepth--
                if (orderedCounters.isNotEmpty()) orderedCounters.removeLast()
                blockBreak(blankLine = false)
            }

            "li" -> appendNewline()
            "blockquote" -> {
                if (quoteDepth > 0) quoteDepth--
                blockBreak(blankLine = true)
            }

            "pre" -> {
                popStyle("pre")
                inPre = false
                blockBreak(blankLine = true)
            }

            "code", "tt", "kbd", "samp" -> if (!inPre) popStyle("code")
            "b", "strong", "i", "em", "cite", "dfn", "u", "ins", "s", "strike", "del" ->
                popStyle(styleKeyFor(name))
            "a" -> closeLink()
            "td", "th" -> appendRawSpace()
            else -> Unit
        }
    }

    private fun styleKeyFor(name: String): String = when (name) {
        "b", "strong" -> "b"
        "i", "em", "cite", "dfn" -> "i"
        "u", "ins" -> "u"
        "s", "strike", "del" -> "s"
        else -> name
    }

    private fun pushStyle(key: String, style: SpanStyle) {
        builder.pushStyle(style)
        styleStack.addLast(key)
    }

    private fun popStyle(key: String) {
        if (styleStack.lastOrNull() == key) {
            styleStack.removeLast()
            builder.pop()
        }
    }

    private fun closeLink() {
        val link = pendingLink ?: return
        pendingLink = null
        if (builder.length <= link.start) return
        val styles = TextLinkStyles(
            style = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
        )
        builder.addLink(LinkAnnotation.Url(link.href, styles, onUrl), link.start, builder.length)
    }

    private fun appendImagePlaceholder() {
        if (pendingSpace && !atLineStart) {
            builder.append(' ')
            pendingSpace = false
        }
        ensureLineStart()
        val start = builder.length
        builder.append("[$imageLabel]")
        builder.addStyle(SpanStyle(color = colors.inkTertiary, fontStyle = FontStyle.Italic), start, builder.length)
        atLineStart = false
        trailingNewlines = 0
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        if (inPre) {
            appendPre(text)
            return
        }
        for (char in text) {
            when (char) {
                '\n', '\r', '\t', ' ' -> pendingSpace = true
                else -> {
                    if (pendingSpace) {
                        if (!atLineStart && builder.length > 0) builder.append(' ')
                        pendingSpace = false
                    }
                    ensureLineStart()
                    builder.append(char)
                    atLineStart = false
                    trailingNewlines = 0
                }
            }
        }
    }

    private fun appendPre(text: String) {
        for (char in text) {
            when (char) {
                '\r' -> Unit
                '\n' -> {
                    if (builder.length > 0) {
                        builder.append('\n')
                        trailingNewlines++
                    }
                    atLineStart = true
                    pendingSpace = false
                }

                else -> {
                    ensureLineStart()
                    builder.append(char)
                    atLineStart = false
                    trailingNewlines = 0
                }
            }
        }
    }

    private fun ensureLineStart() {
        if (!atLineStart) return
        if (quoteDepth > 0) {
            val start = builder.length
            repeat(quoteDepth.coerceAtMost(3)) { builder.append("│ ") }
            builder.addStyle(SpanStyle(color = colors.inkTertiary), start, builder.length)
        }
        atLineStart = false
    }

    private fun appendNewline() {
        pendingSpace = false
        if (builder.length == 0) return
        builder.append('\n')
        trailingNewlines++
        atLineStart = true
    }

    private fun appendRawSpace() {
        if (builder.length == 0 || atLineStart) return
        builder.append(' ')
        pendingSpace = false
    }

    private fun blockBreak(blankLine: Boolean) {
        pendingSpace = false
        if (builder.length == 0) return
        val target = if (blankLine) 2 else 1
        while (trailingNewlines < target) {
            builder.append('\n')
            trailingNewlines++
        }
        atLineStart = true
    }

    private fun appendLinks(text: String) {
        if (text.isEmpty()) return
        var cursor = 0
        for (match in UrlRegex.findAll(text)) {
            if (match.range.first > cursor) builder.append(text.substring(cursor, match.range.first))
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '，', '。', '；', '：', '！', '？', '）')
            if (raw.isEmpty()) {
                builder.append(match.value)
                cursor = match.range.last + 1
                continue
            }
            cursor = match.range.first + raw.length
            val start = builder.length
            val styles = TextLinkStyles(
                style = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
            )
            builder.append(raw)
            builder.addLink(LinkAnnotation.Url(raw, styles, onUrl), start, builder.length)
        }
        if (cursor < text.length) builder.append(text.substring(cursor))
    }

    private data class PendingLink(val href: String, val start: Int)
}

private val UrlRegex = Regex("https?://[^\\s<>\"'）)]+")

private val PlainBulletRegex = Regex("^\\s*[-*•]\\s+")

private val HtmlCommentRegex = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))

private val HtmlHeadRegex = Regex("<head[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

private val HtmlScriptRegex = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

private val HtmlStyleRegex = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

private val HtmlDoctypeRegex = Regex("<![^>]*>")

private val HtmlEntityRegex = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

private val NamedEntities = mapOf(
    "nbsp" to " ",
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "lsquo" to "‘",
    "rsquo" to "’",
    "ldquo" to "“",
    "rdquo" to "”",
    "middot" to "·",
    "bull" to "•",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "deg" to "°",
    "laquo" to "«",
    "raquo" to "»",
    "euro" to "€",
    "pound" to "£",
    "yen" to "¥",
    "times" to "×",
    "divide" to "÷",
    "ensp" to " ",
    "emsp" to " ",
    "thinsp" to " ",
    "zwnj" to "",
    "zwj" to "",
)

private fun stripHtmlNoise(html: String): String =
    html.replace(HtmlCommentRegex, " ")
        .replace(HtmlHeadRegex, " ")
        .replace(HtmlScriptRegex, " ")
        .replace(HtmlStyleRegex, " ")
        .replace(HtmlDoctypeRegex, " ")

private fun decodeEntities(text: String): String = HtmlEntityRegex.replace(text) { match ->
    val token = match.groupValues[1]
    if (token.startsWith("#")) {
        runCatching {
            val code = if (token.startsWith("#x") || token.startsWith("#X")) {
                token.substring(2).toInt(16)
            } else {
                token.substring(1).toInt()
            }
            String(Character.toChars(code))
        }.getOrDefault(match.value)
    } else {
        NamedEntities[token.lowercase()] ?: match.value
    }
}

private fun attribute(attributes: String, name: String): String? {
    val match = Regex("(?i)\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").find(attributes) ?: return null
    return match.groupValues.subList(2, 5).firstOrNull { it.isNotEmpty() }
}

internal fun mailQuoteText(detail: MailMessageDetail): String {
    val text = detail.textBody.trim()
    if (text.isNotEmpty()) return text
    val html = detail.htmlBody.orEmpty().trim()
    if (html.isEmpty()) return ""
    return decodeEntities(stripHtmlNoise(html).replace(Regex("<[^>]+>"), " "))
        .replace(Regex("[ \\t\\u00a0]+"), " ")
        .replace(Regex(" ?\\n ?"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
