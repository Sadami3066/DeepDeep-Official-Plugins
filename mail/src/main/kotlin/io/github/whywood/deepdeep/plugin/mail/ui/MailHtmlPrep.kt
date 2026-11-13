package io.github.whywood.deepdeep.plugin.mail.ui

import java.net.URLDecoder

internal object MailHtmlPrep {
    private const val TRANSPARENT_PIXEL =
        "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

    private val doCall = Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE)
    private val script = Regex("(?is)<script\\b[^>]*>.*?</script\\s*>")
    private val framed = Regex("(?is)<(iframe|object|embed|form|frame|frameset)\\b[^>]*>.*?</\\1\\s*>")
    private val link = Regex("(?is)<link\\b[^>]*>")
    private val meta = Regex("(?is)<meta\\b[^>]*>")
    private val base = Regex("(?is)<base\\b[^>]*>")
    private val title = Regex("(?is)<title\\b[^>]*>.*?</title\\s*>")
    private val doctype = Regex("(?is)<!doctype[^>]*>")
    private val htmlTag = Regex("(?is)</?html\\b[^>]*>")
    private val headTag = Regex("(?is)</?head\\b[^>]*>")
    private val bodyTag = Regex("(?is)</?body\\b[^>]*>")
    private val eventAttribute = Regex("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val javascriptUrl = Regex("(?i)javascript\\s*:")
    private val cssImport = Regex("(?is)@import[^;]+;")
    private val cssExpression = Regex("(?i)expression\\s*\\(")
    private val cidAttribute = Regex(
        "(?i)(src|background)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
    )
    private val cidCss = Regex("(?i)url\\(\\s*[\"']?([^\"')]*)[\"']?\\s*\\)")

    fun prepare(html: String, inlineImages: Map<String, String>): String {
        val clean = sanitize(html)
        val withInline = rewriteCid(clean, inlineImages)
        return wrap(withInline)
    }

    fun sanitize(html: String): String {
        var value = html
            .replace(script, "")
            .replace(framed, "")
            .replace(link, "")
            .replace(meta, "")
            .replace(base, "")
            .replace(title, "")
            .replace(doctype, "")
            .replace(htmlTag, "")
            .replace(headTag, "")
            .replace(bodyTag, "")
            .replace(eventAttribute, "")
            .replace(javascriptUrl, "")
            .replace(cssImport, "")
            .replace(cssExpression, "")
        value = value.replace(
            Regex("(?i)(href|src)\\s*=\\s*([\"'])\\s*data:text/html[^\"']*\\2"),
            "$1=$2#$2",
        )
        return value
    }

    fun rewriteCid(html: String, inlineImages: Map<String, String>): String {
        if (!html.contains("cid:", ignoreCase = true)) return html
        val rewritten = cidAttribute.replace(html) { match ->
            val name = match.groupValues[1]
            val raw = listOf(2, 3, 4).firstNotNullOfOrNull { index ->
                match.groupValues[index].takeIf { it.isNotBlank() }
            }.orEmpty()
            if (!raw.startsWith("cid:", ignoreCase = true)) {
                match.value
            } else {
                "$name=\"${resolve(raw, inlineImages)}\""
            }
        }
        return cidCss.replace(rewritten) { match ->
            val raw = match.groupValues[1]
            if (!raw.startsWith("cid:", ignoreCase = true)) {
                match.value
            } else {
                "url(\"${resolve(raw, inlineImages)}\")"
            }
        }
    }

    fun isSafeLink(url: String): Boolean = doCall.containsMatchIn(url.trim())

    private fun resolve(raw: String, inlineImages: Map<String, String>): String {
        val contentId = decodeContentId(raw.removePrefix("cid:"))
        return inlineImages[contentId.lowercase()] ?: TRANSPARENT_PIXEL
    }

    fun decodeContentId(raw: String): String {
        val stripped = raw.trim().trim('<', '>').trim('"', '\'')
        val decoded = runCatching { URLDecoder.decode(stripped, Charsets.UTF_8.name()) }
            .getOrDefault(stripped)
        return decoded.trim()
    }

    private fun wrap(body: String): String = """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <style>
        html,body{margin:0;padding:0;background:#ffffff;}
        body{color:#1f2328;font-family:sans-serif;font-size:16px;line-height:1.55;word-break:break-word;overflow-wrap:anywhere;-webkit-text-size-adjust:100%;}
        img{max-width:100%!important;height:auto!important;}
        table{max-width:100%!important;border-collapse:collapse;}
        td,th{word-break:break-word;}
        a{color:#0b66c3;}
        pre{white-space:pre-wrap;word-break:break-word;}
        </style>
        <script>
        (function(){
          function report(){
            window.__ddHeight = Math.max(
              document.documentElement ? document.documentElement.scrollHeight : 0,
              document.body ? document.body.scrollHeight : 0
            );
          }
          report();
          if (window.ResizeObserver) {
            try { new ResizeObserver(report).observe(document.documentElement); } catch (e) {}
          }
          window.addEventListener('load', report);
          document.addEventListener('DOMContentLoaded', report);
          window.addEventListener('resize', report);
          setTimeout(report, 300);
          setTimeout(report, 1200);
          setTimeout(report, 2400);
        })();
        </script>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}
