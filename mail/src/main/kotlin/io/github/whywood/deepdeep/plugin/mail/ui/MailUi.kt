package io.github.whywood.deepdeep.plugin.mail.ui

import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.slotBinding
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.tools.MailChatCardTypes

internal fun mailUi(host: PluginHost, repository: MailRepository): PluginUiContributions =
    object : PluginUiContributions {
        override val bindings = listOf(
            slotBinding(
                slotId = SlotIds.chatMessage(MailChatCardTypes.SEARCH),
                content = composeSlot { state ->
                    val custom = state as? CustomMessageSlotState ?: return@composeSlot
                    MailSearchChatCard(host = host, state = custom)
                },
            ),
            slotBinding(
                slotId = SlotIds.chatMessage(MailChatCardTypes.DRAFT),
                content = composeSlot { state ->
                    val custom = state as? CustomMessageSlotState ?: return@composeSlot
                    MailDraftChatCard(host = host, repository = repository, state = custom)
                },
            ),
            slotBinding(
                slotId = SlotIds.chatMessage(MailChatCardTypes.READ),
                content = composeSlot { state ->
                    val custom = state as? CustomMessageSlotState ?: return@composeSlot
                    MailReadChatCard(host = host, state = custom)
                },
            ),
        )

        override val pages = listOf(
            pluginPage(
                id = "mail",
                title = localizedText(default = "邮箱", zh = "邮箱", en = "Mail"),
                content = { MailPage(host, repository) },
            ),
        )
    }
