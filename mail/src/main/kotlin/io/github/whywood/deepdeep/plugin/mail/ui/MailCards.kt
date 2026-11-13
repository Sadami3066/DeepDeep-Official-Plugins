package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import io.github.whywood.deepdeep.plugin.mail.MailDraftToolDetails
import io.github.whywood.deepdeep.plugin.mail.MailListToolDetails
import io.github.whywood.deepdeep.plugin.mail.MailListToolItem
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailSendResult
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val cardJson = Json { ignoreUnknownKeys = true }

private sealed interface DraftCardSendState {
    data object Idle : DraftCardSendState

    data object Sending : DraftCardSendState

    data object Sent : DraftCardSendState

    data class Failed(val message: String) : DraftCardSendState
}

@Composable
internal fun MailSearchCard(
    host: PluginHost,
    state: ToolCardSlotState,
) {
    val colors = deepDeepColors()
    val details = remember(state.result) {
        val result = state.result ?: return@remember null
        if (result.isError) return@remember null
        result.details?.let { element ->
            runCatching { cardJson.decodeFromJsonElement(MailListToolDetails.serializer(), element) }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        when {
            details == null -> Text(
                text = resultPreview(
                    host = host,
                    result = state.result,
                    fallback = if (state.result == null) {
                        val query = state.args.stringArg("query")
                        if (query.isBlank()) {
                            host.t("正在搜索邮件", "Searching mail")
                        } else {
                            host.tf("正在搜索「%s」", "Searching “%s”", displayPreview(query, limit = 40))
                        }
                    } else {
                        host.t("正在搜索邮件", "Searching mail")
                    },
                ),
                modifier = Modifier.padding(vertical = 6.dp),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            details.items.isEmpty() -> Text(
                text = host.t("没有找到匹配的邮件。", "No matching mail."),
                modifier = Modifier.padding(vertical = 6.dp),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )

            else -> details.items.take(5).forEachIndexed { index, item ->
                if (index > 0) DdRowDivider(leadingInset = 0.dp)
                SearchCardItem(host = host, item = item)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdTextAction(
                text = host.t("打开邮箱", "Open mail"),
                onClick = { host.ui().openPage("mail") },
            )
        }
    }
}

@Composable
private fun SearchCardItem(host: PluginHost, item: MailListToolItem) {
    val colors = deepDeepColors()
    val from = item.from.ifBlank { item.account }
    val preview = displayPreview(item.preview, limit = 90)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.unread) {
                MailUnreadDot(size = 6.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = displaySubject(item.subject, host.t("（无主题）", "(No subject)")),
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall.copy(
                    fontWeight = if (item.unread) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = mailListTime(item.dateMillis, System.currentTimeMillis(), host.chinese),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        if (from.isNotBlank() || preview.isNotBlank()) {
            Text(
                text = listOf(from, preview).filter { it.isNotBlank() }.joinToString(" · "),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun MailDraftCard(
    host: PluginHost,
    repository: MailRepository,
    state: ToolCardSlotState,
) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val details = remember(state.result) {
        val result = state.result ?: return@remember null
        if (result.isError) return@remember null
        result.details?.let { element ->
            runCatching { cardJson.decodeFromJsonElement(MailDraftToolDetails.serializer(), element) }.getOrNull()
        }
    }
    var sendState by remember(state.callId) { mutableStateOf<DraftCardSendState>(DraftCardSendState.Idle) }

    fun send(draftId: String) {
        if (sendState is DraftCardSendState.Sending || sendState is DraftCardSendState.Sent) return
        sendState = DraftCardSendState.Sending
        scope.launch {
            val result = runCatching { repository.sendDraft(draftId) }.fold(
                onSuccess = { it },
                onFailure = {
                    MailSendResult(
                        ok = false,
                        error = host.readableError(
                            it,
                            fallbackZh = "发送失败，请重试",
                            fallbackEn = "Couldn't send. Try again.",
                        ),
                    )
                },
            )
            sendState = if (result.ok) {
                DraftCardSendState.Sent
            } else {
                DraftCardSendState.Failed(
                    result.error?.takeIf { it.isNotBlank() }
                        ?: host.t("发送失败，请重试。", "Couldn't send. Try again."),
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (details == null) {
            DraftArgsPreview(host = host, state = state)
            Text(
                text = resultPreview(host, state.result, host.t("正在准备草稿", "Preparing draft")),
                modifier = Modifier.padding(vertical = 6.dp),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            DraftCardRow(label = host.t("收件人", "To"), value = details.to.joinToString(", "), colors = colors)
            DraftCardRow(label = host.t("主题", "Subject"), value = details.subject, colors = colors)
            if (details.bodyPreview.isNotBlank()) {
                Text(
                    text = displayPreview(details.bodyPreview, limit = 220),
                    modifier = Modifier.padding(top = 4.dp),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        when (val current = sendState) {
            is DraftCardSendState.Idle -> if (details != null) Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(
                    text = host.t("发送", "Send"),
                    onClick = { send(details.draftId) },
                    color = colors.accent,
                )
            }

            is DraftCardSendState.Sending -> if (details != null) Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = host.t("发送中…", "Sending…"),
                    style = DdType.label,
                    color = colors.inkTertiary,
                )
            }

            is DraftCardSendState.Sent -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdIcon(icon = DdIcons.Check, tint = colors.success, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = host.t("已发送", "Sent"),
                    style = DdType.label,
                    color = colors.success,
                )
            }

            is DraftCardSendState.Failed -> Column(Modifier.fillMaxWidth()) {
                Text(
                    text = current.message,
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = DdType.bodySmall,
                    color = colors.danger,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdTextAction(
                        text = host.t("重试", "Retry"),
                        onClick = { details?.let { send(it.draftId) } },
                        color = colors.accent,
                        enabled = details != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftArgsPreview(host: PluginHost, state: ToolCardSlotState) {
    if (state.result != null) return
    val to = state.args.stringArg("to")
    val subject = state.args.stringArg("subject")
    val body = state.args.stringArg("body")
    if (to.isBlank() && subject.isBlank() && body.isBlank()) return
    DraftCardRow(label = host.t("收件人", "To"), value = to, colors = deepDeepColors())
    DraftCardRow(label = host.t("主题", "Subject"), value = subject, colors = deepDeepColors())
    if (body.isNotBlank()) {
        Text(
            text = displayPreview(body, limit = 220),
            modifier = Modifier.padding(top = 4.dp),
            style = DdType.bodySmall,
            color = deepDeepColors().inkSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun JsonObject.stringArg(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

@Composable
private fun DraftCardRow(
    label: String,
    value: String,
    colors: DeepDeepColors,
) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(52.dp),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Text(
            text = value.ifBlank { "—" },
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun resultPreview(host: PluginHost, result: PluginToolResult?, fallback: String): String {
    val text = result?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    if (!text.isNullOrEmpty()) return text
    if (result?.isError == true) return host.t("操作失败，请重试。", "Something went wrong. Try again.")
    return fallback
}
