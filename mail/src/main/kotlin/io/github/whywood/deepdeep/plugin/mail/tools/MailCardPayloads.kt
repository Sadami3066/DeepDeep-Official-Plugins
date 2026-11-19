package io.github.whywood.superpi.plugin.mail.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal object MailChatCardTypes {
    const val SEARCH = "mail.search"
    const val DRAFT = "mail.draft"
    const val READ = "mail.read"
}

internal val mailCardJson = Json { encodeDefaults = true }

internal inline fun <reified T> T.encodeCardPayload(): JsonObject =
    mailCardJson.encodeToJsonElement(this).jsonObject

@Serializable
internal data class MailSearchCardPayload(
    val type: String = MailChatCardTypes.SEARCH,
    val query: String = "",
    val account: String = "",
    val folder: String = "",
    val unreadOnly: Boolean = false,
    val total: Int = 0,
    val items: List<MailSearchCardItem> = emptyList(),
)

@Serializable
internal data class MailSearchCardItem(
    val accountId: String = "",
    val messageId: String = "",
    val threadId: String = "",
    val subject: String = "",
    val from: String = "",
    val to: List<String> = emptyList(),
    val preview: String = "",
    val dateMillis: Long = 0L,
    val unread: Boolean = false,
    val starred: Boolean = false,
    val hasAttachments: Boolean = false,
    val sizeBytes: Long = 0L,
    val folderRole: String = "",
    val sent: Boolean = false,
    val account: String = "",
    val accountColor: Int = 0,
)

@Serializable
internal data class MailDraftCardPayload(
    val type: String = MailChatCardTypes.DRAFT,
    val draftId: String = "",
    val account: String = "",
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val bodyPreview: String = "",
    val attachments: List<MailDraftCardAttachment> = emptyList(),
)

@Serializable
internal data class MailDraftCardAttachment(
    val name: String = "",
    val sizeBytes: Long = 0L,
)

@Serializable
internal data class MailReadCardPayload(
    val type: String = MailChatCardTypes.READ,
    val accountId: String = "",
    val messageId: String = "",
    val threadId: String = "",
    val subject: String = "",
    val fromName: String = "",
    val fromAddress: String = "",
    val to: List<String> = emptyList(),
    val account: String = "",
    val folderRole: String = "",
    val dateMillis: Long = 0L,
    val preview: String = "",
    val body: String = "",
    val unread: Boolean = false,
    val textTruncated: Boolean = false,
    val attachments: List<MailReadCardAttachment> = emptyList(),
)

@Serializable
internal data class MailReadCardAttachment(
    val id: String = "",
    val name: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val inline: Boolean = false,
    val contentId: String? = null,
)
