package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.launch

private const val ShownScopeLimit = 6

@Composable
internal fun FeishuPage(host: PluginHost, runtime: FeishuRuntime) {
    val state by runtime.pageState.collectAsState()
    val linuxAvailable = runCatching { host.linux().available }.getOrDefault(false)
    val motionEnabled = LocalDdMotionEnabled.current
    LaunchedEffect(Unit) {
        if (runtime.pageState.value.operation == FeishuOperation.NONE) {
            runtime.refresh(host)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = DdSpacing.Xl,
                end = DdSpacing.Xl,
                top = DdSpacing.Md,
                bottom = DdSpacing.Xxl,
            ),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        StatusSummary(host = host, state = state, linuxAvailable = linuxAvailable)
        Crossfade(
            targetState = state.operation,
            animationSpec = motionFadeSpec(motionEnabled),
            label = "feishu-operation",
        ) { operation ->
            when (operation) {
                FeishuOperation.INSTALL -> InstallProgress(host = host, runtime = runtime, state = state)
                FeishuOperation.CONNECT -> ConnectProgress(host, runtime, state, motionEnabled)
                FeishuOperation.LOGOUT -> SignOutProgress(host = host, state = state)
                FeishuOperation.NONE -> IdleContent(host, runtime, state, linuxAvailable, motionEnabled)
            }
        }
    }
}

@Composable
private fun StatusSummary(host: PluginHost, state: FeishuPageState, linuxAvailable: Boolean) {
    val snapshot = state.snapshot
    val checked = snapshot != null
    val checking = state.checking
    val version = snapshot?.installedVersion
    val connected = snapshot?.connected == true
    val account = snapshot?.account?.takeIf { it.isNotBlank() }
    val unknownValue = if (checking) {
        host.t("检查中…", "Checking…")
    } else {
        host.t("检查失败", "Check failed")
    }
    val unknownTone = if (checking) DdStatusTone.NEUTRAL else DdStatusTone.WARNING
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Xs),
        ) {
        DdStatusRow(
            label = host.t("运行环境", "Runtime"),
            value = if (linuxAvailable) host.t("已就绪", "Ready") else host.t("不可用", "Unavailable"),
            tone = if (linuxAvailable) DdStatusTone.SUCCESS else DdStatusTone.DANGER,
        )
        DdStatusRow(
            label = host.t("飞书 CLI", "Feishu CLI"),
            value = when {
                version != null -> "v$version"
                checked -> host.t("未安装", "Not installed")
                else -> unknownValue
            },
            tone = when {
                version == FEISHU_VERSION -> DdStatusTone.SUCCESS
                version != null -> DdStatusTone.WARNING
                checked -> DdStatusTone.NEUTRAL
                else -> unknownTone
            },
            busy = checking && !checked,
        )
        DdStatusRow(
            label = host.t("账号", "Account"),
            value = when {
                connected -> account ?: host.t("已登录", "Signed in")
                checked -> host.t("未登录", "Not signed in")
                else -> unknownValue
            },
            tone = when {
                connected -> DdStatusTone.SUCCESS
                checked -> DdStatusTone.NEUTRAL
                else -> unknownTone
            },
            busy = checking && !checked,
        )
        DdStatusRow(
            label = host.t("连接状态", "Connection"),
            value = when {
                connected -> host.t("已连接", "Connected")
                checked -> host.t("未连接", "Not connected")
                else -> unknownValue
            },
            tone = when {
                connected -> DdStatusTone.SUCCESS
                checked -> DdStatusTone.NEUTRAL
                else -> unknownTone
            },
            busy = checking && !checked,
        )
        }
    }
}

@Composable
private fun IdleContent(
    host: PluginHost,
    runtime: FeishuRuntime,
    state: FeishuPageState,
    linuxAvailable: Boolean,
    motionEnabled: Boolean,
) {
    val snapshot = state.snapshot
    val version = snapshot?.installedVersion
    val connected = snapshot?.connected == true
    val problem = state.error ?: snapshot?.message
    val problemTone = if (state.error != null) DdNoticeTone.DANGER else DdNoticeTone.WARNING
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg)) {
        MotionReveal(visible = problem != null, motionEnabled = motionEnabled) {
            problem?.let { message ->
                DdNoticeBar(
                    text = message,
                    tone = problemTone,
                    actionLabel = host.t("重试", "Retry"),
                    onAction = { runtime.retry(host) },
                )
            }
        }
        when {
            !linuxAvailable -> DdNoticeBar(
                text = host.text(UNAVAILABLE_LINUX),
                tone = DdNoticeTone.WARNING,
                actionLabel = host.t("重新检查", "Check again"),
                onAction = { runtime.check(host) },
            )

            snapshot == null -> Unit
            version == null -> InstallAction(host = host, runtime = runtime)
            else -> {
                if (version != FEISHU_VERSION) {
                    DdNoticeBar(
                        text = host.tf("有新版本 v%s 可用。", "Version v%s is available.", FEISHU_VERSION),
                        tone = DdNoticeTone.INFO,
                        actionLabel = host.t("更新", "Update"),
                        onAction = { runtime.startInstall(host) },
                    )
                }
                if (connected) {
                    AccountSection(host = host, runtime = runtime, snapshot = snapshot)
                } else {
                    ConnectAction(host = host, runtime = runtime)
                }
            }
        }
    }
}

@Composable
private fun InstallAction(host: PluginHost, runtime: FeishuRuntime) {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
        DdPrimaryButton(
            text = host.t("安装飞书 CLI", "Install Feishu CLI"),
            onClick = { runtime.startInstall(host) },
            modifier = Modifier.fillMaxWidth(),
        )
        DdText(
            text = host.t("安装后即可连接飞书账号。", "Install it, then connect your Feishu account."),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun ConnectAction(host: PluginHost, runtime: FeishuRuntime) {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
        DdPrimaryButton(
            text = host.t("连接飞书", "Connect Feishu"),
            onClick = { runtime.startConnect(host) },
            modifier = Modifier.fillMaxWidth(),
        )
        DdText(
            text = host.t(
                "连接后即可在对话中读写飞书消息、日历和云文档。",
                "Connect to read and write Feishu messages, calendar and docs in chat.",
            ),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun AccountSection(host: PluginHost, runtime: FeishuRuntime, snapshot: FeishuSnapshot) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val missing = snapshot.missingScopes
    if (missing.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
            DdSectionLabel(host.t("未开通的权限", "Permissions to grant"))
            DdGroupCard {
                missing.take(ShownScopeLimit).forEach { name ->
                    DdListRow(
                        title = name,
                        value = host.t("未开通", "Not granted"),
                    )
                }
                if (missing.size > ShownScopeLimit) {
                    DdListRow(
                        title = host.tf("还有 %d 项未开通", "%d more not granted", missing.size - ShownScopeLimit),
                    )
                }
            }
        }
    }
    if (snapshot.appConfigured) {
        DdGroupCard {
            DdListRow(
                title = host.t("应用权限", "App permissions"),
                subtitle = if (missing.isEmpty()) {
                    host.t("在飞书开放平台管理", "Manage in the Feishu developer console")
                } else {
                    host.t("开通后重新登录即可生效", "They apply after signing in again")
                },
                value = snapshot.scopeCount.takeIf { it > 0 }?.let {
                    host.tf("已授权 %d 项", "%d granted", it)
                },
                showChevron = true,
                onClick = { runtime.openAppScopes(host) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdTextAction(
            text = host.t("重新登录", "Sign in again"),
            onClick = {
                scope.launch {
                    val confirmed = host.ui().confirm(
                        uiConfirmRequest(
                            title = host.t("重新登录飞书账号？", "Sign in again?"),
                            message = host.t("当前登录会切换到新的飞书账号。", "This switches to a new Feishu account."),
                            confirmLabel = host.t("重新登录", "Sign in again"),
                            cancelLabel = host.t("取消", "Cancel"),
                        ),
                    )
                    if (confirmed) runtime.startConnect(host)
                }
            },
        )
        DdTextAction(
            text = host.t("退出登录", "Sign out"),
            color = colors.danger,
            onClick = {
                scope.launch {
                    val confirmed = host.ui().confirm(
                        uiConfirmRequest(
                            title = host.t("退出飞书登录？", "Sign out of Feishu?"),
                            message = host.t(
                                "退出后需要重新登录才能继续使用飞书能力。",
                                "You'll need to sign in again to use Feishu.",
                            ),
                            confirmLabel = host.t("退出登录", "Sign out"),
                            cancelLabel = host.t("取消", "Cancel"),
                            destructive = true,
                        ),
                    )
                    if (confirmed) runtime.startLogout(host)
                }
            },
        )
    }
}

@Composable
private fun InstallProgress(host: PluginHost, runtime: FeishuRuntime, state: FeishuPageState) {
    OperationCard(
        host = host,
        stage = state.stage?.let { host.text(it) },
        progress = state.progress,
        cancelling = state.cancelling,
        onCancel = { runtime.cancelOperation(host) },
    )
}

@Composable
private fun ConnectProgress(
    host: PluginHost,
    runtime: FeishuRuntime,
    state: FeishuPageState,
    motionEnabled: Boolean,
) {
    OperationCard(
        host = host,
        stage = state.stage?.let { host.text(it) },
        progress = state.progress,
        cancelling = state.cancelling,
        onCancel = { runtime.cancelOperation(host) },
    ) {
        AuthLink(host = host, runtime = runtime, state = state, motionEnabled = motionEnabled)
    }
}

@Composable
private fun SignOutProgress(host: PluginHost, state: FeishuPageState) {
    OperationCard(
        host = host,
        stage = state.stage?.let { host.text(it) },
        progress = null,
        cancelling = false,
        onCancel = null,
    )
}

@Composable
private fun OperationCard(
    host: PluginHost,
    stage: String?,
    progress: Int?,
    cancelling: Boolean,
    onCancel: (() -> Unit)?,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = stage ?: host.t("正在处理…", "Working…"),
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                )
                if (progress != null && !cancelling) {
                    DdText(
                        text = "$progress%",
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
            }
            DdProgressBar(progress = if (cancelling) null else progress?.let { it / 100f })
            content?.invoke()
            if (onCancel != null && !cancelling) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DdTextAction(
                        text = host.t("取消", "Cancel"),
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthLink(
    host: PluginHost,
    runtime: FeishuRuntime,
    state: FeishuPageState,
    motionEnabled: Boolean,
) {
    val colors = deepDeepColors()
    val url = state.authUrl
    val creatingApp = state.stage == STAGE_CREATE_APP
    MotionReveal(visible = url != null, motionEnabled = motionEnabled) {
        url?.let { value ->
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
                DdText(
                    text = host.t(
                        if (creatingApp) {
                            "在浏览器中创建应用并完成授权，完成后这里会自动继续。"
                        } else {
                            "在浏览器中完成授权，完成后这里会自动继续。"
                        },
                        if (creatingApp) {
                            "Create and authorize the app in the browser; this page continues automatically."
                        } else {
                            "Authorize in the browser; this page continues automatically."
                        },
                    ),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                DdText(
                    text = value,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    DdTextAction(
                        text = host.t("打开浏览器", "Open in browser"),
                        onClick = { runtime.openAuthUrl(host) },
                    )
                    DdTextAction(
                        text = host.t("复制链接", "Copy link"),
                        onClick = { runtime.copyAuthUrl(host) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionReveal(visible: Boolean, motionEnabled: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motionFadeSpec(motionEnabled)) + expandVertically(animationSpec = motionSizeSpec(motionEnabled)),
        exit = fadeOut(motionFadeSpec(motionEnabled)) + shrinkVertically(animationSpec = motionSizeSpec(motionEnabled)),
    ) {
        content()
    }
}

private fun motionFadeSpec(motionEnabled: Boolean): FiniteAnimationSpec<Float> =
    if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap()

private fun motionSizeSpec(motionEnabled: Boolean): FiniteAnimationSpec<IntSize> =
    if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap()
