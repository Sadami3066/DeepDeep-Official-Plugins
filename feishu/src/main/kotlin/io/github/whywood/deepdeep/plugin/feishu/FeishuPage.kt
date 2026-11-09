package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuPage(host: PluginHost, runtime: FeishuRuntime) {
    val state by runtime.pageState.collectAsState()
    val initial = remember { runtime.snapshot(host) }
    val linuxAvailable = runCatching { host.linux().available }.getOrDefault(false)
    LaunchedEffect(Unit) {
        if (runtime.pageState.value.operation == FeishuOperation.NONE) {
            runtime.refresh(host)
        }
    }
    val snapshot = state.snapshot ?: initial
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (snapshot == null) {
            DdLoadingBlock()
        } else {
            CliSection(host, runtime, state, snapshot, linuxAvailable)
            AuthSection(host, runtime, state, snapshot, linuxAvailable)
        }
        state.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun CliSection(
    host: PluginHost,
    runtime: FeishuRuntime,
    state: FeishuPageState,
    snapshot: FeishuSnapshot,
    linuxAvailable: Boolean,
) {
    val colors = deepDeepColors()
    val version = snapshot.installedVersion
    val updateAvailable = version != null && version != FEISHU_VERSION
    DdGroupCard {
        DdListRow(
            title = host.text(literal("飞书 CLI", "Feishu CLI")),
            subtitle = FeishuCli.INSTALL_PATH,
            value = when {
                version == null -> host.text(literal("未安装", "Not installed"))
                else -> "v$version"
            },
        )
    }
    when {
        !linuxAvailable -> Text(
            text = host.text(UNAVAILABLE_LINUX),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.danger,
        )

        state.operation == FeishuOperation.INSTALL -> DdLoadingBlock(text = state.stage?.let { host.text(it) })

        version == null -> DdPrimaryButton(
            text = host.text(literal("安装 CLI", "Install CLI")),
            onClick = { runtime.startInstall(host) },
            modifier = Modifier.fillMaxWidth(),
        )

        updateAvailable -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            DdTextAction(
                text = host.text(literal("更新到 v%s", "Update to v%s"), FEISHU_VERSION),
                onClick = { runtime.startInstall(host) },
            )
        }

        else -> Unit
    }
}

@Composable
private fun AuthSection(
    host: PluginHost,
    runtime: FeishuRuntime,
    state: FeishuPageState,
    snapshot: FeishuSnapshot,
    linuxAvailable: Boolean,
) {
    val colors = deepDeepColors()
    val version = snapshot.installedVersion
    DdGroupCard {
        DdListRow(
            title = host.text(literal("连接状态", "Connection")),
            value = if (snapshot.connected) {
                host.text(literal("已连接 · %s", "Connected · %s"), snapshot.account.orEmpty())
            } else {
                host.text(literal("未连接", "Not connected"))
            },
        )
        if (snapshot.appConfigured) {
            DdListRow(
                title = host.text(literal("飞书应用", "Feishu app")),
                value = snapshot.appId.orEmpty(),
            )
        }
        if (snapshot.scopeCount > 0) {
            DdListRow(
                title = host.text(literal("已授权权限", "Granted permissions")),
                value = host.text(literal("%d 项", "%d"), snapshot.scopeCount),
            )
        }
    }
    if (snapshot.missingScopes.isNotEmpty()) {
        Text(
            text = host.text(
                literal("缺少 %d 项权限：%s", "%d permissions missing: %s"),
                snapshot.missingScopes.size,
                snapshot.missingScopes.take(SHOWN_SCOPE_LIMIT).joinToString(" "),
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
    }
    snapshot.message?.let { message ->
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
    }
    when {
        version == null -> Text(
            text = host.text(
                if (linuxAvailable) {
                    literal(
                        "安装 CLI 后可创建飞书应用并授权账号。",
                        "Install the CLI first, then create the Feishu app and authorize your account.",
                    )
                } else {
                    UNAVAILABLE_LINUX
                },
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )

        !linuxAvailable -> Text(
            text = host.text(UNAVAILABLE_LINUX),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.danger,
        )

        state.operation == FeishuOperation.CONNECT -> AuthProgress(host, runtime, state)

        state.operation == FeishuOperation.LOGOUT -> DdLoadingBlock(text = state.stage?.let { host.text(it) })

        else -> AuthActions(host, runtime, state, snapshot)
    }
}

@Composable
private fun AuthProgress(host: PluginHost, runtime: FeishuRuntime, state: FeishuPageState) {
    val colors = deepDeepColors()
    DdLoadingBlock(text = state.stage?.let { host.text(it) })
    state.authUrl?.let { url ->
        Text(
            text = host.text(if (state.stage == STAGE_CREATE_APP) HINT_CREATE_APP else HINT_AUTH),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
        Text(
            text = url,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            DdTextAction(
                text = host.text(literal("打开浏览器", "Open in browser")),
                onClick = { runtime.openAuthUrl(host) },
            )
        }
    }
}

@Composable
private fun AuthActions(
    host: PluginHost,
    runtime: FeishuRuntime,
    state: FeishuPageState,
    snapshot: FeishuSnapshot,
) {
    val colors = deepDeepColors()
    val busy = state.operation != FeishuOperation.NONE
    if (snapshot.connected) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (snapshot.appConfigured) {
                DdTextAction(
                    text = host.text(literal("应用权限", "App permissions")),
                    onClick = { runtime.openAppScopes(host) },
                    enabled = !busy,
                )
                Spacer(Modifier.width(24.dp))
            }
            DdTextAction(
                text = host.text(literal("重新授权", "Reauthorize")),
                onClick = { runtime.startConnect(host) },
                enabled = !busy,
            )
            Spacer(Modifier.width(24.dp))
            DdTextAction(
                text = host.text(literal("退出登录", "Sign out")),
                onClick = { runtime.startLogout(host) },
                color = colors.danger,
                enabled = !busy,
            )
        }
    } else {
        DdPrimaryButton(
            text = host.text(
                if (snapshot.appConfigured) {
                    literal("重新授权", "Reauthorize")
                } else {
                    literal("连接飞书", "Connect Feishu")
                },
            ),
            onClick = { runtime.startConnect(host) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
    }
}
