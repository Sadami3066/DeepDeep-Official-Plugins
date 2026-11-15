package io.github.whywood.deepdeep.plugin.micloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolResultState
import kotlinx.serialization.json.JsonObject

@Composable
internal fun MiCloudAccountToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudToolCard(
        host = host,
        state = state,
        title = host.t("小米账号", "Xiaomi account"),
        icon = DdIcons.Cloud,
    ) { details ->
        val data = MiCloudAccountCardData.fromJson(details)
        if (data.mode == ACCOUNT_MODE_QUOTA) {
            QuotaCardBody(host, data)
        } else {
            ProfileCardBody(host, data)
        }
    }
}

@Composable
internal fun MiCloudNotesToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudToolCard(
        host = host,
        state = state,
        title = host.t("笔记", "Notes"),
        icon = DdIcons.Pen,
    ) { details ->
        NotesCardBody(host, MiCloudNotesCardData.fromJson(details))
    }
}

@Composable
internal fun MiCloudTodoToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudToolCard(
        host = host,
        state = state,
        title = host.t("待办", "To-dos"),
        icon = DdIcons.Check,
    ) { details ->
        TodoCardBody(host, MiCloudTodoCardData.fromJson(details))
    }
}

@Composable
private fun MiCloudToolCard(
    host: PluginHost,
    state: ToolResultState,
    title: String,
    icon: DdIcon,
    content: @Composable (JsonObject) -> Unit,
) {
    val details = state.result?.details
    val failed = state.result?.isError == true
    val fallbackText = state.result?.text.orEmpty()
    if (failed && details == null) {
        MiCloudCardShell(
            header = { CardHeader(icon = icon, title = title, trailing = null) },
            footer = null,
        ) {
            DdText(
                text = fallbackText.ifBlank { host.t("操作失败，请重试。", "Something went wrong. Try again.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
                style = DdType.bodySmall,
                color = deepDeepColors().danger,
            )
        }
        return
    }
    if (details == null) {
        MiCloudCardShell(
            header = { CardHeader(icon = icon, title = title, trailing = null) },
            footer = null,
        ) {
            DdLoadingBlock(
                modifier = Modifier.padding(vertical = DdSpacing.Md),
                text = host.t("正在读取…", "Loading…"),
            )
        }
        return
    }
    content(details)
}

@Composable
private fun MiCloudCardShell(
    header: @Composable () -> Unit,
    footer: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    DdGroupCard(header = header, footer = footer, content = content)
}

@Composable
private fun CardHeader(
    icon: DdIcon,
    title: String,
    trailing: String?,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        DdIcon(icon = icon, tint = colors.accent, size = 15.dp)
        DdText(
            text = title,
            modifier = Modifier.weight(1f),
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!trailing.isNullOrBlank()) {
            DdText(
                text = trailing,
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CardRow(
    divider: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (divider) DdRowDivider(leadingInset = DdSpacing.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
    ) {
        content()
    }
}

@Composable
private fun EmptyRow(text: String) {
    CardRow(divider = false) {
        DdText(
            text = text,
            style = DdType.bodySmall,
            color = deepDeepColors().inkTertiary,
        )
    }
}

@Composable
private fun ProfileCardBody(host: PluginHost, data: MiCloudAccountCardData) {
    val colors = deepDeepColors()
    val rows = buildList {
        if (data.nickname.isNotBlank()) add(host.t("昵称", "Nickname") to data.nickname)
        if (data.email.isNotBlank()) add(host.t("邮箱", "Email") to data.email)
        if (data.phone.isNotBlank()) add(host.t("手机", "Phone") to data.phone)
    }
    MiCloudCardShell(
        header = {
            CardHeader(
                icon = DdIcons.Cloud,
                title = host.t("小米账号", "Xiaomi account"),
                trailing = data.nickname.ifBlank { null },
            )
        },
        footer = data.userId.takeIf { it.isNotBlank() }?.let { userId ->
            {
                DdText(
                    text = host.tf("用户 ID：%s", "User ID: %s", userId),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
        },
    ) {
        if (rows.isEmpty()) {
            EmptyRow(host.t("没有可显示的账号资料。", "No account details to show."))
        } else {
            rows.forEachIndexed { index, (label, value) ->
                CardRow(divider = index > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdText(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = DdType.bodySmall,
                            color = colors.inkSecondary,
                        )
                        DdText(
                            text = value,
                            style = DdType.bodySmall,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaCardBody(host: PluginHost, data: MiCloudAccountCardData) {
    val colors = deepDeepColors()
    val entries = data.entries.take(MAX_CARD_ROWS)
    val hidden = data.entries.size - entries.size
    MiCloudCardShell(
        header = {
            CardHeader(
                icon = DdIcons.Cloud,
                title = host.t("小米云空间", "Xiaomi Cloud storage"),
                trailing = if (data.entries.isEmpty()) null else host.tf("%d 项", "%d items", data.entries.size),
            )
        },
        footer = {
            DdText(
                text = if (hidden > 0) {
                    host.tf("还有 %d 项；容量以小米云为准。", "%d more; figures come from Xiaomi Cloud.", hidden)
                } else {
                    host.t("容量以小米云为准。", "Storage figures come from Xiaomi Cloud.")
                },
                style = DdType.chip,
                color = colors.inkTertiary,
            )
        },
    ) {
        if (entries.isEmpty()) {
            EmptyRow(host.t("暂时无法读取空间用量。", "Couldn't read storage usage."))
        } else {
            entries.forEachIndexed { index, entry ->
                CardRow(divider = index > 0) {
                    QuotaRow(host, entry)
                }
            }
        }
    }
}

@Composable
private fun QuotaRow(host: PluginHost, entry: MiCloudQuotaEntry) {
    val colors = deepDeepColors()
    val total = entry.totalBytes
    val used = entry.usedBytes
    val free = entry.freeBytes ?: if (total != null && used != null) (total - used).coerceAtLeast(0L) else null
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = host.t(quotaLabelZh(entry.label), quotaLabelEn(entry.label)),
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = when {
                total != null && used != null -> host.tf(
                    "已用 %s / 共 %s",
                    "used %s of %s",
                    formatBytes(used),
                    formatBytes(total),
                )

                used != null -> host.tf("已用 %s", "used %s", formatBytes(used))
                total != null -> host.tf("共 %s", "total %s", formatBytes(total))
                else -> host.t("暂无数据", "No data")
            }
            DdText(
                text = summary,
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
        if (free != null) {
            DdText(
                text = host.tf("剩余 %s", "%s free", formatBytes(free)),
                style = DdType.chip,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun NotesCardBody(host: PluginHost, data: MiCloudNotesCardData) {
    val colors = deepDeepColors()
    when (data.action) {
        NOTES_ACTION_READ -> NotesReadBody(host, data)
        NOTES_ACTION_CREATE, NOTES_ACTION_UPDATE, NOTES_ACTION_DELETE -> NotesWriteBody(host, data)
        else -> {
            val shown = data.rows.take(MAX_CARD_ROWS)
            MiCloudCardShell(
                header = {
                    CardHeader(
                        icon = DdIcons.Pen,
                        title = host.t("笔记", "Notes"),
                        trailing = if (data.total == 0) null else host.tf("共 %d 条", "%d items", data.total),
                    )
                },
                footer = notesListFooter(host, data, shown.size),
            ) {
                if (shown.isEmpty()) {
                    EmptyRow(host.t("没有笔记。", "No notes."))
                } else {
                    shown.forEachIndexed { index, item ->
                        CardRow(divider = index > 0) {
                            NoteRow(host, item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(host: PluginHost, item: MiCloudNoteItem) {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = item.subject.ifBlank { host.t("（无主题）", "(No subject)") },
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val modified = formatTime(item.modifyTime)
            if (modified.isNotBlank()) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(text = modified, style = DdType.chip, color = colors.inkTertiary, maxLines = 1)
            }
        }
        if (item.snippet.isNotBlank()) {
            DdText(
                text = previewText(item.snippet, 120),
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun notesListFooter(
    host: PluginHost,
    data: MiCloudNotesCardData,
    shown: Int,
): (@Composable () -> Unit)? {
    val colors = deepDeepColors()
    val note = when {
        data.total > shown -> host.tf("还有 %d 条", "%d more", data.total - shown)
        data.cursor.isNotBlank() -> host.tf("下一页游标：%s", "Next cursor: %s", data.cursor)
        data.lastPage -> host.t("已到最后一页。", "This is the last page.")
        else -> return null
    }
    return {
        DdText(
            text = note,
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NotesReadBody(host: PluginHost, data: MiCloudNotesCardData) {
    val colors = deepDeepColors()
    MiCloudCardShell(
        header = {
            CardHeader(
                icon = DdIcons.Pen,
                title = host.t("笔记", "Notes"),
                trailing = formatTime(data.modifyTime).ifBlank { null },
            )
        },
        footer = data.noteId.takeIf { it.isNotBlank() }?.let { noteId ->
            {
                DdText(
                    text = host.tf("id：%s", "id: %s", noteId),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
        },
    ) {
        CardRow(divider = false) {
            DdText(
                text = data.subject.ifBlank { host.t("（无主题）", "(No subject)") },
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.preview.isNotBlank()) {
                DdText(
                    text = data.preview,
                    modifier = Modifier.padding(top = DdSpacing.Sm),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NotesWriteBody(host: PluginHost, data: MiCloudNotesCardData) {
    val colors = deepDeepColors()
    val title = when (data.action) {
        NOTES_ACTION_CREATE -> host.t("已新建笔记", "Note created")
        NOTES_ACTION_UPDATE -> host.t("已修改笔记", "Note updated")
        else -> host.t("已删除笔记", "Note deleted")
    }
    MiCloudCardShell(
        header = { CardHeader(icon = DdIcons.Pen, title = title, trailing = formatTime(data.modifyTime).ifBlank { null }) },
        footer = data.noteId.takeIf { it.isNotBlank() }?.let { noteId ->
            {
                DdText(
                    text = host.tf("id：%s", "id: %s", noteId),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
        },
    ) {
        CardRow(divider = false) {
            DdText(
                text = data.subject.ifBlank { host.t("（无主题）", "(No subject)") },
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.preview.isNotBlank()) {
                DdText(
                    text = data.preview,
                    modifier = Modifier.padding(top = DdSpacing.Xxs),
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TodoCardBody(host: PluginHost, data: MiCloudTodoCardData) {
    val colors = deepDeepColors()
    when (data.action) {
        TODO_ACTION_CREATE, TODO_ACTION_UPDATE, TODO_ACTION_DELETE -> TodoWriteBody(host, data)
        else -> {
            val shown = data.rows.take(MAX_CARD_ROWS)
            MiCloudCardShell(
                header = {
                    CardHeader(
                        icon = DdIcons.Check,
                        title = host.t("待办", "To-dos"),
                        trailing = if (data.total == 0) null else host.tf("共 %d 条", "%d items", data.total),
                    )
                },
                footer = {
                    DdText(
                        text = if (data.total == 0) {
                            host.t("没有待办。", "No to-dos.")
                        } else {
                            host.tf("已完成 %d / %d", "%d of %d done", data.doneCount, data.total)
                        },
                        style = DdType.chip,
                        color = colors.inkTertiary,
                    )
                },
            ) {
                if (shown.isEmpty()) {
                    EmptyRow(host.t("没有待办。", "No to-dos."))
                } else {
                    shown.forEachIndexed { index, item ->
                        CardRow(divider = index > 0) {
                            TodoRow(host, item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(host: PluginHost, item: MiCloudTodoItem) {
    val colors = deepDeepColors()
    Row(verticalAlignment = Alignment.Top) {
        if (item.done) {
            DdIcon(
                icon = DdIcons.Check,
                tint = colors.success,
                size = 14.dp,
                contentDescription = host.t("已完成", "Done"),
            )
            Spacer(Modifier.width(DdSpacing.Sm))
        }
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs)) {
            DdText(
                text = item.content.ifBlank { host.t("（无内容）", "(No content)") },
                style = if (item.done) {
                    DdType.bodySmall.copy(textDecoration = TextDecoration.LineThrough)
                } else {
                    DdType.bodySmall
                },
                color = if (item.done) colors.inkTertiary else colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = todoMeta(host, item)
            if (meta.isNotBlank()) {
                DdText(
                    text = meta,
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun todoMeta(host: PluginHost, item: MiCloudTodoItem): String {
    val parts = mutableListOf<String>()
    val remind = formatTime(item.remindTime)
    if (remind.isNotBlank()) parts += host.tf("提醒 %s", "reminder %s", remind)
    val modified = formatTime(item.modifyTime)
    if (modified.isNotBlank()) parts += host.tf("修改 %s", "modified %s", modified)
    if (item.id.isNotBlank()) parts += host.tf("id=%s", "id=%s", item.id)
    return parts.joinToString(" · ")
}

@Composable
private fun TodoWriteBody(host: PluginHost, data: MiCloudTodoCardData) {
    val colors = deepDeepColors()
    val title = when (data.action) {
        TODO_ACTION_CREATE -> host.t("已新建待办", "To-do created")
        TODO_ACTION_UPDATE -> host.t("已修改待办", "To-do updated")
        else -> host.t("已删除待办", "To-do deleted")
    }
    val meta = buildList {
        val remind = formatTime(data.remindTime)
        if (remind.isNotBlank()) add(host.tf("提醒 %s", "reminder %s", remind))
        val modified = formatTime(data.modifyTime)
        if (modified.isNotBlank()) add(host.tf("修改 %s", "modified %s", modified))
    }.joinToString(" · ")
    MiCloudCardShell(
        header = {
            CardHeader(
                icon = DdIcons.Check,
                title = title,
                trailing = if (data.done) host.t("已完成", "Done") else null,
            )
        },
        footer = data.todoId.takeIf { it.isNotBlank() }?.let { todoId ->
            {
                DdText(
                    text = host.tf("id：%s", "id: %s", todoId),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
        },
    ) {
        CardRow(divider = false) {
            DdText(
                text = data.content.ifBlank { host.t("（无内容）", "(No content)") },
                style = if (data.done) {
                    DdType.bodySmall.copy(textDecoration = TextDecoration.LineThrough)
                } else {
                    DdType.bodySmall
                },
                color = if (data.done) colors.inkTertiary else colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                DdText(
                    text = meta,
                    modifier = Modifier.padding(top = DdSpacing.Xxs),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
        }
    }
}
