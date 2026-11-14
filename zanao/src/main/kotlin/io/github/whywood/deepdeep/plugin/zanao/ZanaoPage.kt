package io.github.whywood.deepdeep.plugin.zanao

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdSwitch
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.plugin.api.HostAccessLevel
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class PageMode {
    HOME,
    DETAIL,
    COMPOSE,
}

private class PageState {
    var status by mutableStateOf<ZanaoStatus?>(null)
    var capture by mutableStateOf<CaptureState>(CaptureState.Idle)
    var mode by mutableStateOf(PageMode.HOME)
    var threads by mutableStateOf<List<ZanaoThread>>(emptyList())
    var detail by mutableStateOf<ZanaoThread?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var alias by mutableStateOf(DEFAULT_ALIAS)
    var postTitle by mutableStateOf("")
    var postContent by mutableStateOf("")
    var postCate by mutableStateOf("108")
    var categories by mutableStateOf<List<ZanaoCategory>>(emptyList())
    var anonymous by mutableStateOf(true)
    var posting by mutableStateOf(false)
}

@Composable
internal fun ZanaoPage(plugin: ZanaoPlugin, host: PluginHost) {
    val state = remember { PageState() }
    val clipboard = LocalClipboardManager.current

    suspend fun refreshStatus() {
        state.status = runCatching { plugin.loadStatus(host) }.getOrNull()
        state.alias = state.status?.alias ?: DEFAULT_ALIAS
    }

    suspend fun loadFeed() {
        state.loading = true
        state.error = null
        try {
            state.threads = plugin.feed(host, 20)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.error = error.message ?: host.t("加载失败", "Load failed")
        } finally {
            state.loading = false
        }
    }

    fun launchAction(action: suspend () -> Unit) {
        host.scope.launch {
            state.error = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.error = error.message ?: host.t("操作失败", "Action failed")
            }
        }
    }

    LaunchedEffect(host) {
        runCatching { plugin.cleanup(host) }
        refreshStatus()
        if (state.status?.configured == true) {
            loadFeed()
            runCatching { plugin.categories(host) }.getOrNull()?.let { state.categories = it }
        }
        plugin.captureState(host).collect { capture ->
            state.capture = capture
            when (capture) {
                is CaptureState.Success -> {
                    refreshStatus()
                    state.mode = PageMode.HOME
                    loadFeed()
                    host.ui().snackbar(capture.message)
                }
                is CaptureState.Failed -> state.error = capture.message
                else -> Unit
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = DdSpacing.Xl, vertical = 12.dp)
            .padding(bottom = DdSpacing.Xxl),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        state.error?.let {
            DdNoticeBar(
                text = it,
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = { launchAction { refreshStatus() } },
            )
        }

        when (state.mode) {
            PageMode.DETAIL -> {
                val detail = state.detail
                if (detail != null) {
                    DetailView(host, detail) { state.mode = PageMode.HOME }
                }
            }
            PageMode.COMPOSE -> {
                ComposeView(
                    host = host,
                    state = state,
                    onBack = { state.mode = PageMode.HOME },
                    onSubmit = {
                        launchAction {
                            if (state.postTitle.trim().isEmpty()) {
                                throw ZanaoException(host.t("请填写标题", "Title is required"))
                            }
                            if (state.postContent.trim().isEmpty()) {
                                throw ZanaoException(host.t("请填写正文", "Content is required"))
                            }
                            state.posting = true
                            try {
                                plugin.post(host, state.postTitle.trim(), state.postContent.trim(), state.anonymous, state.postCate.trim().ifBlank { "108" })
                                state.postTitle = ""
                                state.postContent = ""
                                state.mode = PageMode.HOME
                                host.ui().snackbar(host.t("已发布", "Posted"))
                                loadFeed()
                            } finally {
                                state.posting = false
                            }
                        }
                    },
                )
            }
            PageMode.HOME -> {
                HomeView(
                    host = host,
                    state = state,
                    onRefresh = { launchAction { refreshStatus() } },
                    onLoadFeed = { launchAction { loadFeed() } },
                    onImport = {
                        launchAction {
                            val raw = clipboard.getText()?.text
                                ?: throw ZanaoException(host.t("剪贴板是空的", "Clipboard is empty"))
                            plugin.importAccount(host, raw)
                            refreshStatus()
                            state.mode = PageMode.HOME
                            loadFeed()
                            host.ui().snackbar(host.t("账号已导入", "Account imported"))
                        }
                    },
                    onInstallHelper = {
                        launchAction {
                            host.ui().snackbar(plugin.installHelper(host))
                            refreshStatus()
                        }
                    },
                    onOpenHelper = {
                        launchAction {
                            if (!plugin.launchHelper(host)) {
                                throw ZanaoException(host.t("没有找到集市 Token 捕捉器，请先安装", "Token catcher is not installed"))
                            }
                        }
                    },
                    onSaveAlias = {
                        launchAction {
                            plugin.saveAlias(host, state.alias)
                            refreshStatus()
                            host.ui().snackbar(host.t("站点已保存", "Alias saved"))
                        }
                    },
                    onClearAccount = {
                        host.scope.launch {
                            if (host.ui().confirm(uiConfirmRequest(
                                    title = host.t("移除集市账号？", "Remove Zanao account?"),
                                    message = host.t("token 和 device 会从本机删除。", "The token and device will be deleted from this device."),
                                    confirmLabel = host.t("移除", "Remove"),
                                    destructive = true,
                                ))
                            ) {
                                plugin.clearAccount(host)
                                refreshStatus()
                            }
                        }
                    },
                    onOpenDetail = { thread ->
                        state.detail = thread
                        state.mode = PageMode.DETAIL
                        launchAction {
                            state.detail = plugin.detail(host, thread.id)
                        }
                    },
                    onCompose = { state.mode = PageMode.COMPOSE },
                )
            }
        }
    }
}

@Composable
private fun HomeView(
    host: PluginHost,
    state: PageState,
    onRefresh: () -> Unit,
    onLoadFeed: () -> Unit,
    onImport: () -> Unit,
    onInstallHelper: () -> Unit,
    onOpenHelper: () -> Unit,
    onSaveAlias: () -> Unit,
    onClearAccount: () -> Unit,
    onOpenDetail: (ZanaoThread) -> Unit,
    onCompose: () -> Unit,
) {
    val status = state.status
    DdGroupCard(
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = host.t("账号", "Account"),
                    modifier = Modifier.weight(1f),
                    style = DdType.heading,
                )
                DdTextAction(host.t("刷新", "Refresh"), onRefresh)
            }
            Spacer(Modifier.padding(top = DdSpacing.Xs))
            if (status != null) {
                DdStatusRow(
                    label = host.t("系统访问", "System access"),
                    value = when (status.access) {
                        HostAccessLevel.ADB -> if (status.adbConnected) "ADB" else host.t("ADB 未连接", "ADB offline")
                        HostAccessLevel.ROOT -> "Root"
                        HostAccessLevel.LOCAL -> host.t("普通", "Local")
                        else -> host.t("未知", "Unknown")
                    },
                    tone = if (status.access == HostAccessLevel.LOCAL) DdStatusTone.WARNING else DdStatusTone.SUCCESS,
                )
                DdStatusRow(
                    label = host.t("集市账号", "Zanao account"),
                    value = if (status.configured) host.t("已导入", "Imported") else host.t("未导入", "Not imported"),
                    tone = if (status.configured) DdStatusTone.SUCCESS else DdStatusTone.NEUTRAL,
                )
                DdStatusRow(
                    label = host.t("站点", "Alias"),
                    value = status.alias,
                )
                DdStatusRow(
                    label = host.t("Token 捕捉器", "Token catcher"),
                    value = if (status.helperInstalled) host.t("已安装", "Installed") else host.t("未安装", "Not installed"),
                    tone = if (status.helperInstalled) DdStatusTone.SUCCESS else DdStatusTone.WARNING,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdSpinner()
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(host.t("检查中…", "Checking…"), style = DdType.bodySmall)
                }
            }
        },
        content = {
            if (status != null && !status.configured) {
                Column(
                    Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
                ) {
                    DdText(
                        host.t(
                            "捕捉器只接管 api.app.zanao.com，不会改动系统全局代理。完成登录后复制 Token JSON，再回到这里导入。",
                            "The catcher only routes api.app.zanao.com. Sign in, copy the token JSON, then import it here.",
                        ),
                        style = DdType.bodySmall,
                    )
                    DdPrimaryButton(
                        text = if (status.helperInstalled) {
                            host.t("打开集市 Token 捕捉器", "Open token catcher")
                        } else {
                            host.t("安装集市 Token 捕捉器", "Install token catcher")
                        },
                        onClick = if (status.helperInstalled) onOpenHelper else onInstallHelper,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DdTextAction(
                        text = host.t("从剪贴板导入 Token", "Import token from clipboard"),
                        onClick = onImport,
                    )
                }
            } else if (status != null) {
                Column(
                    Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdText(host.t("站点别名", "Alias"), modifier = Modifier.width(84.dp), style = DdType.bodySmall)
                        DdTextField(
                            value = state.alias,
                            onValueChange = { state.alias = it },
                            placeholder = DEFAULT_ALIAS,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(DdSpacing.Sm))
                        DdTextAction(host.t("保存", "Save"), onSaveAlias)
                    }
                    DdTextAction(
                        text = host.t("从剪贴板导入新 Token", "Import new token"),
                        onClick = onImport,
                    )
                    DdTextAction(
                        text = host.t("打开 Token 捕捉器", "Open token catcher"),
                        onClick = if (status.helperInstalled) onOpenHelper else onInstallHelper,
                    )
                    DdTextAction(
                        text = host.t("移除账号", "Remove account"),
                        onClick = onClearAccount,
                        color = io.github.whywood.deepdeep.design.deepDeepColors().danger,
                    )
                }
            }
        },
    )

    if (status?.configured == true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdSectionLabel(host.t("最新帖子", "Latest threads"), Modifier.weight(1f))
            DdTextAction(host.t("发帖", "New post"), onCompose)
            DdTextAction(host.t("刷新", "Refresh"), onLoadFeed)
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdSpinner()
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(host.t("加载中…", "Loading…"), style = DdType.bodySmall)
            }
        }
        state.threads.forEach { thread ->
            DdGroupCard {
                DdListRow(
                    title = thread.title.ifBlank { host.t("无标题", "Untitled") },
                    subtitle = listOf(thread.category, thread.time, "${thread.likes} 赞 · ${thread.comments} 评")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    showChevron = true,
                    onClick = { onOpenDetail(thread) },
                )
            }
        }
    }
}

@Composable
private fun DetailView(host: PluginHost, thread: ZanaoThread, onBack: () -> Unit) {
    DdTextAction(host.t("返回", "Back"), onBack)
    DdGroupCard(
        header = {
            DdText(thread.title, style = DdType.heading)
            Spacer(Modifier.padding(top = DdSpacing.Xs))
            DdText(
                listOf(thread.category, thread.time, thread.nickname).filter { it.isNotBlank() }.joinToString(" · "),
                style = DdType.bodySmall,
            )
        },
        content = {
            Column(
                Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                DdText(thread.content.ifBlank { host.t("没有正文", "No content") }, style = DdType.body)
                DdText(
                    "${thread.likes} 赞 · ${thread.comments} 评",
                    style = DdType.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun ComposeView(
    host: PluginHost,
    state: PageState,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    DdTextAction(host.t("返回", "Back"), onBack)
    DdGroupCard(
        header = {
            DdText(host.t("发布帖子", "New post"), style = DdType.heading)
        },
        content = {
            Column(
                Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
            ) {
                DdTextField(
                    value = state.postTitle,
                    onValueChange = { state.postTitle = it },
                    placeholder = host.t("标题", "Title"),
                )
                DdTextField(
                    value = state.postContent,
                    onValueChange = { state.postContent = it },
                    placeholder = host.t("正文", "Content"),
                    singleLine = false,
                )
                DdTextField(
                    value = state.postCate,
                    onValueChange = { state.postCate = it },
                    placeholder = host.t("分类 ID，例如 108", "Category id, e.g. 108"),
                )
                if (state.categories.isNotEmpty()) {
                    DdText(
                        state.categories.joinToString("  ") { "${it.id} ${it.name}" },
                        style = DdType.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(host.t("匿名发布", "Post anonymously"), modifier = Modifier.weight(1f), style = DdType.body)
                    DdSwitch(checked = state.anonymous, onCheckedChange = { state.anonymous = it })
                }
                DdPrimaryButton(
                    text = if (state.posting) host.t("发布中…", "Posting…") else host.t("发布", "Post"),
                    onClick = onSubmit,
                    enabled = !state.posting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
