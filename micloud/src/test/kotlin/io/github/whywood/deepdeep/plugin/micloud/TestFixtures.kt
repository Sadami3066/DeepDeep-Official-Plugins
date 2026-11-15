package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginRunSignal
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal val chineseText: MiCloudText = { zh, _ -> zh }

internal fun allCapabilities(): MiCloudCapabilities = MiCloudCapabilities(
    notesRead = true,
    notesWrite = true,
    todosRead = true,
    todosWrite = true,
    smsRead = true,
    contactsRead = true,
    galleryRead = true,
    recordingsRead = true,
    accountRead = true,
)

internal fun readOnlyCapabilities(
    gallery: Boolean = true,
    account: Boolean = true,
): MiCloudCapabilities = allCapabilities().copy(galleryRead = gallery, accountRead = account)

internal class FakeSettings(initial: MiCloudCapabilities) : MiCloudSettings {
    private val flow = MutableStateFlow(initial)

    override val capabilities: StateFlow<MiCloudCapabilities> = flow

    override suspend fun setRead(feature: MiCloudFeature, enabled: Boolean) = Unit

    override suspend fun setWrite(feature: MiCloudFeature, enabled: Boolean) = Unit

    override suspend fun setFeature(feature: MiCloudFeature, enabled: Boolean) = Unit

    override suspend fun clear() = Unit
}

internal class FakeReadApi(
    private val responses: List<Pair<String, JsonElement>>,
    private val userId: String = "user-1",
) : MiCloudReadApi {
    val requested = mutableListOf<String>()

    override suspend fun userId(): String? = userId

    override suspend fun getJson(pathWithQuery: String): JsonElement {
        requested += pathWithQuery
        return responses.firstOrNull { (prefix, _) -> pathWithQuery.startsWith(prefix) }?.second
            ?: throw IllegalStateException("unexpected request: $pathWithQuery")
    }

    override suspend fun download(pathWithQuery: String, dest: File): File = dest
}

internal class FakeWriteApi(
    private val response: String = """{"data":{"id":"created-1"}}""",
) : MiCloudWriteApi {
    val posts = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun post(path: String, form: Map<String, String>): String {
        posts += path to form
        return response
    }
}

internal class FakeToolCall(
    override val args: JsonObject,
    override val sessionId: String = "session-1",
) : PluginToolCall {
    override val callId: String = "call-1"
    override val signal: PluginRunSignal? = null

    override fun partial(result: PluginToolResult) = Unit
}

internal fun micloudTools(
    client: MiCloudReadApi,
    settings: MiCloudSettings = FakeSettings(allCapabilities()),
    writer: MiCloudWriteApi = FakeWriteApi(),
    downloadDir: File = File("build/micloud-test-downloads"),
    text: MiCloudText = chineseText,
): Map<String, io.github.whywood.deepdeep.plugin.api.PluginTool> =
    buildMiCloudTools(client, settings, writer, downloadDir, text).associateBy { it.name }
