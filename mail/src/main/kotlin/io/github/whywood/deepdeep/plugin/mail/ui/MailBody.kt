package io.github.whywood.deepdeep.plugin.mail.ui

import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.mime.HtmlText

internal fun mailBodyHtml(detail: MailMessageDetail): String =
    mailBodyHtml(htmlBody = detail.htmlBody, textBody = detail.textBody)

internal fun mailBodyHtml(htmlBody: String?, textBody: String): String {
    val html = htmlBody?.trim().orEmpty()
    if (html.isNotEmpty() && html.contains('<')) return html
    return plainTextHtml(textBody)
}

internal fun mailQuoteText(detail: MailMessageDetail): String {
    val text = detail.textBody.trim()
    if (text.isNotEmpty()) return text
    return detail.htmlBody?.let(HtmlText::toPlainText).orEmpty().trim()
}

private val UrlPattern = Regex("https?://[^\\s<>\"'）)]+")

private val UrlTrailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', '，', '。', '；', '：', '！', '？', '）')

private fun plainTextHtml(text: String): String {
    if (text.isBlank()) return ""
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val builder = StringBuilder()
    var previousQuoted = false
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (isQuoteLine(line)) {
            val quoted = mutableListOf<String>()
            var depth = 1
            while (index < lines.size && isQuoteLine(lines[index])) {
                val raw = lines[index].trimStart()
                val lineDepth = raw.takeWhile { it == '>' }.length
                depth = maxOf(depth, lineDepth)
                quoted += raw.dropWhile { it == '>' }.removePrefix(" ")
                index++
            }
            builder.append("<blockquote>".repeat(depth.coerceAtMost(3)))
            quoted.forEachIndexed { quotedIndex, quotedLine ->
                if (quotedIndex > 0) builder.append("<br>")
                builder.append(escapeAndLink(quotedLine))
            }
            builder.append("</blockquote>".repeat(depth.coerceAtMost(3)))
            previousQuoted = true
            while (index < lines.size && lines[index].isBlank()) index++
            continue
        }
        if (builder.isNotEmpty() && !previousQuoted) builder.append("<br>")
        builder.append(escapeAndLink(line))
        previousQuoted = false
        index++
    }
    return builder.toString()
}

private fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

private fun escapeAndLink(text: String): String {
    if (text.isEmpty()) return text
    val builder = StringBuilder(text.length)
    var cursor = 0
    for (match in UrlPattern.findAll(text)) {
        if (match.range.first > cursor) builder.append(escapeHtml(text.substring(cursor, match.range.first)))
        val raw = match.value.trimEnd(*UrlTrailing)
        if (raw.isEmpty()) {
            builder.append(escapeHtml(match.value))
            cursor = match.range.last + 1
            continue
        }
        builder.append("<a href=\"").append(raw).append("\">")
        builder.append(escapeHtml(raw))
        builder.append("</a>")
        cursor = match.range.first + raw.length
    }
    if (cursor < text.length) builder.append(escapeHtml(text.substring(cursor)))
    return builder.toString()
}

private fun escapeHtml(text: String): String {
    if (text.none { it == '&' || it == '<' || it == '>' || it == '"' }) return text
    val builder = StringBuilder(text.length + 16)
    for (char in text) {
        when (char) {
            '&' -> builder.append("&amp;")
            '<' -> builder.append("&lt;")
            '>' -> builder.append("&gt;")
            '"' -> builder.append("&quot;")
            else -> builder.append(char)
        }
    }
    return builder.toString()
}
