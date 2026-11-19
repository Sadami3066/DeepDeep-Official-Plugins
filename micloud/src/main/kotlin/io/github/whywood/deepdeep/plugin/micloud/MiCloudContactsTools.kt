package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.PluginTool
import io.github.whywood.superpi.plugin.api.pluginTool
import io.github.whywood.superpi.plugin.api.textResult
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal fun contactsTool(client: MiCloudReadApi, text: MiCloudText): PluginTool = pluginTool(
    name = "mi_cloud_contacts",
    label = "mi_cloud_contacts",
    description = "List or search contacts in Xiaomi Cloud. Read-only. Contacts are fetched once from the incremental-sync endpoint; search filters that fetched set on the device, and there is no next page.",
    parameters = contactsParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult(MiCloudFeature.CONTACTS, text) {
        val args = call.args
        val userId = requiredUserId(client)
        val limit = limitArg(args)
        val response = client.getJson(contactsPath(userId, now(), limit))
        val payload = response.json.payloadObject()
        val query = args.argString("query")?.trim()?.takeIf { it.isNotBlank() }
        val fetched = contactItems(response.json)
        val contacts = fetched.filter { contact -> query == null || contact.matches(query) }
        val card = MiCloudContactsCardData(
            contacts = contacts,
            groups = payload.arrayOrNull("group") ?: JsonArray(emptyList()),
        )
        textResult(
            text = "",
            details = toolResultDetails(
                raw = response.body,
                guidance = listOf(
                    contactsListGuidance(
                        count = contacts.size,
                        fetched = fetched.size,
                        searching = query != null,
                        t = text,
                    ),
                    rawNoiseNote(text),
                ).filter(String::isNotBlank).joinToString(" "),
                card = MiCloudCard(
                    title = text("联系人", "Contacts"),
                    payload = card.toJson(),
                ),
            ),
        )
    }
}

private fun MiCloudContactItem.matches(query: String): Boolean {
    val needle = query.lowercase(Locale.ROOT)
    return buildString {
        append(name).append(' ')
        append(pinyin).append(' ')
        append(spinyin).append(' ')
        append(organization).append(' ')
        append(phones.joinToString(" ")).append(' ')
        append(emails.joinToString(" ")).append(' ')
        append(source?.toString().orEmpty())
    }.lowercase(Locale.ROOT).contains(needle)
}

internal fun contactsPath(userId: String, ts: Long, limit: Int): String =
    "/contacts/${encodeComponent(userId)}/initdata?limit=$limit&syncTag=0&syncIgnoreTag=0&ts=$ts"

private val contactsParameters: JsonObject = toolParams {
    put(
        "action",
        propEnum(
            "Action to perform: list or search (default list, search filters the fetched set locally)",
            listOf("list", "search"),
        ),
    )
    put("query", propString("Text to match against contact fields, used with action=search"))
    put("limit", propInt("Number of contacts to fetch", minimum = 1, maximum = MAX_LIST_LIMIT, default = 100))
}
