package io.github.whywood.deepdeep.plugin.feishu

internal object FeishuLinks {
    private const val MAX_ENTRIES = 128

    private val URL = Regex("""https?://([A-Za-z0-9-]+)\.(?:feishu\.cn|larksuite\.com)/[^\s"'<>）)，。]+""")
    private val DOCX_PATH = Regex("""/docx/[^/?#]+""")

    private val urls = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_ENTRIES
    }

    private var docxHost: String? = null

    fun remember(text: String?) {
        val value = text?.takeIf { it.isNotBlank() } ?: return
        URL.findAll(value).forEach { match -> rememberUrl(match.value) }
    }

    fun rememberUrl(url: String?) {
        val value = url?.trim()?.takeIf { it.startsWith("http") } ?: return
        val match = URL.find(value) ?: return
        val host = match.groupValues[1]
        if (DOCX_PATH.containsMatchIn(match.value)) docxHost = host
        val token = tokenOf(match.value)
        if (token != null) urls[token] = match.value.trimEnd('。', '，', ')', '）')
    }

    fun resolve(doc: String?): String? {
        val value = doc?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) {
            rememberUrl(value)
            return value
        }
        urls[value]?.let { return it }
        val host = docxHost ?: return null
        return "https://$host/docx/$value"
    }

    fun clear() {
        urls.clear()
        docxHost = null
    }

    private fun tokenOf(url: String): String? =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
}
