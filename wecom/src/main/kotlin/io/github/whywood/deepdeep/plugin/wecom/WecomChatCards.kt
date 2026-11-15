package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.slotBinding

internal fun wecomChatCardBindings(host: PluginHost): List<SlotBinding> =
    WecomCommandKind.entries.map { kind ->
        slotBinding(
            slotId = SlotIds.chatMessage(kind.customType),
            content = composeSlot { state ->
                val custom = state as? CustomMessageSlotState ?: return@composeSlot
                WecomChatCard(host = host, kind = kind, state = custom)
            },
        )
    }

@Composable
private fun WecomChatCard(host: PluginHost, kind: WecomCommandKind, state: CustomMessageSlotState) {
    val model = remember(state.payload) { WecomChatCards.decode(host, state.payload, kind) }
    if (model == null) {
        WecomUnavailableCard(host, kind)
        return
    }
    when (kind) {
        WecomCommandKind.CHAT_GROUPS -> WecomGroupsCard(host, kind, model)
        WecomCommandKind.CHAT_MESSAGES -> WecomMessagesCard(host, kind, model)
        WecomCommandKind.DOC_SEARCH -> WecomDocsSearchCard(host, kind, model)
        WecomCommandKind.DOC_CONTENT -> WecomDocContentCard(host, kind, model)
        WecomCommandKind.CALENDAR_SCHEDULES -> WecomScheduleCard(host, kind, model)
        WecomCommandKind.TODO_LIST -> WecomTodoCard(host, kind, model)
        WecomCommandKind.CONTACT_USERS -> WecomContactsCard(host, kind, model)
    }
}

@Composable
private fun WecomUnavailableCard(host: PluginHost, kind: WecomCommandKind) {
    val colors = deepDeepColors()
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
        ) {
            DdText(
                text = host.t(kind.listLabelZh, kind.listLabelEn),
                style = WecomCardType.eyebrow,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = host.t("这里暂时显示不出来", "Can't show this here"),
                style = WecomCardType.title,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            DdText(
                text = host.t("在对话里再查询一次。", "Run the query again in chat."),
                style = WecomCardType.secondary,
                color = colors.inkSecondary,
                maxLines = 2,
            )
        }
    }
}
