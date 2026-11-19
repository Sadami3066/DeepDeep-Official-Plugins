package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.PluginTool
import io.github.whywood.superpi.plugin.api.pluginTool
import io.github.whywood.superpi.plugin.api.textResult
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MAX_SNIPPET_CHARS = 200

private const val TOOL_NOTE_WRITE_OFF =
    "Note writing is off; enable the notes switch on the Mi Cloud plugin page"

internal fun notesTool(
    client: MiCloudReadApi,
    settings: MiCloudSettings,
    writer: MiCloudWriteApi,
    text: MiCloudText,
): PluginTool = pluginTool(
    name = "mi_cloud_notes",
    label = "mi_cloud_notes",
    description = "List, read, create, update or delete notes in Xiaomi Cloud. Reads and writes the user's own notes; writes are only allowed when the notes write switch is on.",
    parameters = notesParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult(MiCloudFeature.NOTES, text) {
        val args = call.args
        when (val action = args.argString("action") ?: "list") {
            "list" -> {
                val limit = limitArg(args)
                val response = client.getJson(notesPagePath(now(), limit, args.argString("syncTag")))
                val payload = response.json.payloadObject()
                val entries = payload.arrayOrNull("entries").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map(::noteItem)
                val cursor = cursorValue(payload.stringOrNull("syncTag"))
                val lastPage = payload.boolOrNull("lastPage") ?: false
                val card = MiCloudNotesCardData(
                    action = NOTES_ACTION_LIST,
                    total = entries.size,
                    cursor = cursor,
                    lastPage = lastPage,
                    rows = entries,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        guidance = listOf(
                            notesListGuidance(entries.size, cursor, lastPage, text),
                            rawNoiseNote(text),
                        ).filter(String::isNotBlank).joinToString(" "),
                        card = MiCloudCard(
                            title = text("我的便签", "My notes"),
                            payload = card.toJson(),
                        ),
                    ),
                )
            }

            "read" -> {
                val id = args.requiredArgString("id")
                val response = noteDetail(client, id)
                val entry = response.json as JsonObject
                val item = noteItem(entry)
                val plain = notePlainText(entry.stringOrNull("content"))
                val card = MiCloudNotesCardData(
                    action = NOTES_ACTION_READ,
                    total = 1,
                    noteId = id,
                    subject = item.subject,
                    preview = plain,
                    modifyTime = item.modifyTime,
                    rows = listOf(item),
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        card = MiCloudCard(
                            title = text.fmt(
                                "便签 · %s",
                                "Note · %s",
                                item.subject.ifBlank { text("（无主题）", "(No subject)") },
                            ),
                            payload = card.toJson(),
                        ),
                    ),
                )
            }

            "create" -> {
                requireWrite(settings, MiCloudFeature.NOTES, TOOL_NOTE_WRITE_OFF)
                val subject = args.requiredArgString("subject")
                val content = args.argString("content").orEmpty()
                val timestamp = now()
                val entry = buildNoteEntry(
                    subject = subject,
                    content = content,
                    folderId = args.argString("folderId") ?: "0",
                    colorId = args.argInt("colorId") ?: 0,
                    now = timestamp,
                )
                val responseBody = writer.post(noteCreatePath(), noteCreateForm(entry))
                val response = parseResponseObject(responseBody)
                val noteId = responseId(response)
                val card = MiCloudNotesCardData(
                    action = NOTES_ACTION_CREATE,
                    total = 1,
                    noteId = noteId,
                    subject = subject,
                    preview = content,
                    modifyTime = timestamp,
                    response = response,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = responseBody,
                        card = MiCloudCard(
                            title = text("已新建便签", "Note created"),
                            payload = card.toJson(),
                        ),
                    ),
                )
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
                val timestamp = now()
                val updated = updatedNoteEntry(existing.json as JsonObject, subject, content, timestamp)
                val responseBody = writer.post(noteUpdatePath(id), noteUpdateForm(updated))
                val response = parseResponseObject(responseBody)
                val updatedSubject = updated.stringOrNull("subject").orEmpty()
                val updatedContent = notePlainText(updated.stringOrNull("content"))
                val card = MiCloudNotesCardData(
                    action = NOTES_ACTION_UPDATE,
                    total = 1,
                    noteId = id,
                    subject = updatedSubject,
                    preview = updatedContent,
                    modifyTime = timestamp,
                    response = response,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = responseBody,
                        card = MiCloudCard(
                            title = text("已修改便签", "Note updated"),
                            payload = card.toJson(),
                        ),
                    ),
                )
            }

            "delete" -> {
                requireWrite(settings, MiCloudFeature.NOTES, TOOL_NOTE_WRITE_OFF)
                val id = args.requiredArgString("id")
                val entry = noteDetail(client, id).json as JsonObject
                val subject = entry.stringOrNull("subject").orEmpty()
                val tag = entry.stringOrNull("tag")
                    ?: throw IllegalArgumentException("Note has no tag; cannot delete")
                val purge = args.argBool("purge") ?: true
                val responseBody = writer.post(noteDeletePath(id), noteDeleteForm(tag, purge))
                val response = parseResponseObject(responseBody)
                val card = MiCloudNotesCardData(
                    action = NOTES_ACTION_DELETE,
                    total = 1,
                    noteId = id,
                    subject = subject,
                    response = response,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = responseBody,
                        card = MiCloudCard(
                            title = text("已删除便签", "Note deleted"),
                            payload = card.toJson(),
                        ),
                    ),
                )
            }

            else -> throw IllegalArgumentException("action must be list, read, create, update or delete")
        }
    }
}

private suspend fun noteDetail(client: MiCloudReadApi, id: String): MiCloudResponse {
    val response = client.getJson(noteDetailPath(id, now()))
    val payload = response.json.payloadObject()
    val entry = payload.objectOrNull("entry") ?: payload ?: throw IOException("Note data format is invalid")
    return MiCloudResponse(body = response.body, json = entry)
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
