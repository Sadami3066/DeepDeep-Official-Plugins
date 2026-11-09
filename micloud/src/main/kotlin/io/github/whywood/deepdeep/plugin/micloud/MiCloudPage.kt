package io.github.whywood.deepdeep.plugin.micloud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSwitch
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val COOKIE_POLL_MS = 1_000L

private const val SERVICE_TOKEN = "serviceToken"

@Composable
internal fun MiCloudPage(host: PluginHost, content: MiCloudContent) {
    val scope = rememberCoroutineScope()
    val capabilities by content.preferences.capabilities.collectAsState()
    var status by remember { mutableStateOf(content.lastStatus) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loginMode by remember { mutableStateOf(false) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }

    fun refreshStatus(force: Boolean) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                status = content.resolveStatus(force)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = host.text(MiCloudTexts.statusRefreshFailed)
            } finally {
                busy = false
            }
        }
    }

    fun startLogin(clearExisting: Boolean) {
        if (busy) return
        message = null
        webViewError = null
        webViewGeneration++
        if (!clearExisting) {
            loginMode = true
            return
        }
        busy = true
        scope.launch {
            try {
                content.logout()
                status = content.lastStatus
                loginMode = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = host.text(MiCloudTexts.logoutFailed)
            } finally {
                busy = false
            }
        }
    }

    fun finishLogin() {
        if (busy) return
        val cookie = currentWebCookie()
        if (cookie == null || cookieValue(cookie, SERVICE_TOKEN) == null) {
            message = host.text(MiCloudTexts.loginNotCompleted)
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                content.verifyCookie(cookie)
                status = content.lastStatus
                loginMode = false
                host.requestContributionsRefresh()
                message = host.text(MiCloudTexts.loginSucceeded)
            } catch (error: CancellationException) {
                throw error
            } catch (_: MiCloudAuthException) {
                message = host.text(MiCloudTexts.loginVerifyFailed)
            } catch (_: Exception) {
                message = host.text(MiCloudTexts.loginVerifyError)
            } finally {
                busy = false
            }
        }
    }

    fun logout() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                content.logout()
                status = content.lastStatus
                host.requestContributionsRefresh()
                message = host.text(MiCloudTexts.loggedOut)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = host.text(MiCloudTexts.logoutFailed)
            } finally {
                busy = false
            }
        }
    }

    fun toggle(feature: MiCloudFeature, enabled: Boolean) {
        scope.launch {
            content.preferences.setFeature(feature, enabled)
            host.requestContributionsRefresh()
        }
    }

    LaunchedEffect(Unit) {
        status = content.lastStatus
        busy = true
        try {
            status = content.resolveStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        } finally {
            busy = false
        }
    }

    LaunchedEffect(loginMode, webViewGeneration) {
        if (!loginMode) return@LaunchedEffect
        var tried: String? = null
        while (isActive) {
            delay(COOKIE_POLL_MS)
            if (busy) continue
            val cookie = currentWebCookie() ?: continue
            if (cookieValue(cookie, SERVICE_TOKEN) == null || cookie == tried) continue
            tried = cookie
            busy = true
            try {
                content.verifyCookie(cookie)
                status = content.lastStatus
                loginMode = false
                message = host.text(MiCloudTexts.loginSucceeded)
                break
            } catch (error: CancellationException) {
                throw error
            } catch (_: MiCloudAuthException) {
                message = host.text(MiCloudTexts.loginVerifyFailed)
            } catch (_: Exception) {
                message = host.text(MiCloudTexts.loginVerifyError)
            } finally {
                busy = false
            }
        }
    }

    if (loginMode) {
        val colors = deepDeepColors()
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = host.text(MiCloudTexts.loginTitle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                style = DdType.heading,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White),
            ) {
                if (webViewError == null) {
                    key(webViewGeneration) {
                        AndroidView(
                            factory = { context ->
                                createLoginWebView(
                                    context = context,
                                    onPageError = { webViewError = host.text(MiCloudTexts.pageError) },
                                    onRendererGone = { webViewError = host.text(MiCloudTexts.rendererError) },
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { view -> destroyWebView(view) },
                        )
                    }
                    if (busy) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = webViewError.orEmpty(),
                            style = DdType.bodySmall,
                            color = colors.danger,
                        )
                        Spacer(Modifier.height(12.dp))
                        DdPrimaryButton(
                            text = host.text(MiCloudTexts.retry),
                            onClick = {
                                webViewError = null
                                webViewGeneration++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            message?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdPrimaryButton(
                    text = host.text(MiCloudTexts.completeLogin),
                    onClick = ::finishLogin,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
                DdTextAction(
                    text = host.text(MiCloudTexts.cancel),
                    onClick = {
                        loginMode = false
                        message = null
                    },
                )
            }
        }
    } else {
        val colors = deepDeepColors()
        val signedIn = isSignedIn(status)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DdGroupCard {
                DdListRow(
                    title = host.text(MiCloudTexts.pageTitle),
                    subtitle = status?.nickname
                        ?.takeIf { it.isNotBlank() }
                        ?.let { host.text(MiCloudTexts.accountLabel, it) },
                    value = host.text(statusLabel(status)),
                )
                if (signedIn) {
                    DdListRow(
                        title = host.text(MiCloudTexts.logout),
                        onClick = ::logout,
                        enabled = !busy,
                    )
                    DdListRow(
                        title = host.text(MiCloudTexts.relogin),
                        onClick = { startLogin(clearExisting = true) },
                        enabled = !busy,
                    )
                } else {
                    val label = if (status?.state == MiCloudSessionState.EXPIRED) {
                        MiCloudTexts.relogin
                    } else {
                        MiCloudTexts.login
                    }
                    DdListRow(
                        title = host.text(label),
                        onClick = { startLogin(clearExisting = false) },
                        enabled = !busy,
                    )
                }
                DdListRow(
                    title = host.text(MiCloudTexts.refreshStatus),
                    onClick = { refreshStatus(force = true) },
                    enabled = !busy,
                )
            }
            if (busy) DdLoadingBlock()
            if (!signedIn) {
                Text(
                    text = host.text(MiCloudTexts.loginRequired),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
            DdSectionLabel(host.text(MiCloudTexts.featuresTitle))
            DdGroupCard {
                MiCloudFeature.entries.forEach { feature ->
                    DdListRow(
                        title = host.text(featureLabel(feature)),
                        trailing = {
                            DdSwitch(
                                checked = capabilities.readEnabled(feature),
                                onCheckedChange = { enabled -> toggle(feature, enabled) },
                                enabled = signedIn && !busy,
                            )
                        },
                    )
                }
            }
            Text(
                text = host.text(MiCloudTexts.writeHint),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
            message?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

private fun isSignedIn(view: MiCloudSessionView?): Boolean =
    view?.state == MiCloudSessionState.VERIFIED || view?.state == MiCloudSessionState.UNVERIFIED

private fun statusLabel(view: MiCloudSessionView?): LocalizedText = when (view?.state) {
    MiCloudSessionState.VERIFIED -> MiCloudTexts.statusSignedIn
    MiCloudSessionState.SIGNED_OUT -> MiCloudTexts.statusSignedOut
    MiCloudSessionState.EXPIRED -> MiCloudTexts.statusExpired
    MiCloudSessionState.UNVERIFIED -> MiCloudTexts.statusUnverified
    null -> MiCloudTexts.statusUnknown
}

private fun featureLabel(feature: MiCloudFeature): LocalizedText = when (feature) {
    MiCloudFeature.NOTES -> MiCloudTexts.featureNotes
    MiCloudFeature.TODOS -> MiCloudTexts.featureTodos
    MiCloudFeature.SMS -> MiCloudTexts.featureSms
    MiCloudFeature.CONTACTS -> MiCloudTexts.featureContacts
    MiCloudFeature.GALLERY -> MiCloudTexts.featureGallery
    MiCloudFeature.RECORDINGS -> MiCloudTexts.featureRecordings
    MiCloudFeature.ACCOUNT -> MiCloudTexts.featureAccount
}

private fun currentWebCookie(): String? = runCatching {
    CookieManager.getInstance().getCookie(MICLOUD_ORIGIN)
}.getOrNull()?.takeIf { it.isNotBlank() }

@SuppressLint("SetJavaScriptEnabled")
private fun createLoginWebView(
    context: Context,
    onPageError: () -> Unit,
    onRendererGone: () -> Unit,
): WebView {
    val view = WebView(context)
    view.setBackgroundColor(AndroidColor.WHITE)
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(view, true)
    }
    view.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            if (!host.endsWith("mi.com") && !host.endsWith("xiaomi.com")) return
            view.evaluateJavascript(VIEWPORT_FIX_SCRIPT, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            onPageError()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            onRendererGone()
            return true
        }
    }
    view.loadUrl(MICLOUD_ORIGIN)
    return view
}

private fun destroyWebView(view: WebView) {
    runCatching { view.stopLoading() }
    runCatching { view.removeAllViews() }
    runCatching { view.destroy() }
}

private val VIEWPORT_FIX_SCRIPT = """
    (function () {
      var probe = document.createElement('div');
      probe.style.cssText = 'position:fixed;left:0;top:0;width:0;height:100vh;visibility:hidden;';
      (document.body || document.documentElement).appendChild(probe);
      var viewportHeight = probe.getBoundingClientRect().height;
      if (probe.parentNode) probe.parentNode.removeChild(probe);
      if (viewportHeight !== 0 || window.innerHeight <= 0) return;
      var style = document.getElementById('deepdeep-viewport-fix');
      if (!style) {
        style = document.createElement('style');
        style.id = 'deepdeep-viewport-fix';
        (document.head || document.documentElement).appendChild(style);
      }
      var height = window.innerHeight;
      style.textContent = 'html,body,#root,#app,.micloud-app{height:auto!important;min-height:' +
        height + 'px!important}body{overflow:auto!important}';
    })();
""".trimIndent()

internal object MiCloudTexts {
    val pageTitle = literal("小米云服务", "Xiaomi Cloud")
    val statusUnknown = literal("登录状态未检查", "Sign-in status not checked")
    val statusSignedIn = literal("已登录", "Signed in")
    val statusSignedOut = literal("未登录", "Not signed in")
    val statusExpired = literal("登录已失效，请重新登录", "Sign-in expired, sign in again")
    val statusUnverified = literal("已登录（未联网校验）", "Signed in (not verified online)")
    val accountLabel = literal("账号：%s", "Account: %s")
    val login = literal("登录", "Sign in")
    val relogin = literal("重新登录", "Sign in again")
    val logout = literal("退出登录", "Sign out")
    val refreshStatus = literal("刷新登录状态", "Refresh status")
    val loginTitle = literal("登录小米云服务账号", "Sign in to Xiaomi")
    val completeLogin = literal("完成登录", "Finish sign-in")
    val cancel = literal("取消", "Cancel")
    val retry = literal("重试", "Retry")
    val loginSucceeded = literal("登录成功", "Signed in")
    val loggedOut = literal("已退出登录", "Signed out")
    val logoutFailed = literal("退出登录失败", "Failed to sign out")
    val statusRefreshFailed = literal("刷新登录状态失败，请检查网络", "Failed to refresh sign-in status; check your network")
    val loginNotCompleted = literal(
        "尚未完成登录，请先在页面中登录小米账号",
        "Sign-in is not complete yet; sign in to your Xiaomi account on the page first",
    )
    val loginVerifyFailed = literal("登录校验失败，请重试", "Sign-in verification failed; try again")
    val loginVerifyError = literal(
        "已登录，但校验小米云会话失败；请检查网络后重试",
        "Signed in, but verifying the Mi Cloud session failed. Check your network and try again.",
    )
    val pageError = literal("页面加载失败，请检查网络后重试", "Page failed to load; check your network and retry")
    val rendererError = literal("登录页面已重置，请重试", "The sign-in page was reset; try again")
    val featuresTitle = literal("云数据开关", "Cloud data")
    val loginRequired = literal(
        "登录小米账号后可开启这些功能",
        "Sign in to your Xiaomi account to enable these features",
    )
    val writeHint = literal(
        "笔记与待办开关同时开启读写；新建、修改或删除前需向用户确认。",
        "Notes and to-dos switches enable both reading and writing; confirm with the user before creating, updating or deleting.",
    )
    val featureNotes = literal("笔记", "Notes")
    val featureTodos = literal("待办", "To-dos")
    val featureSms = literal("短信与通话", "SMS and calls")
    val featureContacts = literal("联系人", "Contacts")
    val featureGallery = literal("相册", "Gallery")
    val featureRecordings = literal("录音", "Recordings")
    val featureAccount = literal("账号信息", "Account info")
}

private fun literal(zh: String, en: String): LocalizedText =
    LocalizedText.Literal(mapOf("default" to zh, "zh" to zh, "en" to en))
