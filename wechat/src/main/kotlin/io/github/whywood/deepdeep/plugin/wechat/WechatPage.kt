package io.github.whywood.deepdeep.plugin.wechat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class WechatPageState {
    var status by mutableStateOf<WechatStatus?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var from by mutableStateOf(today())
    var to by mutableStateOf(today())
}

private fun today(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

@Composable
internal fun WechatPage(plugin: WechatPlugin, host: PluginHost) {
    val state = remember { WechatPageState() }

    LaunchedEffect(host) {
        state.busy = true
        try {
            state.status = plugin.loadStatus(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.error = error.message
        } finally {
            state.busy = false
        }
    }

    fun runAction(action: suspend () -> Unit) {
        if (state.busy) return
        host.scope.launch {
            state.busy = true
            state.progress = null
            state.error = null
            state.message = null
            try {
                action()
                host.requestContributionsRefresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.error = error.message ?: host.text(TEXT_ERROR_UNKNOWN)
            } finally {
                state.busy = false
                state.progress = null
            }
        }
    }

    val install = {
        runAction {
            plugin.ensureInstalled(host) { state.progress = it }
            state.status = plugin.loadStatus(host)
            state.message = host.text(TEXT_INSTALLED)
        }
    }
    val sync = {
        runAction {
            plugin.syncAndIngest(host) { state.progress = it }
            state.status = plugin.loadStatus(host)
            state.message = host.text(TEXT_SYNCED)
        }
    }
    val export = {
        runAction {
            val path = plugin.exportRange(host, state.from.trim(), state.to.trim()) { state.progress = it }
            state.status = plugin.loadStatus(host)
            state.message = host.text(TEXT_EXPORTED, path)
        }
    }

    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(host, state.status)
        statusHint(host, state.status)?.let { hint ->
            Text(hint, modifier = Modifier.padding(horizontal = 4.dp), style = DdType.bodySmall, color = colors.inkTertiary)
        }
        if (state.busy) DdLoadingBlock(text = state.progress)
        state.error?.let { Text(it, style = DdType.bodySmall, color = colors.danger) }
        state.message?.let { Text(it, style = DdType.bodySmall, color = colors.inkSecondary) }

        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(host.text(TEXT_RANGE_HINT), style = DdType.bodySmall, color = colors.inkTertiary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DdTextField(
                        value = state.from,
                        onValueChange = { if (!state.busy) state.from = it.take(10) },
                        placeholder = host.text(TEXT_FROM),
                        modifier = Modifier.weight(1f),
                    )
                    DdTextField(
                        value = state.to,
                        onValueChange = { if (!state.busy) state.to = it.take(10) },
                        placeholder = host.text(TEXT_TO),
                        modifier = Modifier.weight(1f),
                    )
                }
                DdPrimaryButton(
                    text = host.text(TEXT_EXPORT),
                    onClick = export,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdTextAction(text = host.text(TEXT_SYNC), onClick = sync, enabled = !state.busy)
                    Spacer(Modifier.width(24.dp))
                    DdTextAction(text = host.text(TEXT_INSTALL), onClick = install, enabled = !state.busy)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(host: PluginHost, status: WechatStatus?) {
    DdGroupCard {
        DdListRow(title = host.text(TEXT_RUNTIME), value = if (status?.linuxAvailable == true) host.text(TEXT_AVAILABLE) else host.text(TEXT_UNAVAILABLE))
        DdListRow(title = "Node.js", value = status?.nodeVersion ?: host.text(TEXT_UNKNOWN))
        DdListRow(title = host.text(TEXT_CLI), value = status?.cliVersion ?: host.text(TEXT_NOT_INSTALLED))
        DdListRow(title = host.text(TEXT_SNAPSHOT), value = if (status?.hasSnapshot == true) host.text(TEXT_YES) else host.text(TEXT_NO))
        DdListRow(title = host.text(TEXT_NORMALIZED), value = if (status?.hasNormalized == true) host.text(TEXT_YES) else host.text(TEXT_NO))
        DdListRow(title = host.text(TEXT_LAST_EXPORT), value = status?.exports?.firstOrNull() ?: host.text(TEXT_NONE))
    }
}

private fun statusHint(host: PluginHost, status: WechatStatus?): String? = when {
    status == null -> null
    !status.linuxAvailable -> host.text(TEXT_UNAVAILABLE_LINUX)
    status.nodeVersion == null -> host.text(TEXT_HINT_NODE)
    status.nodeTooOld -> host.text(TEXT_HINT_NODE_OLD, status.nodeVersion)
    !status.installed -> host.text(TEXT_HINT_INSTALL)
    status.outdated -> host.text(TEXT_HINT_UPDATE)
    else -> null
}

private val TEXT_RUNTIME = literal("运行环境", "Runtime")
private val TEXT_CLI = literal("微信导出 CLI", "WeChat CLI")
private val TEXT_SNAPSHOT = literal("微信快照", "Snapshot")
private val TEXT_NORMALIZED = literal("规范化库", "Normalized DB")
private val TEXT_LAST_EXPORT = literal("最近导出", "Latest export")
private val TEXT_AVAILABLE = literal("可用", "Available")
private val TEXT_UNAVAILABLE = literal("不可用", "Unavailable")
private val TEXT_UNKNOWN = literal("未知", "Unknown")
private val TEXT_YES = literal("有", "Yes")
private val TEXT_NO = literal("无", "No")
private val TEXT_NONE = literal("还没有", "None")
private val TEXT_NOT_INSTALLED = literal("未安装", "Not installed")
private val TEXT_HINT_NODE = literal("缺少 Node.js，请到 设置 → 运行环境安装 Node.js 24+", "Node.js is missing. Install Node.js 24+ in Settings → Runtime.")
private val TEXT_HINT_NODE_OLD = literal("Node.js 版本过低（%1\$s），需要 24+", "Node.js is too old (%1\$s); version 24+ is required.")
private val TEXT_HINT_INSTALL = literal("点「安装/更新 CLI」开始", "Tap \"Install / update CLI\" to start")
private val TEXT_HINT_UPDATE = literal("CLI 有新版本，点「安装/更新 CLI」", "A newer CLI is available; tap \"Install / update CLI\"")
private val TEXT_UNAVAILABLE_LINUX = literal("请先在 设置 → 运行环境安装 Linux 环境", "Install the Linux runtime in Settings → Runtime first")
private val TEXT_RANGE_HINT = literal("导出范围（Asia/Shanghai，包含首尾日期）", "Export range (Asia/Shanghai, inclusive)")
private val TEXT_FROM = literal("开始 YYYY-MM-DD", "From YYYY-MM-DD")
private val TEXT_TO = literal("结束 YYYY-MM-DD", "To YYYY-MM-DD")
private val TEXT_EXPORT = literal("导出完整记录", "Export complete records")
private val TEXT_SYNC = literal("同步并导入", "Sync and ingest")
private val TEXT_INSTALL = literal("安装/更新 CLI", "Install / update CLI")
private val TEXT_INSTALLED = literal("CLI 已就绪", "CLI is ready")
private val TEXT_SYNCED = literal("同步和导入完成", "Sync and ingest complete")
private val TEXT_EXPORTED = literal("已导出：%1\$s", "Exported: %1\$s")
private val TEXT_ERROR_UNKNOWN = literal("操作失败，请重试", "Something went wrong. Try again.")
