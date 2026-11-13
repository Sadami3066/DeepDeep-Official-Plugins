package io.github.whywood.deepdeep.plugin.wechat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdDatePicker
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.shareRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val EXPORT_MIME = "text/plain"
private const val DASH = "—"

private enum class WechatTask { CHECK, INSTALL, SYNC, EXPORT }

private enum class WechatDateField { FROM, TO }

private class WechatPageState {
    var status by mutableStateOf<WechatStatus?>(null)
    var refreshing by mutableStateOf(true)
    var task by mutableStateOf<WechatTask?>(null)
    var stage by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var failedTask by mutableStateOf<WechatTask?>(null)
    var from by mutableStateOf(today())
    var to by mutableStateOf(today())
    var picker by mutableStateOf<WechatDateField?>(null)
    var result by mutableStateOf<WechatExport?>(null)
}

private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))

@Composable
internal fun WechatPage(plugin: WechatPlugin, host: PluginHost) {
    val state = remember { WechatPageState() }
    val colors = deepDeepColors()
    val motionEnabled = LocalDdMotionEnabled.current

    suspend fun loadStatus() {
        state.refreshing = true
        try {
            state.status = plugin.loadStatus(host)
        } finally {
            state.refreshing = false
        }
    }

    fun runTask(task: WechatTask, action: suspend () -> Unit) {
        if (state.task != null) return
        state.task = task
        state.stage = null
        state.error = null
        state.failedTask = null
        host.scope.launch {
            try {
                action()
                host.requestContributionsRefresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.error = error.message ?: host.text(TEXT_ERROR_UNKNOWN)
                state.failedTask = task
            } finally {
                state.task = null
                state.stage = null
            }
        }
    }

    val check = { runTask(WechatTask.CHECK) { loadStatus() } }
    val install = {
        runTask(WechatTask.INSTALL) {
            plugin.ensureInstalled(host) { state.stage = it }
            state.stage = null
            loadStatus()
            host.ui().snackbar(host.text(TEXT_TOOL_READY))
        }
    }
    val sync = {
        runTask(WechatTask.SYNC) {
            plugin.syncAndIngest(host) { state.stage = it }
            state.stage = null
            loadStatus()
            host.ui().snackbar(host.text(TEXT_SYNC_DONE))
        }
    }
    val export = {
        runTask(WechatTask.EXPORT) {
            val result = plugin.exportRange(host, state.from.toString(), state.to.toString()) { state.stage = it }
            WechatSettingsStore(host).writeRange(result.from, result.to)
            state.result = result
            state.stage = null
            loadStatus()
        }
    }
    val retry = {
        when (state.failedTask) {
            WechatTask.CHECK -> check()
            WechatTask.INSTALL -> install()
            WechatTask.SYNC -> sync()
            WechatTask.EXPORT -> export()
            null -> Unit
        }
    }

    LaunchedEffect(host) {
        WechatSettingsStore(host).readRange()?.let { (from, to) ->
            val start = runCatching { LocalDate.parse(from) }.getOrNull()
            val end = runCatching { LocalDate.parse(to) }.getOrNull()
            if (start != null && end != null) {
                state.from = start
                state.to = end
            }
        }
        check()
    }

    val status = state.status
    val busy = state.task != null
    val rangeError = if (state.from > state.to) host.text(TEXT_RANGE_ORDER) else null
    val environmentReady = status != null && status.linuxAvailable && status.nodeReady
    val dataReady = status?.dataReady == true
    val canExport = !busy && environmentReady && dataReady && rangeError == null
    val hint = when {
        busy || state.error != null || status == null -> null
        !status.linuxAvailable -> host.text(TEXT_HINT_LINUX)
        !status.nodeReady -> status.nodeVersion
            ?.let { host.text(TEXT_HINT_NODE_OLD, it) }
            ?: host.text(TEXT_HINT_NODE)
        !status.dataReady -> host.text(TEXT_HINT_SYNC)
        else -> null
    }
    val hintAction: (() -> Unit)? = when {
        busy || state.error != null || status == null -> null
        !status.linuxAvailable || !status.nodeReady -> check
        !status.dataReady -> sync
        else -> null
    }
    val hintActionLabel = when {
        hintAction == null || status == null -> null
        !status.linuxAvailable || !status.nodeReady -> host.text(TEXT_RECHECK)
        else -> host.text(TEXT_SYNC_ACTION)
    }
    val showSyncAction = state.error == null && status != null && status.linuxAvailable && status.dataReady
    val showUpdateAction = state.error == null && status != null && status.linuxAvailable && status.dataReady && status.outdated
    val exportResult = state.result

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
    ) {
        StatusSummary(host = host, status = status, refreshing = state.refreshing)
        MotionReveal(visible = hint != null, motionEnabled = motionEnabled) {
            hint?.let { message ->
                DdNoticeBar(
                    text = message,
                    modifier = Modifier.padding(top = DdSpacing.Lg),
                    tone = if (status != null && status.linuxAvailable && status.nodeReady) {
                        DdNoticeTone.INFO
                    } else {
                        DdNoticeTone.WARNING
                    },
                    actionLabel = hintActionLabel,
                    onAction = hintAction,
                )
            }
        }
        Column(
            modifier = Modifier.padding(top = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdSectionLabel(host.text(TEXT_RANGE_SECTION))
            DdGroupCard {
                DdListRow(
                    title = host.text(TEXT_FROM),
                    value = state.from.toString(),
                    showChevron = true,
                    enabled = !busy,
                    onClick = { state.picker = WechatDateField.FROM },
                )
                DdListRow(
                    title = host.text(TEXT_TO),
                    value = state.to.toString(),
                    showChevron = true,
                    enabled = !busy,
                    onClick = { state.picker = WechatDateField.TO },
                )
                MotionReveal(visible = rangeError != null, motionEnabled = motionEnabled) {
                    rangeError?.let { message ->
                        DdText(
                            text = message,
                            modifier = Modifier.padding(
                                start = DdSpacing.RowHorizontal,
                                end = DdSpacing.RowHorizontal,
                                bottom = DdSpacing.Md,
                            ),
                            style = DdType.bodySmall,
                            color = colors.danger,
                        )
                    }
                }
            }
        }
        DdPrimaryButton(
            text = host.text(TEXT_EXPORT),
            onClick = export,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DdSpacing.Lg),
            enabled = canExport,
        )
        MotionReveal(
            visible = state.task == WechatTask.INSTALL ||
                state.task == WechatTask.SYNC ||
                state.task == WechatTask.EXPORT,
            motionEnabled = motionEnabled,
        ) {
            OperationCard(
                host = host,
                stage = state.stage,
                modifier = Modifier.padding(top = DdSpacing.Lg),
            )
        }
        MotionReveal(visible = state.error != null, motionEnabled = motionEnabled) {
            state.error?.let { message ->
                DdNoticeBar(
                    text = message,
                    modifier = Modifier.padding(top = DdSpacing.Lg),
                    tone = DdNoticeTone.DANGER,
                    actionLabel = if (state.failedTask != null) host.text(TEXT_RETRY) else null,
                    onAction = if (state.failedTask != null) retry else null,
                )
            }
        }
        MotionReveal(visible = exportResult != null, motionEnabled = motionEnabled) {
            exportResult?.let { result ->
                Column(
                    modifier = Modifier.padding(top = DdSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                ) {
                    DdSectionLabel(host.text(TEXT_RESULT_SECTION))
                    DdGroupCard {
                        DdListRow(
                            title = host.text(TEXT_RECORD_TITLE),
                            subtitle = host.text(
                                TEXT_RESULT_SUBTITLE,
                                rangeLabel(host, result.from, result.to),
                                formatSize(result.sizeBytes),
                            ),
                            icon = DdIcons.File,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdTextAction(
                            text = host.text(TEXT_OPEN),
                            onClick = { openExport(host, result) },
                        )
                        DdTextAction(
                            text = host.text(TEXT_SHARE),
                            onClick = { shareExport(host, result) },
                        )
                    }
                }
            }
        }
        MotionReveal(visible = showSyncAction || showUpdateAction, motionEnabled = motionEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DdSpacing.Lg),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSyncAction) {
                    DdTextAction(
                        text = host.text(TEXT_SYNC_AGAIN),
                        onClick = sync,
                        enabled = !busy,
                    )
                }
                if (showUpdateAction) {
                    DdTextAction(
                        text = host.text(TEXT_UPDATE_TOOL),
                        onClick = install,
                        enabled = !busy,
                    )
                }
            }
        }
    }

    state.picker?.let { field ->
        DdDatePicker(
            selected = if (field == WechatDateField.FROM) state.from else state.to,
            onSelect = { date ->
                when (field) {
                    WechatDateField.FROM -> state.from = date
                    WechatDateField.TO -> state.to = date
                }
                if (state.failedTask == WechatTask.EXPORT) {
                    state.error = null
                    state.failedTask = null
                }
            },
            onDismiss = { state.picker = null },
            title = host.text(if (field == WechatDateField.FROM) TEXT_PICK_FROM else TEXT_PICK_TO),
            previousMonthLabel = host.text(TEXT_PREV_MONTH),
            nextMonthLabel = host.text(TEXT_NEXT_MONTH),
        )
    }
}

@Composable
private fun StatusSummary(host: PluginHost, status: WechatStatus?, refreshing: Boolean) {
    val checking = status == null && refreshing
    val placeholder = if (checking) host.text(TEXT_CHECKING) else host.text(TEXT_CHECK_FAILED)
    val placeholderTone = if (checking) DdStatusTone.NEUTRAL else DdStatusTone.WARNING
    DdGroupCard {
        DdStatusRow(
            label = host.text(TEXT_RUNTIME),
            value = when {
                status == null -> placeholder
                !status.linuxAvailable -> host.text(TEXT_UNAVAILABLE)
                status.nodeVersion == null -> host.text(TEXT_NEEDS_NODE)
                status.nodeTooOld -> host.text(TEXT_NODE_UPDATE)
                else -> host.text(TEXT_READY)
            },
            tone = when {
                status == null -> placeholderTone
                !status.linuxAvailable -> DdStatusTone.DANGER
                !status.nodeReady -> DdStatusTone.WARNING
                else -> DdStatusTone.SUCCESS
            },
            busy = refreshing,
        )
        DdStatusRow(
            label = host.text(TEXT_TOOL),
            value = when {
                status == null -> placeholder
                !status.linuxAvailable -> DASH
                status.outdated -> host.text(TEXT_UPDATE_AVAILABLE)
                status.installed -> host.text(TEXT_INSTALLED)
                else -> host.text(TEXT_NOT_INSTALLED)
            },
            tone = when {
                status == null -> placeholderTone
                !status.linuxAvailable -> DdStatusTone.NEUTRAL
                status.outdated -> DdStatusTone.WARNING
                status.installed -> DdStatusTone.SUCCESS
                else -> DdStatusTone.NEUTRAL
            },
            busy = refreshing,
        )
        DdStatusRow(
            label = host.text(TEXT_DATA),
            value = when {
                status == null -> placeholder
                !status.linuxAvailable -> DASH
                status.dataReady -> host.text(TEXT_SYNCED)
                else -> host.text(TEXT_NOT_SYNCED)
            },
            tone = when {
                status == null -> placeholderTone
                status.dataReady -> DdStatusTone.SUCCESS
                else -> DdStatusTone.NEUTRAL
            },
            busy = refreshing,
        )
        DdStatusRow(
            label = host.text(TEXT_LAST_EXPORT),
            value = when {
                status == null -> placeholder
                !status.linuxAvailable -> DASH
                else -> status.exports.firstOrNull()?.let { exportRangeLabel(host, it) } ?: host.text(TEXT_NONE)
            },
            tone = when {
                status == null -> placeholderTone
                else -> DdStatusTone.NEUTRAL
            },
            busy = refreshing,
        )
    }
}

@Composable
private fun OperationCard(host: PluginHost, stage: String?, modifier: Modifier = Modifier) {
    val colors = deepDeepColors()
    DdGroupCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.RowHorizontal, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdSpinner(color = colors.inkTertiary, size = 16.dp)
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = stage ?: host.text(TEXT_WORKING),
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                )
            }
            DdProgressBar(progress = null)
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

private fun openExport(host: PluginHost, export: WechatExport) {
    host.scope.launch {
        val opened = runCatching { host.media().open(export.path) }.getOrDefault(false)
        if (!opened) host.ui().toast(host.text(TEXT_OPEN_FAILED))
    }
}

private fun shareExport(host: PluginHost, export: WechatExport) {
    runCatching {
        host.ui().share(shareRequest(filePath = export.path, mimeType = EXPORT_MIME))
    }.onFailure {
        host.ui().toast(host.text(TEXT_SHARE_FAILED))
    }
}

private fun rangeLabel(host: PluginHost, from: String, to: String): String =
    if (from == to) from else host.text(TEXT_RANGE_LABEL, from, to)

private fun exportRangeLabel(host: PluginHost, fileName: String): String {
    val base = fileName.substringBeforeLast('.')
    val parts = base.split('_')
    return if (parts.size == 2 && parts.all { WechatCli.DATE_PATTERN.matches(it) }) {
        host.text(TEXT_RANGE_LABEL, parts[0], parts[1])
    } else {
        base
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val TEXT_RUNTIME = literal("运行环境", "Runtime")
private val TEXT_TOOL = literal("导出工具", "Export tool")
private val TEXT_DATA = literal("微信数据", "WeChat data")
private val TEXT_LAST_EXPORT = literal("最近导出", "Last export")
private val TEXT_CHECKING = literal("检查中…", "Checking…")
private val TEXT_CHECK_FAILED = literal("检查失败", "Check failed")
private val TEXT_READY = literal("已就绪", "Ready")
private val TEXT_UNAVAILABLE = literal("不可用", "Unavailable")
private val TEXT_NEEDS_NODE = literal("需要 Node.js", "Needs Node.js")
private val TEXT_NODE_UPDATE = literal("Node.js 需更新", "Update Node.js")
private val TEXT_INSTALLED = literal("已安装", "Installed")
private val TEXT_NOT_INSTALLED = literal("未安装", "Not installed")
private val TEXT_UPDATE_AVAILABLE = literal("可更新", "Update available")
private val TEXT_SYNCED = literal("已同步", "Synced")
private val TEXT_NOT_SYNCED = literal("未同步", "Not synced")
private val TEXT_NONE = literal("暂无", "None")
private val TEXT_HINT_LINUX = literal(
    "微信导出需要 Linux 运行环境。请到 设置 → 运行环境 安装后重新检查。",
    "WeChat export needs the Linux runtime. Install it in Settings → Runtime, then check again.",
)
private val TEXT_HINT_NODE = literal(
    "运行环境里缺少 Node.js 24 或更高版本。请到 设置 → 运行环境 安装后重新检查。",
    "Node.js 24 or newer is missing from the runtime. Install it in Settings → Runtime, then check again.",
)
private val TEXT_HINT_NODE_OLD = literal(
    "Node.js 版本过低（%1\$s），需要 24 或更高版本。",
    "Node.js is too old (%1\$s); version 24 or newer is required.",
)
private val TEXT_HINT_SYNC = literal(
    "还没有同步微信数据，先同步再导出。",
    "WeChat data hasn't been synced yet. Sync it before exporting.",
)
private val TEXT_RECHECK = literal("重新检查", "Check again")
private val TEXT_SYNC_ACTION = literal("同步", "Sync")
private val TEXT_RANGE_SECTION = literal("导出范围", "Export range")
private val TEXT_FROM = literal("开始日期", "Start date")
private val TEXT_TO = literal("结束日期", "End date")
private val TEXT_PICK_FROM = literal("选择开始日期", "Choose start date")
private val TEXT_PICK_TO = literal("选择结束日期", "Choose end date")
private val TEXT_PREV_MONTH = literal("上个月", "Previous month")
private val TEXT_NEXT_MONTH = literal("下个月", "Next month")
private val TEXT_RANGE_ORDER = literal("开始日期不能晚于结束日期", "Start date can't be after the end date")
private val TEXT_EXPORT = literal("导出完整记录", "Export complete records")
private val TEXT_WORKING = literal("正在处理…", "Working…")
private val TEXT_RETRY = literal("重试", "Retry")
private val TEXT_RESULT_SECTION = literal("导出结果", "Export result")
private val TEXT_RECORD_TITLE = literal("微信记录", "WeChat records")
private val TEXT_RESULT_SUBTITLE = literal("%1\$s · %2\$s", "%1\$s · %2\$s")
private val TEXT_RANGE_LABEL = literal("%1\$s 至 %2\$s", "%1\$s – %2\$s")
private val TEXT_OPEN = literal("打开", "Open")
private val TEXT_SHARE = literal("分享", "Share")
private val TEXT_SYNC_AGAIN = literal("重新同步", "Sync again")
private val TEXT_UPDATE_TOOL = literal("更新导出工具", "Update export tool")
private val TEXT_OPEN_FAILED = literal("没有能打开这个文件的应用", "No app can open this file")
private val TEXT_SHARE_FAILED = literal("无法分享这个文件", "Couldn't share this file")
private val TEXT_TOOL_READY = literal("导出工具已就绪", "Export tool is ready")
private val TEXT_SYNC_DONE = literal("同步完成", "Sync complete")
private val TEXT_ERROR_UNKNOWN = literal("操作失败，请重试", "Something went wrong. Try again.")
