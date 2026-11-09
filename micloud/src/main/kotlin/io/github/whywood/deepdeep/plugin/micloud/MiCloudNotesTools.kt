package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MAX_SNIPPET_CHARS = 200

private const val TOOL_NOTE_WRITE_OFF =
    "Note writing is off; enable the notes switch on the Mi Cloud plugin page"

internal fun notesTool(
    client: MiCloudReadApi,
    settings: MiCloudSettings,
    writer: MiCloudWriteApi,
): PluginTool = PluginTool(
    name = "mi_cloud_notes",
    label = "mi_cloud_notes",
    description = "List, read, create, update or delete notes in Xiaomi Cloud. Reads and writes the user's own notes; writes are only allowed when the notes write switch is on.",
    parameters = notesParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val args = call.args
        when (val action = args.argString("action") ?: "list") {
            "list" -> {
                val limit = limitArg(args)
                val response = client.getJson(notesPagePath(now(), limit, args.argString("syncTag")))
                val payload = response.payloadObject()
                val entries = payload.arrayOrNull("entries").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map(::noteEntry)
                val extra = buildJsonObject {
                    payload.stringOrNull("syncTag")?.let { put("nextSyncTag", it) }
                    payload.boolOrNull("lastPage")?.let { put("lastPage", it) }
                }
                itemsResult(entries, extra)
            }

            "read" -> {
                val id = args.requiredArgString("id")
                val entry = noteDetail(client, id)
                textResult(compactPayload(entry))
            }

            "create" -> {
                requireWrite(settings, MiCloudFeature.NOTES, TOOL_NOTE_WRITE_OFF)
                val subject = args.requiredArgString("subject")
                val content = args.argString("content").orEmpty()
                val entry = buildNoteEntry(
                    subject = subject,
                    content = content,
                    folderId = args.argString("folderId") ?: "0",
                    colorId = args.argInt("colorId") ?: 0,
                    now = now(),
                )
                writeResult(writer.post(noteCreatePath(), noteCreateForm(entry)))
            }

            "update" -> {
                requireWrite(settings, MiCloudFeature.NOTES, TOOL_NOTE_WRITE_OFF)
                val id = args.requiredArgString("id")
                val subject = args.argString("subject")
                val content = args.argString("content")
                if (subject == null && content == null) {
                    throw IllegalArgumentException("update requires subject or content")
                }
                val existing = noteDetail(client, id)
                val updated = updatedNoteEntry(existing, subject, content, now())
                writeResult(writer.post(noteUpdatePath(id), noteUpdateForm(updated)))
            }

            "delete" -> {
                requireWrite(settings, MiCloudFeature.NOTES, TOOL_NOTE_WRITE_OFF)
                val id = args.requiredArgString("id")
                val entry = noteDetail(client, id)
                val tag = entry.stringOrNull("tag")
                    ?: throw IllegalArgumentException("Note has no tag; cannot delete")
                val purge = args.argBool("purge") ?: true
                writeResult(writer.post(noteDeletePath(id), noteDeleteForm(tag, purge)))
            }

            else -> throw IllegalArgumentException("action must be list, read, create, update or delete")
        }
    }
}

private suspend fun noteDetail(client: MiCloudReadApi, id: String): JsonObject {
    val payload = client.getJson(noteDetailPath(id, now())).payloadObject()
    return payload.objectOrNull("entry") ?: payload ?: throw IOException("Note data format is invalid")
}

private fun noteEntry(item: JsonObject): JsonObject = buildJsonObject {
    put("id", item.stringOrNull("id") ?: "")
    put("subject", item.stringOrNull("subject") ?: "")
    put("snippet", item.stringOrNull("snippet") ?: "")
    item.stringOrNull("tag")?.let { put("tag", it) }
    item.longOrNull("modifyDate")?.let { put("modifyDate", it) }
    item.longOrNull("createDate")?.let { put("createDate", it) }
    item.stringOrNull("folderId")?.let { put("folderId", it) }
}

internal fun notesPagePath(ts: Long, limit: Int, syncTag: String?): String {
    val base = "/note/full/page/?ts=$ts&limit=$limit"
    return if (syncTag == null) base else "$base&syncTag=${encodeComponent(syncTag)}"
}

internal fun noteDetailPath(id: String, ts: Long): String = "/note/note/${encodeComponent(id)}/?ts=$ts"

internal fun noteCreatePath(): String = "/note/note"

internal fun noteUpdatePath(id: String): String = "/note/note/${encodeComponent(id)}"

internal fun noteDeletePath(id: String): String = "/note/full/${encodeComponent(id)}/delete"

internal fun noteCreateForm(entry: JsonObject): Map<String, String> = mapOf("entry" to entry.toString())

internal fun noteUpdateForm(entry: JsonObject): Map<String, String> = mapOf("entry" to entry.toString())

internal fun noteDeleteForm(tag: String, purge: Boolean): Map<String, String> = mapOf(
    "tag" to tag,
    "purge" to if (purge) "1" else "0",
)

internal fun buildNoteEntry(
    subject: String,
    content: String,
    folderId: String,
    colorId: Int,
    now: Long,
): JsonObject = buildJsonObject {
    put("subject", subject)
    put("snippet", content.take(MAX_SNIPPET_CHARS))
    put("content", content)
    put("createDate", now)
    put("modifyDate", now)
    put("colorId", colorId)
    put("folderId", folderId)
    put("status", "normal")
    put("alertDate", 0)
    putJsonObject("setting") {
        put("themeId", 0)
        put("stickyTime", 0)
        put("version", 0)
    }
}

internal fun updatedNoteEntry(
    existing: JsonObject,
    subject: String?,
    content: String?,
    now: Long,
): JsonObject {
    val values = existing.toMutableMap()
    if (subject != null) values["subject"] = JsonPrimitive(subject)
    if (content != null) {
        values["content"] = JsonPrimitive(content)
        values["snippet"] = JsonPrimitive(content.take(MAX_SNIPPET_CHARS))
    }
    values["modifyDate"] = JsonPrimitive(now)
    return JsonObject(values)
}

private val notesParameters: JsonObject = toolParams(required = listOf("action")) {
    put(
        "action",
        propEnum(
            "Action to perform: list, read, create, update or delete",
            listOf("list", "read", "create", "update", "delete"),
        ),
    )
    put("id", propString("Note id, required for read, update and delete"))
    put("subject", propString("Note title, required for create"))
    put("content", propString("Note body text in plain text or Markdown"))
    put("folderId", propString("Note folder id (default 0)"))
    put("colorId", propInt("Note color id (default 0)", minimum = 0))
    put("purge", propBool("Delete permanently instead of moving to trash (default true)", default = true))
    put("syncTag", propString("Sync tag returned by a previous list call for incremental sync"))
    put("limit", propInt("Number of notes to return", minimum = 1, maximum = MAX_LIST_LIMIT, default = DEFAULT_LIST_LIMIT))
}
