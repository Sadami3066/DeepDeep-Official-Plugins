package io.github.whywood.deepdeep.plugin.hotel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
    androidx.compose.runtime.LaunchedEffect(Unit) { runtime.check(host) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.Xl, vertical = DdSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = "RollingGo 酒店",
                        modifier = Modifier.weight(1f),
                        style = DdType.heading,
                        color = colors.ink,
                    )
                    if (busy) DdSpinner(color = colors.inkTertiary, size = 18.dp, strokeWidth = 2.dp)
                }
                DdText(
                    text = when {
                        state.checking -> "正在检查…"
                        snapshot?.installedVersion == null -> "CLI 未安装"
                        snapshot.loggedIn -> "已登录${snapshot.account?.let { " · $it" }.orEmpty()}"
                        else -> "CLI ${snapshot.installedVersion} · 未登录"
                    },
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                state.stage?.let { stage ->
                    DdText(stage, style = DdType.bodySmall, color = colors.inkTertiary)
                }
            }
        }

        state.error?.let { message ->
            DdNoticeBar(
                text = message,
                tone = DdNoticeTone.DANGER,
                actionLabel = "重试",
                onAction = {
                    when (state.operation) {
                        HotelOperation.NONE -> runtime.check(host)
                        else -> Unit
                    }
                },
            )
        }

        DdPrimaryButton(
            text = if (snapshot?.installedVersion == null) "安装酒店 CLI" else "更新酒店 CLI",
            onClick = { runtime.startInstall(host) },
            enabled = !busy && !state.checking,
        )

        if (snapshot?.installedVersion != null) {
            DdPrimaryButton(
                text = if (snapshot.loggedIn) "退出登录" else "登录 RollingGo",
                onClick = {
                    if (snapshot.loggedIn) runtime.startLogout(host) else runtime.startLogin(host)
                },
                enabled = !busy,
            )
        }

        state.loginUrl?.let { url ->
            DdNoticeBar(
                text = "等待浏览器授权",
                tone = DdNoticeTone.INFO,
            )
            DdTextAction(
                text = "打开授权链接",
                onClick = { runtime.openAuthUrl(host) },
            )
            DdTextAction(
                text = "复制授权链接",
                onClick = { runtime.copyAuthUrl(host) },
            )
        }

    }
}
