package io.github.whywood.deepdeep.plugin.micloud

import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdEmptyState
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdInfoTooltip
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdSwitch
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.DdWebView
import io.github.whywood.deepdeep.design.DdWebViewErrorKind
import io.github.whywood.deepdeep.design.DdWebViewState
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val SERVICE_TOKEN = "serviceToken"

@Composable
internal fun MiCloudPage(host: PluginHost, content: MiCloudContent) {
    val scope = rememberCoroutineScope()
    val capabilities by content.preferences.capabilities.collectAsState()
    var status by remember { mutableStateOf(content.lastStatus) }
    var busy by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginStage by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var logoutError by remember { mutableStateOf<String?>(null) }

    fun refreshStatus(force: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                status = content.resolveStatus(force)
            } finally {
                busy = false
            }
        }
    }

    fun logout() {
        if (busy) return
        busy = true
        logoutError = null
        scope.launch {
            try {
                content.logout()
                status = content.lastStatus
                host.ui().snackbar(host.t("已退出登录", "Signed out"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                logoutError = host.t("退出登录失败，请重试。", "Couldn't sign out. Try again.")
            } finally {
                busy = false
            }
        }
    }

    fun confirmLogout() {
        if (busy) return
        scope.launch {
            val confirmed = host.ui().confirm(
                uiConfirmRequest(
                    title = host.t("退出小米云登录？", "Sign out of Xiaomi Cloud?"),
                    message = host.t(
                        "退出后需要重新登录才能读取小米云数据。",
                        "You'll need to sign in again to read Xiaomi Cloud data.",
                    ),
                    confirmLabel = host.t("退出登录", "Sign out"),
                    cancelLabel = host.t("取消", "Cancel"),
                    destructive = true,
                ),
            )
            if (confirmed) logout()
        }
    }

    fun openLogin() {
        if (busy) return
        loginError = null
        loginStage = null
        logoutError = null
        loginOpen = true
    }

    fun finishLogin() {
        if (loginBusy) return
        loginError = null
        val cookie = currentWebCookie()
        if (cookie == null || cookieValue(cookie, SERVICE_TOKEN) == null) {
            loginError = host.t(
                "还没有完成登录，请先在上方页面登录小米账号。",
                "Sign-in isn't complete yet. Sign in to your Xiaomi account above.",
            )
            return
        }
        loginBusy = true
        loginStage = host.t("正在连接小米云…", "Connecting to Xiaomi Cloud…")
        scope.launch {
            try {
                content.verifyCookie(cookie)
                status = content.lastStatus
                logoutError = null
                loginOpen = false
                host.ui().snackbar(host.t("登录成功", "Signed in"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: MiCloudAuthException) {
                loginError = host.t(
                    "登录未成功，请重新登录后再试。",
                    "Sign-in didn't complete. Sign in again and retry.",
                )
            } catch (_: Exception) {
                loginError = host.t(
                    "无法连接小米云，请检查网络后重试。",
                    "Couldn't reach Xiaomi Cloud. Check your network and try again.",
                )
            } finally {
                loginStage = null
                loginBusy = false
            }
        }
    }

    fun toggle(feature: MiCloudFeature, enabled: Boolean) {
        if (busy) return
        scope.launch {
            content.preferences.setFeature(feature, enabled)
            host.requestContributionsRefresh()
        }
    }

    LaunchedEffect(Unit) {
        status = content.lastStatus
        content.ensureLoaded()
        refreshStatus(force = false)
    }

    val motionEnabled = LocalDdMotionEnabled.current
    AnimatedContent(
        targetState = loginOpen,
        transitionSpec = {
            if (!motionEnabled) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (targetState) {
                (fadeIn(tween(DdMotion.FAST)) +
                    slideInHorizontally(tween(DdMotion.FAST)) { width -> width / 10 }) togetherWith
                    fadeOut(tween(DdMotion.FAST))
            } else {
                fadeIn(tween(DdMotion.FAST)) togetherWith
                    (fadeOut(tween(DdMotion.FAST)) +
                        slideOutHorizontally(tween(DdMotion.FAST)) { width -> width / 10 })
            }
        },
        label = "micloud-page",
    ) { showLogin ->
        if (showLogin) {
            MiCloudLoginScreen(
                host = host,
                verifying = loginBusy,
                stage = loginStage,
                error = loginError,
                onComplete = ::finishLogin,
                onClose = { loginOpen = false },
            )
        } else {
            MiCloudHomeScreen(
                host = host,
                status = status,
                capabilities = capabilities,
                busy = busy,
                logoutError = logoutError,
                onOpenLogin = ::openLogin,
                onRefresh = { refreshStatus(force = true) },
                onRetryLogout = ::logout,
                onLogout = ::confirmLogout,
                onToggle = ::toggle,
            )
        }
    }
}

@Composable
private fun MiCloudHomeScreen(
    host: PluginHost,
    status: MiCloudSessionView?,
    capabilities: MiCloudCapabilities,
    busy: Boolean,
    logoutError: String?,
    onOpenLogin: () -> Unit,
    onRefresh: () -> Unit,
    onRetryLogout: () -> Unit,
    onLogout: () -> Unit,
    onToggle: (MiCloudFeature, Boolean) -> Unit,
) {
    val colors = deepDeepColors()
    val signedIn = isSignedIn(status)
    val expired = status?.state == MiCloudSessionState.EXPIRED
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DdGroupCard {
            Box(Modifier.padding(horizontal = 18.dp)) {
                DdStatusRow(
                    label = host.t("登录状态", "Sign-in status"),
                    value = if (status == null) {
                        host.t("检查中…", "Checking…")
                    } else {
                        statusValue(host, status)
                    },
                    tone = statusTone(status),
                    busy = busy,
                )
            }
            if (signedIn) {
                DdListRow(
                    title = host.t("刷新登录状态", "Refresh sign-in status"),
                    onClick = onRefresh,
                    enabled = !busy,
                    showChevron = true,
                )
            }
        }
        when {
            status?.state == MiCloudSessionState.UNVERIFIED -> DdNoticeBar(
                text = host.t(
                    "暂时无法连接小米云，请检查网络后重试。",
                    "Couldn't reach Xiaomi Cloud. Check your network and try again.",
                ),
                tone = DdNoticeTone.WARNING,
                actionLabel = host.t("重试", "Retry"),
                onAction = onRefresh,
            )

            expired -> DdNoticeBar(
                text = host.t("登录已失效，需要重新登录。", "Your sign-in expired. Sign in again."),
                tone = DdNoticeTone.DANGER,
            )

            !signedIn -> DdNoticeBar(
                text = host.t(
                    "登录小米账号后，就能读取云端的笔记、待办和个人数据。",
                    "Sign in to your Xiaomi account to read notes, to-dos and personal data from the cloud.",
                ),
                tone = DdNoticeTone.INFO,
            )
        }
        if (!signedIn) {
            DdPrimaryButton(
                text = if (expired) {
                    host.t("重新登录", "Sign in again")
                } else {
                    host.t("登录小米账号", "Sign in to Xiaomi")
                },
                onClick = onOpenLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdSectionLabel(host.t("笔记与待办", "Notes & to-dos"))
            DdInfoTooltip(
                text = host.t(
                    "开启后可读取和写入；修改前会先征求你的确认。",
                    "Enables reading and writing. You'll be asked before anything changes.",
                ),
                contentDescription = host.t("关于笔记与待办", "About notes and to-dos"),
            )
        }
        DdGroupCard {
            listOf(MiCloudFeature.NOTES, MiCloudFeature.TODOS).forEach { feature ->
                MiCloudFeatureRow(host, feature, capabilities, signedIn && !busy, onToggle)
            }
        }
        DdSectionLabel(host.t("个人数据", "Personal data"))
        DdGroupCard {
            listOf(
                MiCloudFeature.ACCOUNT,
                MiCloudFeature.SMS,
                MiCloudFeature.CONTACTS,
                MiCloudFeature.GALLERY,
                MiCloudFeature.RECORDINGS,
            ).forEach { feature ->
                MiCloudFeatureRow(host, feature, capabilities, signedIn && !busy, onToggle)
            }
        }
        if (signedIn) {
            logoutError?.let { message ->
                DdNoticeBar(
                    text = message,
                    tone = DdNoticeTone.DANGER,
                    actionLabel = host.t("重试", "Retry"),
                    onAction = onRetryLogout,
                )
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DdTextAction(
                    text = host.t("退出登录", "Sign out"),
                    onClick = onLogout,
                    color = colors.danger,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun MiCloudFeatureRow(
    host: PluginHost,
    feature: MiCloudFeature,
    capabilities: MiCloudCapabilities,
    enabled: Boolean,
    onToggle: (MiCloudFeature, Boolean) -> Unit,
) {
    DdListRow(
        title = featureName(host, feature),
        trailing = {
            DdSwitch(
                checked = capabilities.readEnabled(feature),
                onCheckedChange = { checked -> onToggle(feature, checked) },
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun MiCloudLoginScreen(
    host: PluginHost,
    verifying: Boolean,
    stage: String?,
    error: String?,
    onComplete: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = deepDeepColors()
    var webViewError by remember { mutableStateOf<String?>(null) }
    var webLoading by remember { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    val webState = remember(generation) { DdWebViewState() }

    fun reload() {
        webViewError = null
        webLoading = true
        generation++
    }

    fun handleBack() {
        if (webViewError == null && webState.canGoBack) {
            webState.goBack()
        } else {
            onClose()
        }
    }

    BackHandler { if (!verifying) handleBack() }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = ::handleBack,
                contentDescription = host.t("返回", "Back"),
                tint = colors.ink,
                iconSize = 21.dp,
                enabled = !verifying,
            )
            DdText(
                text = host.t("登录小米账号", "Sign in to Xiaomi"),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                style = DdType.heading,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(colors.paper)) {
            if (webViewError == null) {
                key(generation) {
                    DdWebView(
                        url = MICLOUD_ORIGIN,
                        modifier = Modifier.fillMaxSize(),
                        state = webState,
                        onPageStarted = { webLoading = true },
                        onPageFinished = { url ->
                            webLoading = false
                            if (url != null && isMiCloudHost(url)) {
                                webState.evaluateJavascript(VIEWPORT_FIX_SCRIPT, null)
                            }
                        },
                        onError = { failure ->
                            if (!verifying) {
                                webViewError = when (failure.kind) {
                                    DdWebViewErrorKind.RENDERER -> host.t(
                                        "登录页面已重置，请重试。",
                                        "The sign-in page was reset. Try again.",
                                    )

                                    else -> host.t(
                                        "页面加载失败，请检查网络后重试。",
                                        "The page didn't load. Check your network and try again.",
                                    )
                                }
                            }
                        },
                    )
                }
                if (webLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(colors.paper),
                        contentAlignment = Alignment.Center,
                    ) {
                        DdLoadingBlock()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DdEmptyState(
                        text = webViewError.orEmpty(),
                        icon = DdIcons.Globe,
                        action = {
                            DdPrimaryButton(
                                text = host.t("重新加载", "Reload"),
                                onClick = ::reload,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                DdNoticeBar(text = error, tone = DdNoticeTone.DANGER)
            }
            if (verifying) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    DdText(
                        text = stage ?: host.t("正在连接小米云…", "Connecting to Xiaomi Cloud…"),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    DdProgressBar(progress = null)
                }
            } else if (webViewError == null) {
                DdPrimaryButton(
                    text = host.t("完成登录", "Finish sign-in"),
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun isSignedIn(view: MiCloudSessionView?): Boolean =
    view?.state == MiCloudSessionState.VERIFIED || view?.state == MiCloudSessionState.UNVERIFIED

private fun statusValue(host: PluginHost, view: MiCloudSessionView?): String = when (view?.state) {
    MiCloudSessionState.VERIFIED -> view.nickname
        ?.takeIf { it.isNotBlank() }
        ?.let { host.tf("已登录 · %s", "Signed in · %s", it) }
        ?: host.t("已登录", "Signed in")

    MiCloudSessionState.UNVERIFIED -> host.t("已登录 · 暂时无法连接", "Signed in · can't connect")
    MiCloudSessionState.EXPIRED -> host.t("登录已失效", "Sign-in expired")
    MiCloudSessionState.SIGNED_OUT -> host.t("未登录", "Not signed in")
    null -> host.t("状态未知", "Status unknown")
}

private fun statusTone(view: MiCloudSessionView?): DdStatusTone = when (view?.state) {
    MiCloudSessionState.VERIFIED -> DdStatusTone.SUCCESS
    MiCloudSessionState.UNVERIFIED -> DdStatusTone.WARNING
    MiCloudSessionState.EXPIRED -> DdStatusTone.DANGER
    MiCloudSessionState.SIGNED_OUT, null -> DdStatusTone.NEUTRAL
}

private fun featureName(host: PluginHost, feature: MiCloudFeature): String = when (feature) {
    MiCloudFeature.NOTES -> host.t("笔记", "Notes")
    MiCloudFeature.TODOS -> host.t("待办", "To-dos")
    MiCloudFeature.SMS -> host.t("短信与通话", "SMS and calls")
    MiCloudFeature.CONTACTS -> host.t("联系人", "Contacts")
    MiCloudFeature.GALLERY -> host.t("相册", "Gallery")
    MiCloudFeature.RECORDINGS -> host.t("录音", "Recordings")
    MiCloudFeature.ACCOUNT -> host.t("账号信息", "Account info")
}

private fun currentWebCookie(): String? = runCatching {
    CookieManager.getInstance().getCookie(MICLOUD_ORIGIN)
}.getOrNull()?.takeIf { it.isNotBlank() }

private fun isMiCloudHost(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    return host.endsWith("mi.com") || host.endsWith("xiaomi.com")
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
