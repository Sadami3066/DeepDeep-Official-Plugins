package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TOOL_TODO_WRITE_OFF =
    "To-do writing is off; enable the to-do switch on the Mi Cloud plugin page"

internal fun todoTool(
    client: MiCloudReadApi,
    settings: MiCloudSettings,
    writer: MiCloudWriteApi,
    text: MiCloudText,
): PluginTool = pluginTool(
    name = "mi_cloud_todo",
    label = "mi_cloud_todo",
    description = "List, create, update or delete to-do items in Xiaomi Cloud. Reads and writes the user's own to-do list; writes are only allowed when the to-do write switch is on.",
    parameters = todoParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val args = call.args
        when (val action = args.argString("action") ?: "list") {
            "list" -> {
                val limit = limitArg(args)
                val response = client.getJson(
                    todoRecordsPath(now(), limit, args.argString("cursor")),
                )
                val payload = response.payloadObject()
                val items = payload.arrayOrNull("records").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map(::todoItem)
                val cursor = payload.stringOrNull("syncToken").orEmpty()
                val hasMore = payload.boolOrNull("hasMore") ?: false
                val card = MiCloudTodoCardData(
                    action = TODO_ACTION_LIST,
                    total = items.size,
                    doneCount = items.count { it.done },
                    cursor = cursor,
                    hasMore = hasMore,
                    rows = items.take(MAX_CARD_ROWS),
                )
                textResult(
                    todoListObservation(items.take(MAX_OBSERVATION_ITEMS), cursor, hasMore, text),
                    details = card.toJson(),
                )
            }

            "create" -> {
                requireWrite(settings, MiCloudFeature.TODOS, TOOL_TODO_WRITE_OFF)
                val content = args.requiredArgString("content")
                val remindAt = args.argLong("remindAt")
                val timestamp = now()
                val record = buildTodoRecord(
                    content = content,
                    now = timestamp,
                    remindAt = remindAt,
                )
                val response = parseResponseObject(writer.post(todoCreatePath(), todoCreateForm(record)))
                val todoId = responseId(response)
                val card = MiCloudTodoCardData(
                    action = TODO_ACTION_CREATE,
                    total = 1,
                    todoId = todoId,
                    content = content,
                    remindTime = remindAt ?: 0L,
                    modifyTime = timestamp,
                )
                textResult(
                    todoWriteObservation(TODO_ACTION_CREATE, todoId, content, false, remindAt ?: 0L, timestamp, text),
                    details = card.toJson(),
                )
            }

            "update" -> {
                requireWrite(settings, MiCloudFeature.TODOS, TOOL_TODO_WRITE_OFF)
                val id = args.requiredArgString("id")
                val content = args.argString("content")
                val done = args.argBool("done")
                val remindAt = args.argLong("remindAt")
                if (content == null && done == null && remindAt == null) {
                    throw IllegalArgumentException("update requires content, done or remindAt")
                }
                val existing = todoRecord(client, id)
                val existingItem = todoItem(existing)
                val eTag = etagOf(existing)
                    ?: throw IllegalArgumentException("To-do has no eTag; cannot update")
                val timestamp = now()
                val updated = updatedTodoRecord(existing, content, done, remindAt, timestamp)
                parseResponseObject(writer.post(todoUpdatePath(id), todoUpdateForm(eTag, updated)))
                val updatedContent = content ?: existingItem.content
                val updatedDone = done ?: existingItem.done
                val updatedRemind = remindAt ?: existingItem.remindTime
                val card = MiCloudTodoCardData(
                    action = TODO_ACTION_UPDATE,
                    total = 1,
                    todoId = id,
                    content = updatedContent,
                    done = updatedDone,
                    remindTime = updatedRemind,
                    modifyTime = timestamp,
                )
                textResult(
                    todoWriteObservation(
                        TODO_ACTION_UPDATE,
                        id,
                        updatedContent,
                        updatedDone,
                        updatedRemind,
                        timestamp,
                        text,
                    ),
                    details = card.toJson(),
                )
            }

            "delete" -> {
                requireWrite(settings, MiCloudFeature.TODOS, TOOL_TODO_WRITE_OFF)
                val id = args.requiredArgString("id")
                val existing = todoRecord(client, id)
                val existingItem = todoItem(existing)
                val eTag = etagOf(existing)
                    ?: throw IllegalArgumentException("To-do has no eTag; cannot delete")
                parseResponseObject(writer.post(todoDeletePath(id), todoDeleteForm(eTag)))
                val card = MiCloudTodoCardData(
                    action = TODO_ACTION_DELETE,
                    total = 1,
                    todoId = id,
                    content = existingItem.content,
                    done = existingItem.done,
                )
                textResult(
                    todoWriteObservation(
                        TODO_ACTION_DELETE,
                        id,
                        existingItem.content,
                        existingItem.done,
                        0L,
                        0L,
                        text,
                    ),
                    details = card.toJson(),
                )
            }

            else -> throw IllegalArgumentException("action must be list, create, update or delete")
        }
    }
}

private suspend fun todoRecord(client: MiCloudReadApi, id: String): JsonObject {
    val payload = client.getJson(todoRecordPath(id)).payloadObject()
    return payload.objectOrNull("record") ?: payload ?: throw IOException("To-do data format is invalid")
}

private fun etagOf(record: JsonObject): String? =
    (record["eTag"] as? JsonPrimitive)?.contentOrNull
        ?: (record["etag"] as? JsonPrimitive)?.contentOrNull

internal fun todoRecordsPath(ts: Long, limit: Int, cursor: String?): String {
    val base = "/todo/v1/user/records?ts=$ts&limit=$limit"
    return if (cursor == null) base else "$base&syncToken=${encodeComponent(cursor)}"
}

internal fun todoRecordPath(id: String): String = "/todo/v1/user/records/${encodeComponent(id)}"

internal fun todoCreatePath(): String = "/todo/v1/user/records"

internal fun todoUpdatePath(id: String): String = "/todo/v1/user/records/${encodeComponent(id)}/update"

internal fun todoDeletePath(id: String): String = "/todo/v1/user/records/${encodeComponent(id)}/delete"

internal fun todoCreateForm(record: JsonObject): Map<String, String> = mapOf("record" to record.toString())

internal fun todoUpdateForm(previousETag: String, record: JsonObject): Map<String, String> = mapOf(
    "previousETag" to previousETag,
    "record" to record.toString(),
)

internal fun todoDeleteForm(prevETag: String): Map<String, String> = mapOf("prevETag" to prevETag)

internal fun buildTodoRecord(
    content: String,
    now: Long,
    remindAt: Long?,
    done: Boolean = false,
    finishTime: Long = 0,
): JsonObject = buildJsonObject {
    put("type", "entity")
    putJsonObject("contentJson") {
        put("listType", 0)
        put("content", content)
        put("plainText", content)
        put("isFinish", if (done) 1 else 0)
        put("markFinishTime", finishTime)
        put("remindType", if (remindAt != null) 2 else 0)
        put("remindRepeatType", 0)
        put("remindTime", remindAt ?: 0)
        put("firstRemindTime", remindAt ?: 0)
        put("audioFileField", "")
        put("audioFileName", "")
        put("audioFileSize", 0)
        put("createTime", now)
        put("lastModifiedTime", now)
        put("inputType", 0)
        put("id", 0)
        put("customSortId", 0)
        put("expireTime", 0)
        put("hideType", 0)
        put("folderId", 0)
        put("colorLabel", 0)
        put("source", 0)
        put("localStatus", 0)
        put("serverStatus", 0)
        put("is_finish", done)
        putJsonArray("assets") {}
    }
}

internal fun updatedTodoRecord(
    existing: JsonObject,
    content: String?,
    done: Boolean?,
    remindAt: Long?,
    now: Long,
): JsonObject {
    val record = existing.toMutableMap()
    val contentJson = (existing["contentJson"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    if (content != null) {
        contentJson["content"] = JsonPrimitive(content)
        contentJson["plainText"] = JsonPrimitive(content)
    }
    if (done != null) {
        contentJson["isFinish"] = JsonPrimitive(if (done) 1 else 0)
        contentJson["markFinishTime"] = JsonPrimitive(if (done) now else 0)
        contentJson["is_finish"] = JsonPrimitive(done)
    }
    if (remindAt != null) {
        contentJson["remindType"] = JsonPrimitive(2)
        contentJson["remindTime"] = JsonPrimitive(remindAt)
        contentJson["firstRemindTime"] = JsonPrimitive(remindAt)
    }
    contentJson["lastModifiedTime"] = JsonPrimitive(now)
    record["contentJson"] = JsonObject(contentJson)
    return JsonObject(record)
}

private val todoParameters: JsonObject = toolParams(required = listOf("action")) {
    put(
        "action",
        propEnum(
            "Action to perform: list, create, update or delete",
            listOf("list", "create", "update", "delete"),
        ),
    )
    put("id", propString("To-do item id, required for update and delete"))
    put("content", propString("To-do text, required for create"))
    put("done", propBool("Mark the item finished or unfinished"))
    put("remindAt", propLong("Reminder time in epoch milliseconds"))
    put("cursor", propString("Pagination cursor returned as next by a previous list call"))
    put("limit", propInt("Number of items to return", minimum = 1, maximum = MAX_LIST_LIMIT, default = DEFAULT_LIST_LIMIT))
}
