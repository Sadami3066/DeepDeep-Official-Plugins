package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
    miCloudToolResult(MiCloudFeature.GALLERY, text) {
        val args = call.args
        when (val action = args.argString("action") ?: "list") {
            "list" -> {
                val page = (args.argInt("pageNum") ?: 1).coerceAtLeast(1)
                val albumId = args.argString("albumId") ?: "1"
                val pageSize = limitArg(args)
                val response = client.getJson(galleriesPath(now(), albumId, page, pageSize))
                val payload = response.json.payloadObject()
                val items = galleryItems(
                    response.json.payloadArray("galleries", "items", "list", "media"),
                )
                val isLastPage = payload.boolOrNull("isLastPage")
                val hasMore = payload.boolOrNull("hasMore") ?: isLastPage?.not() ?: false
                val card = MiCloudGalleryCardData(
                    mode = GALLERY_MODE_LIST,
                    items = items,
                    hasMore = hasMore,
                    isLastPage = isLastPage,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        guidance = listOf(
                            galleryGuidance(
                                count = items.size,
                                hasMore = hasMore,
                                albums = false,
                                nextPage = if (hasMore) page + 1 else 0,
                                t = text,
                            ),
                            rawNoiseNote(text),
                        ).filter(String::isNotBlank).joinToString(" "),
                        card = MiCloudCard(
                            title = text("相册内容", "Gallery items"),
                            payload = card.toJson(),
                        ),
                    ),
                )
            }

            "albums" -> {
                val response = client.getJson(albumsPath(now()))
                val payload = response.json.payloadObject()
                val items = galleryItems(response.json.payloadArray("albums"))
                    .sortedWith(compareByDescending<MiCloudGalleryItem> { it.itemCount }.thenByDescending { it.timeMs })
                val isLastPage = payload.boolOrNull("isLastPage")
                val hasMore = payload.boolOrNull("hasMore") ?: isLastPage?.not() ?: false
                val card = MiCloudGalleryCardData(
                    mode = GALLERY_MODE_ALBUMS,
                    items = items,
                    hasMore = hasMore,
                    isLastPage = isLastPage,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        guidance = listOf(
                            galleryGuidance(items.size, hasMore, albums = true, t = text),
                            rawNoiseNote(text),
                        ).filter(String::isNotBlank).joinToString(" "),
                        card = MiCloudCard(
                            title = text("我的相册", "My albums"),
                            payload = card.toJson(),
                        ),
                    ),
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
    miCloudToolResult(MiCloudFeature.RECORDINGS, text) {
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
                        name = recordingDisplayName(item.stringOrNull("name").orEmpty()),
                        sizeBytes = item.longOrNull("size") ?: 0L,
                        timeMs = item.longOrNull("createTime")
                            ?: item.longOrNull("create_time")
                            ?: item.longOrNull("modify_time")
                            ?: 0L,
                        durationSeconds = recordingDurationSeconds(item.longOrNull("duration")),
                        source = item,
                    )
                }
                val nextOffset = offset + items.size
                val card = MiCloudFileCardData(
                    mode = FILES_MODE_LIST,
                    files = files,
                    offset = offset,
                    nextOffset = nextOffset,
                    limit = limit,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        guidance = listOf(
                            recordingsListGuidance(items.size, nextOffset, text),
                            rawNoiseNote(text),
                        ).filter(String::isNotBlank).joinToString(" "),
                        card = MiCloudCard(
                            title = text("通话录音", "Call recordings"),
                            payload = card.toJson(),
                        ),
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
                    path = downloaded.absolutePath,
                )
                val card = MiCloudFileCardData(mode = FILES_MODE_DOWNLOAD, files = listOf(file))
                textResult(
                    text = "",
                    details = toolResultDetails(
                        guidance = recordingDownloadGuidance(downloaded.absolutePath, text),
                        card = MiCloudCard(
                            title = text("录音已保存", "Recording saved"),
                            payload = card.toJson(),
                        ),
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

internal fun galleriesPath(ts: Long, albumId: String, pageNum: Int, pageSize: Int): String {
    val endDate = LocalDate.now().plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
    return "/gallery/user/galleries?startDate=20000101&endDate=$endDate" +
        "&pageNum=$pageNum&pageSize=$pageSize&albumId=${encodeComponent(albumId)}&ts=$ts"
}

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
    put("albumId", propString("Album id for action=list (default 1, the camera album)"))
    put("pageNum", propInt("Page number for action=list, starting at 1", minimum = 1, default = 1))
    put("limit", propInt("Photos per page for action=list", minimum = 1, maximum = MAX_LIST_LIMIT, default = 30))
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
