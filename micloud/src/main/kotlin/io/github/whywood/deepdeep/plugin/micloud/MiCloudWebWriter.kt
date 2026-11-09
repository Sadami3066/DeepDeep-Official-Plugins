package io.github.whywood.deepdeep.plugin.micloud

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject

internal const val MICLOUD_ORIGIN = "https://i.mi.com"

private const val TIMEOUT_MS = 30_000L
private const val LOAD_TIMEOUT_MS = 20_000L
private const val IDLE_RELEASE_MS = 120_000L
private const val BRIDGE_NAME = "MiCloudWriterBridge"

internal fun miCloudUrl(path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val absolute = when {
        trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("/") -> "$MICLOUD_ORIGIN$trimmed"
        else -> return null
    }
    if (absolute != MICLOUD_ORIGIN && !absolute.startsWith("$MICLOUD_ORIGIN/")) return null
    if (absolute.any { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) return null
    return absolute
}

internal fun cookieValue(cookie: String?, name: String): String? {
    if (cookie.isNullOrBlank()) return null
    return cookie.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun encodeForm(form: Map<String, String>): String = form.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
}

internal fun isAuthFailure(status: Int, body: String): Boolean {
    if (status == 401 || status == 403) return true
    val json = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
    val code = (json["R"] as? JsonPrimitive)?.contentOrNull
        ?: (json["code"] as? JsonPrimitive)?.contentOrNull
    return code == "401" || code == "403"
}

internal fun clearMiCloudWebCookies() {
    val manager = CookieManager.getInstance()
    val cookie = runCatching { manager.getCookie(MICLOUD_ORIGIN) }.getOrNull().orEmpty()
    if (cookie.isNotBlank()) {
        parseCookie(cookie).keys.forEach { name ->
            listOf("", "Domain=.i.mi.com; ", "Domain=.mi.com; ").forEach { domain ->
                runCatching { manager.setCookie(MICLOUD_ORIGIN, "$name=; Max-Age=0; Path=/; $domain") }
            }
        }
    }
    manager.flush()
}

internal class MiCloudWebWriter(
    context: Context,
    private val cookieStore: MiCloudCookieStore,
    private val onAuthExpired: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var destroyed = false
    @Volatile private var pending: Pair<String, CompletableDeferred<FetchResponse>>? = null

    private var webView: WebView? = null
    private var loadGate: CompletableDeferred<Unit>? = null
    private var loadReady = false

    suspend fun post(path: String, form: Map<String, String>): String = mutex.withLock {
        mainHandler.removeCallbacks(idleRelease)
        try {
            if (destroyed) throw MiCloudAuthException("MiCloud writer is closed")
            val url = miCloudUrl(path) ?: throw IllegalArgumentException("Only i.mi.com requests are allowed")
            val token = currentServiceToken() ?: throw MiCloudAuthException("serviceToken is missing, sign in again")
            val body = encodeForm(form + ("serviceToken" to token))
            withTimeout(TIMEOUT_MS) { dispatch(url, body) }
        } finally {
            if (!destroyed) mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
        }
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacks(idleRelease)
        pending?.second?.cancel()
        pending = null
        mainHandler.post(::release)
    }

    private val idleRelease = Runnable { release() }

    private fun release() {
        webView?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
        webView = null
        loadGate?.cancel()
        loadGate = null
        loadReady = false
    }

    private suspend fun dispatch(url: String, body: String): String = withContext(Dispatchers.Main) {
        val view = loadedWebView()
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchResponse>()
        pending = id to deferred
        try {
            view.evaluateJavascript(fetchScript(id, url, body), null)
            val response = deferred.await()
            when {
                isAuthFailure(response.status, response.body) -> {
                    onAuthExpired()
                    throw MiCloudAuthException("MiCloud session expired, sign in again")
                }

                response.status == 0 ->
                    throw IOException(response.body.ifBlank { "network error" })

                response.status !in 200..299 ->
                    throw IOException("HTTP ${response.status}: ${response.body.take(300)}")

                else -> response.body
            }
        } finally {
            if (pending?.first == id) pending = null
        }
    }

    private suspend fun loadedWebView(): WebView {
        val cookie = cookieStore.read()
        return withContext(Dispatchers.Main) {
            if (destroyed) throw MiCloudAuthException("MiCloud writer is closed")
            val view = webView ?: createWebView(cookie).also { webView = it }
            if (loadReady) return@withContext view
            val gate = loadGate ?: CompletableDeferred<Unit>().also { created ->
                loadGate = created
                view.loadUrl(MICLOUD_ORIGIN)
            }
            try {
                withTimeout(LOAD_TIMEOUT_MS) { gate.await() }
            } catch (error: TimeoutCancellationException) {
                loadGate = null
                throw IOException("Timed out loading i.mi.com")
            } catch (error: IOException) {
                loadGate = null
                throw error
            }
            view
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(cookie: String?): WebView {
        val view = WebView(appContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(MICLOUD_ORIGIN)) {
                    loadReady = true
                    loadGate?.complete(Unit)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    loadGate?.completeExceptionally(
                        IOException(error.description?.toString() ?: "Failed to load i.mi.com"),
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                if (webView === view) handleRendererGone()
                return true
            }
        }
        restoreCookies(cookie)
        return view
    }

    private fun handleRendererGone() {
        val gate = loadGate
        loadGate = null
        loadReady = false
        gate?.completeExceptionally(IOException("i.mi.com renderer crashed"))
        val waiting = pending
        pending = null
        waiting?.second?.completeExceptionally(IOException("i.mi.com renderer crashed"))
        val dead = webView
        webView = null
        dead?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.removeAllViews() }
            runCatching { view.destroy() }
        }
    }

    private fun restoreCookies(cookie: String?) {
        if (cookie.isNullOrBlank()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        cookie.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair -> runCatching { manager.setCookie(MICLOUD_ORIGIN, pair) } }
        manager.flush()
    }

    private suspend fun currentServiceToken(): String? {
        val live = withContext(Dispatchers.Main) {
            runCatching { CookieManager.getInstance().getCookie(MICLOUD_ORIGIN) }.getOrNull()
        }
        return cookieValue(live, "serviceToken") ?: cookieStore.serviceToken()
    }

    private fun fetchScript(id: String, url: String, body: String): String = """
        (function () {
          var requestId = ${JSONObject.quote(id)};
          fetch(${JSONObject.quote(url)}, {
            method: 'POST',
            credentials: 'include',
            headers: {
              'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
              'X-Requested-With': 'XMLHttpRequest'
            },
            body: ${JSONObject.quote(body)}
          }).then(function (response) {
            return response.text().then(function (text) {
              $BRIDGE_NAME.onResult(JSON.stringify({
                id: requestId,
                status: response.status,
                body: text
              }));
            });
          }).catch(function (error) {
            $BRIDGE_NAME.onResult(JSON.stringify({
              id: requestId,
              status: 0,
              body: String((error && error.message) || error)
            }));
          });
        })();
    """.trimIndent()

    private data class FetchResponse(val status: Int, val body: String)

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(payload: String) {
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val current = pending ?: return
            if (current.first != json.optString("id")) return
            current.second.complete(
                FetchResponse(
                    status = json.optInt("status", 0),
                    body = json.optString("body"),
                ),
            )
        }
    }
}
