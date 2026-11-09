package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun galleryTool(client: MiCloudReadApi): PluginTool = PluginTool(
    name = "mi_cloud_gallery",
    label = "mi_cloud_gallery",
    description = "List Xiaomi Cloud gallery photos/videos or albums. Read-only.",
    parameters = galleryParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val args = call.args
        when (val action = args.argString("action") ?: "list") {
            "list" -> {
                val limit = limitArg(args)
                val response = client.getJson(galleriesPath(now()))
                val payload = response.payloadObject()
                val items = response.payloadArray("galleries")
                    .mapNotNull { it as? JsonObject }
                    .map(::galleryItem)
                    .take(limit)
                val extra = buildJsonObject {
                    payload.boolOrNull("isLastPage")?.let { put("isLastPage", it) }
                }
                itemsResult(items, extra)
            }

            "albums" -> {
                val limit = limitArg(args)
                val response = client.getJson(albumsPath(now()))
                val albums = response.payloadArray("albums")
                    .mapNotNull { it as? JsonObject }
                    .map(::compactObject)
                    .take(limit)
                itemsResult(albums)
            }

            else -> throw IllegalArgumentException("action must be list or albums")
        }
    }
}

internal fun recordingsTool(
    client: MiCloudReadApi,
    downloadDir: File,
): PluginTool = PluginTool(
    name = "mi_cloud_recordings",
    label = "mi_cloud_recordings",
    description = "List or download call recordings stored in Xiaomi Cloud. Downloads are saved into the plugin data directory.",
    parameters = recordingsParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val args = call.args
        when (val action = args.argString("action") ?: "list") {
            "list" -> {
                val userId = requiredUserId(client)
                val limit = limitArg(args)
                val offset = args.argInt("offset") ?: 0
                if (offset < 0) {
                    throw IllegalArgumentException("offset must not be negative")
                }
                val response = client.getJson(recordingsPath(userId, now(), offset, limit))
                val items = response.payloadArray("list")
                    .mapNotNull { it as? JsonObject }
                    .map(::recordingItem)
                val extra = buildJsonObject {
                    put("nextOffset", offset + items.size)
                }
                itemsResult(items, extra)
            }

            "download" -> {
                val userId = requiredUserId(client)
                val fileId = args.requiredArgString("fileId")
                val name = args.argString("fileName") ?: recordingName(client, userId, fileId)
                downloadDir.mkdirs()
                val target = uniqueFile(downloadDir, safeFileName(name))
                val downloaded = client.download(recordingDownloadPath(userId, fileId), target)
                val result = buildJsonObject {
                    put("fileId", fileId)
                    put("name", downloaded.name)
                    put("size", downloaded.length())
                    put("path", downloaded.absolutePath)
                }
                textResult(result.toString())
            }

            else -> throw IllegalArgumentException("action must be list or download")
        }
    }
}

private suspend fun recordingName(client: MiCloudReadApi, userId: String, fileId: String): String {
    val response = runCatching { client.getJson(recordingsPath(userId, now(), 0, MAX_LIST_LIMIT)) }.getOrNull()
    val items: List<JsonElement> = response?.payloadArray("list").orEmpty()
    val match = items.mapNotNull { it as? JsonObject }
        .firstOrNull { it.stringOrNull("id") == fileId || it.stringOrNull("fileId") == fileId }
    return match?.let(::recordingItem)?.stringOrNull("name") ?: "recording-$fileId"
}

private fun galleryItem(item: JsonObject): JsonObject = buildJsonObject {
    put("id", item.stringOrNull("id") ?: "")
    put("fileName", item.stringOrNull("fileName") ?: item.stringOrNull("name") ?: "")
    item.longOrNull("size")?.let { put("size", it) }
    item.stringOrNull("mimeType")?.let { put("mimeType", it) }
    item.longOrNull("dateTaken")?.let { put("dateTaken", it) }
    item.longOrNull("createTime")?.let { put("createTime", it) }
    item.boolOrNull("isFavorite")?.let { put("favorite", it) }
    item.stringOrNull("type")?.let { put("type", it) }
}

private fun recordingItem(item: JsonObject): JsonObject {
    val id = item.stringOrNull("id") ?: item.stringOrNull("fileId") ?: item.stringOrNull("sid") ?: ""
    val name = item.stringOrNull("fileName") ?: item.stringOrNull("name") ?: item.stringOrNull("title") ?: id
    return buildJsonObject {
        put("id", id)
        put("name", name)
        item.longOrNull("size")?.let { put("size", it) }
        item.longOrNull("duration")?.let { put("duration", it) }
        item.longOrNull("createTime")?.let { put("createTime", it) }
    }
}

private fun uniqueFile(directory: File, name: String): File {
    var candidate = File(directory, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "")
    var index = 2
    while (candidate.exists()) {
        val next = if (extension.isEmpty()) "$base-$index" else "$base-$index.$extension"
        candidate = File(directory, next)
        index++
    }
    return candidate
}

internal fun safeFileName(raw: String): String {
    val normalized = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
    return normalized.take(96).ifBlank { "recording" }
}

internal fun galleriesPath(ts: Long): String = "/gallery/user/galleries?ts=$ts"

internal fun albumsPath(ts: Long): String = "/gallery/user/album/list?isShared=false&ts=$ts"

internal fun recordingsPath(userId: String, ts: Long, offset: Int, limit: Int): String =
    "/sfs/${encodeComponent(userId)}/ns/recorder/dir/0/list?offset=$offset&limit=$limit&ts=$ts"

internal fun recordingDownloadPath(userId: String, fileId: String): String =
    "/sfs/${encodeComponent(userId)}/ns/recorder/file/${encodeComponent(fileId)}/storage"

private val galleryParameters: JsonObject = toolParams {
    put(
        "action",
        propEnum("Action to perform: list media items or albums (default list)", listOf("list", "albums")),
    )
    put("limit", propInt("Number of items to return", minimum = 1, maximum = MAX_LIST_LIMIT, default = 50))
}

private val recordingsParameters: JsonObject = toolParams(required = listOf("action")) {
    put(
        "action",
        propEnum("Action to perform: list or download", listOf("list", "download")),
    )
    put("fileId", propString("Recording file id, required for download"))
    put("fileName", propString("Optional file name to use when downloading"))
    put("offset", propInt("Pagination offset for list", minimum = 0))
    put("limit", propInt("Number of recordings to return", minimum = 1, maximum = MAX_LIST_LIMIT, default = DEFAULT_LIST_LIMIT))
}
