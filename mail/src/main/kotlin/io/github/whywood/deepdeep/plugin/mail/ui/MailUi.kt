package io.github.whywood.deepdeep.plugin.mail.ui

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.slotBinding
import io.github.whywood.deepdeep.plugin.mail.MailRepository

internal fun mailUi(host: PluginHost, repository: MailRepository): PluginUiContributions =
    object : PluginUiContributions {
        override val bindings = listOf(
            slotBinding(
                slotId = SlotIds.toolCard("mail_search"),
                content = composeSlot { state ->
                    val cardState = state as? ToolCardSlotState ?: return@composeSlot
                    MailSearchCard(host = host, state = cardState)
                },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("mail_draft"),
                content = composeSlot { state ->
                    val cardState = state as? ToolCardSlotState ?: return@composeSlot
                    MailDraftCard(host = host, repository = repository, state = cardState)
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
