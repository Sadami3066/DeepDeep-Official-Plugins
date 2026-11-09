package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

private class ClientReadApi(private val client: MiCloudClient) : MiCloudReadApi {
    override suspend fun userId(): String? = client.userId()

    override suspend fun getJson(pathWithQuery: String): JsonElement = client.getJson(pathWithQuery)

    override suspend fun download(pathWithQuery: String, dest: File): File = client.download(pathWithQuery, dest)
}

private class WriterWriteApi(private val writer: MiCloudWebWriter) : MiCloudWriteApi {
    override suspend fun post(path: String, form: Map<String, String>): String = writer.post(path, form)
}

internal fun miCloudTools(
    client: MiCloudClient,
    settings: MiCloudSettings,
    writer: MiCloudWebWriter,
    downloadDir: File,
): List<PluginTool> = buildMiCloudTools(
    client = ClientReadApi(client),
    settings = settings,
    writer = WriterWriteApi(writer),
    downloadDir = downloadDir,
)

internal fun buildMiCloudTools(
    client: MiCloudReadApi,
    settings: MiCloudSettings,
    writer: MiCloudWriteApi,
    downloadDir: File,
): List<PluginTool> = listOf(
    capabilityGated(accountTool(client), settings, MiCloudFeature.ACCOUNT),
    capabilityGated(notesTool(client, settings, writer), settings, MiCloudFeature.NOTES),
    capabilityGated(todoTool(client, settings, writer), settings, MiCloudFeature.TODOS),
    capabilityGated(smsTool(client), settings, MiCloudFeature.SMS),
    capabilityGated(contactsTool(client), settings, MiCloudFeature.CONTACTS),
    capabilityGated(galleryTool(client), settings, MiCloudFeature.GALLERY),
    capabilityGated(recordingsTool(client, downloadDir), settings, MiCloudFeature.RECORDINGS),
)

private fun capabilityGated(
    tool: PluginTool,
    settings: MiCloudSettings,
    feature: MiCloudFeature,
): PluginTool = PluginTool(
    name = tool.name,
    label = tool.label,
    description = tool.description,
    parameters = tool.parameters,
    promptSnippet = tool.promptSnippet,
    promptGuidelines = tool.promptGuidelines,
) { call ->
    if (!settings.capabilities.value.readEnabled(feature)) {
        PluginToolResult.Text(
            text = "capability is not enabled: open the Xiaomi Cloud plugin page and enable ${feature.name.lowercase()} first",
            isError = true,
        )
    } else {
        tool.execute(call)
    }
}

private fun accountTool(client: MiCloudReadApi): PluginTool = PluginTool(
    name = "mi_cloud_account",
    label = "mi_cloud_account",
    description = "Read the signed-in Xiaomi Cloud account profile or storage quota. Read-only.",
    parameters = accountParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val action = call.args.argString("action") ?: "profile"
        val target = when (action) {
            "profile" -> "/status/lite/profile?ts=${now()}"
            "quota" -> "/status/vip/quota/summary?ts=${now()}"
            else -> throw IllegalArgumentException("action must be profile or quota")
        }
        textResult(compactPayload(client.getJson(target)))
    }
}

private val accountParameters: JsonObject = toolParams {
    put(
        "action",
        propEnum("What to read: profile or quota (default profile)", listOf("profile", "quota")),
    )
}
