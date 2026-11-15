package io.github.whywood.deepdeep.plugin.feishu

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
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun FeishuPage(host: PluginHost, runtime: FeishuRuntime) {
    val state by runtime.pageState.collectAsState()
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val linuxAvailable = runCatching { host.linux().available }.getOrDefault(false)
    LaunchedEffect(Unit) {
        if (runtime.pageState.value.operation == FeishuOperation.NONE) {
            runtime.refresh(host)
        }
    }
    val snapshot = state.snapshot
    val busy = state.operation != FeishuOperation.NONE
    val checked = snapshot != null
    val version = snapshot?.installedVersion
    val installed = version != null
    val connected = snapshot?.connected == true
    val latestVersion = state.latestVersion
    val updateChecking = state.updateChecking
    val updateFailed = installed && !updateChecking && latestVersion == null && state.lastCheckedAt > 0
    val canUpdate = version != null && FeishuUpdate.isNewer(latestVersion ?: FEISHU_VERSION, version)
    val checkingFirstTime = !checked && state.checking
    val checkFailed = !checked && !state.checking
    val problem = state.error
        ?: snapshot?.message
        ?: if (checkFailed) {
            host.t("无法读取飞书状态，请重试。", "Couldn't read the Feishu status. Try again.")
        } else {
            null
        }
    val problemTone = if (state.error != null || checkFailed) DdNoticeTone.DANGER else DdNoticeTone.WARNING

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
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
            DdText(
                text = host.t("飞书", "Feishu"),
                style = DdType.heading,
                color = colors.ink,
            )
            DdText(
                text = host.t(
                    "连接后可以在对话里读写消息、日历和云文档。",
                    "Connect to read and write Feishu messages, calendar and docs in chat.",
                ),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }

        StatusCard(host = host, state = state, linuxAvailable = linuxAvailable)

        if (checked && !linuxAvailable) {
            DdNoticeBar(
                text = host.text(UNAVAILABLE_LINUX),
                tone = DdNoticeTone.WARNING,
                actionLabel = host.t("重新检查", "Check again"),
                onAction = { runtime.check(host) },
            )
        }

        problem?.let { message ->
            DdNoticeBar(
                text = message,
                tone = problemTone,
                actionLabel = host.t("重试", "Retry"),
                onAction = { runtime.retry(host) },
            )
        }

        if (canUpdate && !busy && linuxAvailable) {
            DdNoticeBar(
                text = host.text(UPDATE_NOTICE),
                tone = DdNoticeTone.INFO,
                actionLabel = host.text(UPDATE_ACTION),
                onAction = { runtime.startUpdate(host) },
            )
        }

        if (updateFailed && !canUpdate && !busy && linuxAvailable) {
            DdNoticeBar(
                text = host.text(UPDATE_FAILED_NOTICE),
                tone = DdNoticeTone.WARNING,
                actionLabel = host.text(UPDATE_CHECK_AGAIN),
                onAction = { runtime.requestUpdateCheck(host, force = true) },
            )
        }

        if (snapshot != null && snapshot.appConfigured && snapshot.missingScopes.isNotEmpty()) {
            DdNoticeBar(
                text = host.tf(
                    "有 %d 项应用权限尚未开通，开通并重新登录后生效。",
                    "%d app permissions are not granted yet; grant them and sign in again.",
                    snapshot.missingScopes.size,
                ),
                tone = DdNoticeTone.WARNING,
            )
        }

        if (checkingFirstTime) {
            DdLoadingBlock(text = host.t("正在检查飞书状态…", "Checking Feishu status…"))
        }

        if (busy) {
            OperationCard(host = host, runtime = runtime, state = state)
        } else if (linuxAvailable && checked) {
            when {
                !installed -> DdPrimaryButton(
                    text = host.t("安装飞书组件", "Install Feishu component"),
                    onClick = { runtime.startInstall(host) },
                    enabled = !state.checking,
                    modifier = Modifier.fillMaxWidth(),
                )

                !connected -> DdPrimaryButton(
                    text = host.t("连接飞书", "Connect Feishu"),
                    onClick = { runtime.startConnect(host) },
                    enabled = !state.checking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (snapshot != null && snapshot.appConfigured && !busy) {
            DdGroupCard {
                DdListRow(
                    title = host.t("应用权限", "App permissions"),
                    value = if (snapshot.missingScopes.isEmpty()) {
                        host.t("已开通", "Granted")
                    } else {
                        host.tf("%d 项待开通", "%d to grant", snapshot.missingScopes.size)
                    },
                    showChevron = true,
                    enabled = !state.checking,
                    onClick = { runtime.openAppScopes(host) },
                )
            }
        }

        if (connected && !busy) {
            AccountActions(host = host, runtime = runtime, scope = scope, enabled = !state.checking)
        }

        DdText(
            text = host.t(
                "连接后在对话里告诉我要做什么，例如发消息、查日程或读写文档。",
                "Once connected, tell me what to do in chat, such as sending a message, checking your calendar or editing docs.",
            ),
            style = DdType.micro,
            color = colors.inkTertiary,
        )
    }
}

@Composable
private fun StatusCard(host: PluginHost, state: FeishuPageState, linuxAvailable: Boolean) {
    val snapshot = state.snapshot
    val checking = state.checking
    val checked = snapshot != null
    val version = snapshot?.installedVersion
    val installed = version != null
    val connected = snapshot?.connected == true
    val latestVersion = state.latestVersion
    val updateFailed = installed && !state.updateChecking && latestVersion == null && state.lastCheckedAt > 0
    val updateStatus = FeishuUpdate.statusTextKey(
        installedVersion = version,
        latestVersion = latestVersion,
        checking = state.updateChecking || (latestVersion == null && state.lastCheckedAt == 0L),
        failed = updateFailed,
        pinnedVersion = FEISHU_VERSION,
    )
    val unknown = host.t("检查中…", "Checking…")
    val checkFailed = host.t("检查失败", "Check failed")
    val updateBusy = installed && updateStatus == FeishuUpdateStatus.CHECKING
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Xs),
        ) {
            DdStatusRow(
                label = host.t("运行环境", "Runtime"),
                value = when {
                    !checked && checking -> unknown
                    linuxAvailable -> host.t("已就绪", "Ready")
                    else -> host.t("不可用", "Unavailable")
                },
                tone = when {
                    !checked && checking -> DdStatusTone.NEUTRAL
                    linuxAvailable -> DdStatusTone.SUCCESS
                    else -> DdStatusTone.DANGER
                },
                busy = !checked && checking,
            )
            DdHairline()
            DdStatusRow(
                label = host.t("飞书组件", "Feishu component"),
                value = when {
                    !checked && checking -> unknown
                    !checked -> checkFailed
                    !linuxAvailable -> host.t("不可用", "Unavailable")
                    !installed -> host.t("未安装", "Not installed")
                    else -> when (updateStatus) {
                        FeishuUpdateStatus.CHECKING -> host.text(UPDATE_STATUS_CHECKING)
                        FeishuUpdateStatus.AVAILABLE -> host.text(UPDATE_STATUS_AVAILABLE)
                        FeishuUpdateStatus.FAILED -> host.text(UPDATE_STATUS_FAILED)
                        FeishuUpdateStatus.LATEST -> host.t("已就绪", "Ready")
                        FeishuUpdateStatus.NOT_INSTALLED -> host.t("未安装", "Not installed")
                    }
                },
                tone = when {
                    !checked && checking -> DdStatusTone.NEUTRAL
                    !checked -> DdStatusTone.WARNING
                    !linuxAvailable -> DdStatusTone.NEUTRAL
                    !installed -> DdStatusTone.NEUTRAL
                    updateStatus == FeishuUpdateStatus.CHECKING -> DdStatusTone.ACCENT
                    updateStatus == FeishuUpdateStatus.AVAILABLE -> DdStatusTone.WARNING
                    updateStatus == FeishuUpdateStatus.FAILED -> DdStatusTone.WARNING
                    updateStatus == FeishuUpdateStatus.LATEST -> DdStatusTone.SUCCESS
                    else -> DdStatusTone.NEUTRAL
                },
                busy = (!checked && checking) || updateBusy,
            )
            DdHairline()
            DdStatusRow(
                label = host.t("账号", "Account"),
                value = when {
                    !checked && checking -> unknown
                    !checked -> checkFailed
                    !linuxAvailable -> host.t("不可用", "Unavailable")
                    connected -> snapshot.account?.takeIf { it.isNotBlank() } ?: host.t("已连接", "Connected")
                    else -> host.t("未登录", "Not signed in")
                },
                tone = when {
                    !checked && checking -> DdStatusTone.NEUTRAL
                    !checked -> DdStatusTone.WARNING
                    !linuxAvailable -> DdStatusTone.NEUTRAL
                    connected -> DdStatusTone.SUCCESS
                    installed -> DdStatusTone.WARNING
                    else -> DdStatusTone.NEUTRAL
                },
                busy = !checked && checking,
            )
        }
    }
}

@Composable
private fun OperationCard(host: PluginHost, runtime: FeishuRuntime, state: FeishuPageState) {
    val colors = deepDeepColors()
    val cancelling = state.cancelling
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = state.stage?.let { host.text(it) } ?: host.t("正在处理…", "Working…"),
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                )
                state.progress?.takeIf { !cancelling }?.let { progress ->
                    DdText(
                        text = "$progress%",
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
            }
            DdProgressBar(progress = if (cancelling) null else state.progress?.let { it / 100f })
            if (state.operation == FeishuOperation.CONNECT) {
                AuthLink(host = host, runtime = runtime, state = state)
            }
            if (!cancelling) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DdTextAction(
                        text = host.t("取消", "Cancel"),
                        onClick = { runtime.cancelOperation(host) },
                        color = colors.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthLink(host: PluginHost, runtime: FeishuRuntime, state: FeishuPageState) {
    val colors = deepDeepColors()
    if (state.authUrl == null) return
    val creatingApp = state.stage == STAGE_CREATE_APP
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
        DdText(
            text = if (creatingApp) {
                host.t(
                    "在浏览器中创建飞书应用并完成授权，完成后这里会自动继续。",
                    "Create and authorize the Feishu app in the browser; this page continues automatically.",
                )
            } else {
                host.t(
                    "在浏览器中完成授权，完成后这里会自动继续。",
                    "Authorize in the browser; this page continues automatically.",
                )
            },
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
        Row {
            DdTextAction(
                text = host.t("打开浏览器", "Open in browser"),
                onClick = { runtime.openAuthUrl(host) },
            )
            DdTextAction(
                text = host.t("复制链接", "Copy link"),
                onClick = { runtime.copyAuthUrl(host) },
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
private fun AccountActions(
    host: PluginHost,
    runtime: FeishuRuntime,
    scope: CoroutineScope,
    enabled: Boolean,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdTextAction(
            text = host.t("重新登录", "Sign in again"),
            enabled = enabled,
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
            enabled = enabled,
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
