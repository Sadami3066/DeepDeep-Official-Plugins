package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
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
    text: MiCloudText,
): List<PluginTool> = buildMiCloudTools(
    client = ClientReadApi(client),
    settings = settings,
    writer = WriterWriteApi(writer),
    downloadDir = downloadDir,
    text = text,
)

internal fun buildMiCloudTools(
    client: MiCloudReadApi,
    settings: MiCloudSettings,
    writer: MiCloudWriteApi,
    downloadDir: File,
    text: MiCloudText = { zh, _ -> zh },
): List<PluginTool> = listOf(
    capabilityGated(accountTool(client, text), settings, MiCloudFeature.ACCOUNT, text),
    capabilityGated(notesTool(client, settings, writer, text), settings, MiCloudFeature.NOTES, text),
    capabilityGated(todoTool(client, settings, writer, text), settings, MiCloudFeature.TODOS, text),
    capabilityGated(smsTool(client, text), settings, MiCloudFeature.SMS, text),
    capabilityGated(contactsTool(client, text), settings, MiCloudFeature.CONTACTS, text),
    capabilityGated(galleryTool(client, text), settings, MiCloudFeature.GALLERY, text),
    capabilityGated(recordingsTool(client, downloadDir, text), settings, MiCloudFeature.RECORDINGS, text),
)

private fun capabilityGated(
    tool: PluginTool,
    settings: MiCloudSettings,
    feature: MiCloudFeature,
    text: MiCloudText,
): PluginTool = pluginTool(
    name = tool.name,
    label = tool.label,
    description = tool.description,
    parameters = tool.parameters,
    promptSnippet = tool.promptSnippet,
    promptGuidelines = tool.promptGuidelines,
) { call ->
    if (!settings.capabilities.value.readEnabled(feature)) {
        textResult(capabilityOffText(feature, text), isError = true)
    } else {
        tool.execute(call)
    }
}

private fun accountTool(client: MiCloudReadApi, text: MiCloudText): PluginTool = pluginTool(
    name = "mi_cloud_account",
    label = "mi_cloud_account",
    description = "Read the signed-in Xiaomi Cloud account profile or storage quota. Read-only.",
    parameters = accountParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val action = call.args.argString("action") ?: "profile"
        when (action) {
            "profile" -> {
                val raw = client.getJson("/status/lite/profile?ts=${now()}")
                val profile = parseProfile(raw)
                val data = miCloudResponseData(raw) ?: JsonObject(emptyMap())
                val card = MiCloudAccountCardData(
                    mode = ACCOUNT_MODE_PROFILE,
                    nickname = profile?.nickname.orEmpty(),
                    userId = profile?.userId.orEmpty(),
                    email = data.stringOrNull("email") ?: data.stringOrNull("mail").orEmpty(),
                    phone = data.stringOrNull("phone") ?: data.stringOrNull("mobile").orEmpty(),
                )
                textResult(accountProfileObservation(profile, data, text), details = card.toJson())
            }

            "quota" -> {
                val raw = client.getJson("/status/vip/quota/summary?ts=${now()}")
                val entries = parseQuotaEntries(raw)
                val card = MiCloudAccountCardData(mode = ACCOUNT_MODE_QUOTA, entries = entries)
                textResult(quotaObservation(entries, text), details = card.toJson())
            }

            else -> throw IllegalArgumentException("action must be profile or quota")
        }
    }
}

private val accountParameters: JsonObject = toolParams {
    put(
        "action",
        propEnum("What to read: profile or quota (default profile)", listOf("profile", "quota")),
    )
}
