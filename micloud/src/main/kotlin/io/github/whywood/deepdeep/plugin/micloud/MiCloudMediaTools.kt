package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal fun galleryTool(client: MiCloudReadApi, text: MiCloudText): PluginTool = pluginTool(
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
                val response = client.getJson(galleriesPath(now()))
                val payload = response.json.payloadObject()
                val items = response.json.payloadArray("galleries")
                    .mapNotNull { it as? JsonObject }
                val hasMore = payload.boolOrNull("hasMore") ?: false
                textResult(
                    galleryObservation(items, hasMore, text),
                    details = rawResultDetails(response.body),
                )
            }

            "albums" -> {
                val response = client.getJson(albumsPath(now()))
                val payload = response.json.payloadObject()
                val albums = response.json.payloadArray("albums")
                    .mapNotNull { it as? JsonObject }
                val hasMore = payload.boolOrNull("hasMore") ?: false
                textResult(
                    albumsObservation(albums, hasMore, text),
                    details = rawResultDetails(response.body),
                )
            }

            else -> throw IllegalArgumentException("action must be list or albums")
        }
    }
}

internal fun recordingsTool(
    client: MiCloudReadApi,
    downloadDir: File,
    text: MiCloudText,
): PluginTool = pluginTool(
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
                val items = response.json.payloadArray("list")
                    .mapNotNull { it as? JsonObject }
                val files = items.map { item ->
                    MiCloudFileItem(
                        fileId = item.stringOrNull("id").orEmpty(),
                        name = item.stringOrNull("name").orEmpty(),
                        sizeBytes = item.longOrNull("size") ?: 0L,
                        timeMs = item.longOrNull("createTime") ?: 0L,
                        durationSeconds = recordingDurationSeconds(item.longOrNull("duration")),
                    )
                }
                val card = MiCloudFileCardData(mode = FILES_MODE_LIST, files = files)
                textResult(
                    recordingsObservation(items, offset + items.size, text),
                    details = cardResultDetails(
                        card = card.toJson(),
                        raw = response.body,
                        title = text.fmt("通话录音（%d 条）", "Call recordings (%d)", files.size),
                    ),
                )
            }

            "download" -> {
                val userId = requiredUserId(client)
                val fileId = args.requiredArgString("fileId")
                val name = args.argString("fileName") ?: recordingName(client, userId, fileId)
                downloadDir.mkdirs()
                val target = uniqueFile(downloadDir, safeFileName(name))
                val downloaded = client.download(recordingDownloadPath(userId, fileId), target)
                val file = MiCloudFileItem(
                    fileId = fileId,
                    name = downloaded.name,
                    sizeBytes = downloaded.length(),
                    timeMs = now(),
                )
                val card = MiCloudFileCardData(mode = FILES_MODE_DOWNLOAD, files = listOf(file))
                textResult(
                    recordingDownloadObservation(
                        fileId = fileId,
                        name = downloaded.name,
                        size = downloaded.length(),
                        path = downloaded.absolutePath,
                        t = text,
                    ),
                    details = cardResultDetails(
                        card = card.toJson(),
                        raw = "",
                        title = text("录音已保存", "Recording saved"),
                    ),
                )
            }

            else -> throw IllegalArgumentException("action must be list or download")
        }
    }
}

private suspend fun recordingName(client: MiCloudReadApi, userId: String, fileId: String): String {
    val response = runCatching { client.getJson(recordingsPath(userId, now(), 0, MAX_LIST_LIMIT)) }.getOrNull()
    val items: List<JsonElement> = response?.json?.payloadArray("list").orEmpty()
    val match = items.mapNotNull { it as? JsonObject }
        .firstOrNull { it.stringOrNull("id") == fileId || it.stringOrNull("fileId") == fileId }
    return match?.stringOrNull("name") ?: match?.stringOrNull("fileName") ?: "recording-$fileId"
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
