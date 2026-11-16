package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal fun contactsTool(client: MiCloudReadApi, text: MiCloudText): PluginTool = pluginTool(
    name = "mi_cloud_contacts",
    label = "mi_cloud_contacts",
    description = "List or search contacts in Xiaomi Cloud. Read-only.",
    parameters = contactsParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val args = call.args
        val userId = requiredUserId(client)
        val limit = limitArg(args)
        val syncTag = args.argString("syncTag") ?: "0"
        val syncIgnoreTag = args.argString("syncIgnoreTag") ?: "0"
        val response = client.getJson(contactsPath(userId, now(), limit, syncTag, syncIgnoreTag))
        val payload = response.json.payloadObject()
        val query = args.argString("query")
        val contacts = contactObjects(response.json)
            .filter { contact ->
                if (query == null) {
                    true
                } else {
                    val needle = query.lowercase(Locale.ROOT)
                    contact.toString().lowercase(Locale.ROOT).contains(needle)
                }
            }
        val hasMore = payload.boolOrNull("hasMore") ?: false
        textResult(
            contactsObservation(
                contacts = contacts,
                nextSyncTag = cursorValue(payload.stringOrNull("syncTag")),
                nextSyncIgnoreTag = cursorValue(payload.stringOrNull("syncIgnoreTag")),
                hasMore = hasMore,
                t = text,
            ),
            details = rawResultDetails(response.body),
        )
    }
}

private fun contactObjects(element: JsonElement): List<JsonObject> {
    val payload = element.payloadElement()
    val source = when (payload) {
        is JsonArray -> payload
        is JsonObject -> payload["content"] ?: payload["contacts"] ?: payload["list"]
        else -> null
    }
    return when (source) {
        is JsonArray -> source.mapNotNull { it as? JsonObject }
        is JsonObject -> source.values.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
}

internal fun contactsPath(
    userId: String,
    ts: Long,
    limit: Int,
    syncTag: String,
    syncIgnoreTag: String,
): String = "/contacts/${encodeComponent(userId)}/initdata?limit=$limit" +
    "&syncTag=${encodeComponent(syncTag)}&syncIgnoreTag=${encodeComponent(syncIgnoreTag)}&ts=$ts"

private val contactsParameters: JsonObject = toolParams {
    put(
        "action",
        propEnum("Action to perform: list or search (default list)", listOf("list", "search")),
    )
    put("query", propString("Text to match against contact fields, used with action=search"))
    put("limit", propInt("Number of contacts to fetch", minimum = 1, maximum = MAX_LIST_LIMIT, default = 100))
    put("syncTag", propString("Sync tag returned by a previous contacts call (default 0)"))
    put("syncIgnoreTag", propString("Ignore tag returned by a previous contacts call (default 0)"))
}
