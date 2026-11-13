package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@Composable
internal fun MailChatCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = deepDeepColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DdShapes.Card)
            .background(colors.raised),
        content = content,
    )
}

@Composable
internal fun MailChatCardHeader(
    icon: DdIcon,
    title: String,
    trailing: String? = null,
    bottomPadding: Dp = DdSpacing.Md,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = icon, tint = colors.accent, size = 15.dp)
        Spacer(Modifier.width(DdSpacing.Sm))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(DdSpacing.Sm))
            Text(
                text = trailing,
                modifier = Modifier.widthIn(max = 160.dp),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun MailChatCardFooter(
    actionLabel: String,
    onAction: () -> Unit,
    note: String? = null,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                modifier = Modifier.weight(1f),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        DdTextAction(text = actionLabel, onClick = onAction)
    }
}

@Composable
internal fun MailChatUnavailableCard(host: PluginHost, title: String) {
    val colors = deepDeepColors()
    MailChatCardSurface {
        MailChatCardHeader(icon = DdIcons.Message, title = title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Sm),
        ) {
            Text(
                text = host.t(
                    "这张卡片暂时无法显示，请在邮箱里查看。",
                    "This card can't be shown here. Open Mail to view it.",
                ),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
        MailChatCardFooter(
            actionLabel = host.t("打开邮箱", "Open Mail"),
            onAction = { host.openMailPage() },
        )
    }
}

internal fun PluginHost.openMailPage() {
    runCatching { ui().openPage("mail") }.onFailure {
        runCatching { ui().toast(t("无法打开邮箱页面", "Couldn't open the Mail page")) }
    }
}

internal fun PluginHost.openMailThreadSheet(accountId: String, messageId: String, threadId: String) {
    if (accountId.isBlank() || messageId.isBlank() || threadId.isBlank()) return
    runCatching {
        ui().openPageSheet(
            "mail",
            buildJsonObject {
                put("accountId", accountId)
                put("messageId", messageId)
                put("threadId", threadId)
            },
        )
    }.onFailure {
        runCatching { ui().toast(t("无法打开这封邮件", "Couldn't open this message")) }
    }
}

internal fun PluginHost.openMailDraftPage(draftId: String) {
    if (draftId.isBlank()) return
    runCatching {
        ui().openPageWithArguments(
            "mail",
            buildJsonObject { put("draftId", draftId) },
        )
    }.onFailure {
        runCatching { ui().toast(t("无法打开邮箱页面", "Couldn't open the Mail page")) }
    }
}

internal val mailChatCardJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal fun <T> CustomMessageSlotState.decodeCardPayload(
    deserializer: DeserializationStrategy<T>,
): T? = runCatching { mailChatCardJson.decodeFromJsonElement(deserializer, payload) }.getOrNull()
