package io.github.whywood.deepdeep.plugin.mail.ui

import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MaxHtmlHeightDp = 6000f

@Composable
internal fun MailHtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit,
) {
    val currentOpenUrl by rememberUpdatedState(onOpenUrl)
    var height by remember(html) { mutableStateOf(1.dp) }
    var webView by remember(html) { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(height),
        factory = { context ->
            createMailWebView(context, html, currentOpenUrl).also { webView = it }
        },
        onRelease = { view ->
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
            if (webView === view) webView = null
        },
    )
    LaunchedEffect(webView, html) {
        val view = webView ?: return@LaunchedEffect
        repeat(24) {
            delay(250)
            val measured = view.measureHtmlHeight()
            if (measured != null && measured > 1) {
                val next = measured.dp.coerceAtMost(MaxHtmlHeightDp.dp)
                if (next != height) height = next
            }
        }
    }
}

private fun createMailWebView(
    context: Context,
    html: String,
    onOpenUrl: (String) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(Color.WHITE)
    isNestedScrollingEnabled = true
    overScrollMode = View.OVER_SCROLL_NEVER
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        builtInZoomControls = false
        displayZoomControls = false
        loadWithOverviewMode = true
        useWideViewPort = true
        textZoom = 100
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        blockNetworkImage = false
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString().orEmpty()
            if (!url.startsWith("http://") &&
                !url.startsWith("https://") &&
                !url.startsWith("mailto:") &&
                !url.startsWith("tel:")
            ) {
                return false
            }
            runCatching { onOpenUrl(url) }
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (request.isForMainFrame) view.loadUrl("about:blank")
        }
    }
    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

private suspend fun WebView.measureHtmlHeight(): Int? =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val script = """
            (function() {
              var h = Math.max(
                document.documentElement ? document.documentElement.scrollHeight : 0,
                document.body ? document.body.scrollHeight : 0
              );
              return String(Math.ceil(h));
            })();
        """.trimIndent()
        evaluateJavascript(script) { value ->
            val text = value?.trim()?.removeSurrounding("\"")
            val parsed = text?.toFloatOrNull()?.toInt()
            if (continuation.isActive) continuation.resumeWith(Result.success(parsed))
        }
    }

internal suspend fun prepareMailWebHtml(
    repository: MailRepository,
    detail: MailMessageDetail,
): String {
    val html = mailBodyHtml(detail)
    if (html.isBlank()) return ""
    val inline = detail.attachments
        .filter { it.inline && !it.contentId.isNullOrBlank() && it.sizeBytes <= MaxInlineBytes }
        .mapNotNull { attachment ->
            val contentId = MailHtmlPrep.decodeContentId(attachment.contentId.orEmpty())
            if (contentId.isBlank()) return@mapNotNull null
            val path = runCatching {
                repository.loadInlineImage(detail.summary.accountId, detail.summary.id, contentId)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bytes = runCatching { withContext(Dispatchers.IO) { File(path).readBytes() } }.getOrNull()
                ?: return@mapNotNull null
            val mime = attachment.mimeType.takeIf { it.startsWith("image/") } ?: "image/png"
            contentId.lowercase() to "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }
        .toMap()
    return MailHtmlPrep.prepare(html, inline)
}

private const val MaxInlineBytes = 2_500_000L
