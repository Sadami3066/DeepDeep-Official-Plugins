package io.github.whywood.deepdeep.plugin.mail.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailHtmlPrepTest {
    @Test
    fun stripsExecutableMarkup() {
        val html = """
            <p onclick="steal()">Hello</p>
            <script>alert('x')</script>
            <iframe src="https://example.com"></iframe>
            <a href="javascript:alert(1)">click</a>
        """.trimIndent()

        val sanitized = MailHtmlPrep.sanitize(html)

        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("<iframe", ignoreCase = true))
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
        assertTrue(sanitized.contains("Hello"))
    }

    @Test
    fun stripsUnclosedOrRemoteScriptTags() {
        val html = """<script src="https://evil.example/x.js"></script><script>alert(1)"""

        val sanitized = MailHtmlPrep.sanitize(html)

        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("</script", ignoreCase = true))
    }

    @Test
    fun rewritesCidAttributesAndCssUrls() {
        val html = """
            <img src="cid:logo%401">
            <div style="background-image: url(cid:hero@2)"></div>
        """.trimIndent()
        val inline = mapOf(
            "logo@1" to "data:image/png;base64,AAA",
            "hero@2" to "data:image/png;base64,BBB",
        )

        val rewritten = MailHtmlPrep.rewriteCid(html, inline)

        assertTrue(rewritten.contains("src=\"data:image/png;base64,AAA\""))
        assertTrue(rewritten.contains("url(\"data:image/png;base64,BBB\")"))
        assertFalse(rewritten.contains("cid:", ignoreCase = true))
    }

    @Test
    fun missingInlineImageFallsBackToTransparentPixel() {
        val rewritten = MailHtmlPrep.rewriteCid("<img src=\"cid:missing\">", emptyMap())

        assertTrue(rewritten.contains("data:image/gif;base64"))
    }

    @Test
    fun prepWrapsDocumentWithViewportAndStyles() {
        val prepared = MailHtmlPrep.prepare("<p>Hello</p>", emptyMap())

        assertTrue(prepared.contains("name=\"viewport\""))
        assertTrue(prepared.contains("max-width:100%!important"))
        assertTrue(prepared.contains("<p>Hello</p>"))
    }

    @Test
    fun decodeContentIdUnwrapsAngleBracketsAndEncoding() {
        assertTrue(MailHtmlPrep.decodeContentId("<logo%401>") == "logo@1")
        assertTrue(MailHtmlPrep.decodeContentId("plain@example.com") == "plain@example.com")
    }

    @Test
    fun safeLinkOnlyAllowsExpectedSchemes() {
        assertTrue(MailHtmlPrep.isSafeLink("https://example.com"))
        assertTrue(MailHtmlPrep.isSafeLink("http://example.com"))
        assertTrue(MailHtmlPrep.isSafeLink("mailto:a@example.com"))
        assertFalse(MailHtmlPrep.isSafeLink("javascript:alert(1)"))
        assertFalse(MailHtmlPrep.isSafeLink("intent://example"))
    }
}
