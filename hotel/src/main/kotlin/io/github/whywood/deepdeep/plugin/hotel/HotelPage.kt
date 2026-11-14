package io.github.whywood.deepdeep.plugin.hotel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun HotelPage(host: PluginHost, runtime: HotelRuntime) {
    val state by runtime.pageState.collectAsStateWithLifecycle()
    val colors = deepDeepColors()
    val snapshot = state.snapshot
    val busy = state.operation != HotelOperation.NONE
    val linuxAvailable = snapshot?.linuxAvailable != false
    val installed = snapshot?.installedVersion

    LaunchedEffect(Unit) { runtime.check(host) }

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
        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
            ) {
                DdStatusRow(
                    label = host.t("运行环境", "Environment"),
                    value = when {
                        state.checking -> host.t("正在检查…", "Checking…")
                        linuxAvailable -> host.t("可用", "Available")
                        else -> host.t("不可用", "Unavailable")
                    },
                    tone = when {
                        state.checking -> DdStatusTone.NEUTRAL
                        linuxAvailable -> DdStatusTone.SUCCESS
                        else -> DdStatusTone.DANGER
                    },
                    busy = state.checking,
                )
                DdStatusRow(
                    label = host.t("酒店服务", "Hotel service"),
                    value = when {
                        state.checking -> host.t("正在检查…", "Checking…")
                        installed != null -> "v$installed"
                        else -> host.t("未安装", "Not installed")
                    },
                    tone = when {
                        state.checking -> DdStatusTone.NEUTRAL
                        installed != null -> DdStatusTone.SUCCESS
                        else -> DdStatusTone.WARNING
                    },
                )
                DdStatusRow(
                    label = host.t("RollingGo 账号", "RollingGo account"),
                    value = when {
                        state.checking -> host.t("正在检查…", "Checking…")
                        snapshot?.loggedIn == true -> snapshot.account ?: host.t("已登录", "Signed in")
                        installed == null -> host.t("未安装", "Not installed")
                        else -> host.t("未登录", "Not signed in")
                    },
                    tone = when {
                        state.checking -> DdStatusTone.NEUTRAL
                        snapshot?.loggedIn == true -> DdStatusTone.SUCCESS
                        installed == null -> DdStatusTone.NEUTRAL
                        else -> DdStatusTone.WARNING
                    },
                )
                if (busy || state.checking) {
                    DdStatusRow(
                        label = host.t("状态", "Status"),
                        value = state.stage ?: host.t("处理中…", "Working…"),
                        tone = DdStatusTone.NEUTRAL,
                        busy = true,
                    )
                }
            }
        }

        if (!linuxAvailable && !state.checking) {
            DdNoticeBar(
                text = host.t(
                    "需要可用的 Linux 运行环境才能使用酒店服务。",
                    "A working Linux environment is required for the hotel service.",
                ),
                tone = DdNoticeTone.WARNING,
            )
        }

        state.error?.let { message ->
            DdNoticeBar(
                text = message,
                tone = DdNoticeTone.DANGER,
                actionLabel = if (state.failedOperation != null) host.t("重试", "Retry") else null,
                onAction = if (state.failedOperation != null) {
                    { runtime.retry(host) }
                } else {
                    null
                },
            )
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                DdSpinner(color = colors.inkTertiary, size = 16.dp, strokeWidth = 2.dp)
                DdText(
                    text = state.stage ?: host.t("处理中…", "Working…"),
                    modifier = Modifier.weight(1f),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = host.t("取消", "Cancel"),
                    onClick = { runtime.cancel(host) },
                    color = colors.inkSecondary,
                )
            }
        }

        if (installed == null) {
            DdPrimaryButton(
                text = host.t("安装酒店服务", "Install hotel service"),
                onClick = { runtime.startInstall(host) },
                enabled = !busy && !state.checking && linuxAvailable,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            if (!snapshot.loggedIn) {
                DdPrimaryButton(
                    text = host.t("登录 RollingGo", "Sign in to RollingGo"),
                    onClick = { runtime.startLogin(host) },
                    enabled = !busy && !state.checking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(
                    text = host.t("更新酒店服务", "Update hotel service"),
                    onClick = { runtime.startInstall(host) },
                    enabled = !busy && !state.checking && linuxAvailable,
                    color = colors.inkSecondary,
                )
                if (snapshot.loggedIn) {
                    DdTextAction(
                        text = host.t("退出登录", "Sign out"),
                        onClick = { runtime.startLogout(host) },
                        enabled = !busy && !state.checking,
                        color = colors.inkSecondary,
                    )
                }
            }
        }

        state.loginUrl?.let {
            DdNoticeBar(
                text = host.t(
                    "等待浏览器授权，完成后回到这里。",
                    "Waiting for browser authorization. Return here when finished.",
                ),
                tone = DdNoticeTone.INFO,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg)) {
                DdTextAction(
                    text = host.t("打开授权链接", "Open authorization"),
                    onClick = { runtime.openAuthUrl(host) },
                )
                DdTextAction(
                    text = host.t("复制链接", "Copy link"),
                    onClick = { runtime.copyAuthUrl(host) },
                    color = colors.inkSecondary,
                )
            }
        }

        Spacer(Modifier.height(DdSpacing.Xs))
        DdText(
            text = host.t(
                "连接后，在对话里描述目的地和日期即可搜索酒店、查看房型。真实预订在 RollingGo 完成。",
                "After connecting, describe the destination and dates in chat to search hotels and view rooms. Booking happens on RollingGo.",
            ),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
    }
}
