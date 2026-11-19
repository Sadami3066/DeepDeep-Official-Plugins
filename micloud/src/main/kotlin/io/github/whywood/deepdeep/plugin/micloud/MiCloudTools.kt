package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.PluginTool
import io.github.whywood.superpi.plugin.api.pluginTool
import io.github.whywood.superpi.plugin.api.textResult
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

private class ClientReadApi(private val client: MiCloudClient) : MiCloudReadApi {
    override suspend fun userId(): String? = client.userId()

    override suspend fun getJson(pathWithQuery: String): MiCloudResponse = client.getJson(pathWithQuery)

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
        textResult(
            text = "",
            details = toolResultDetails(guidance = capabilityOffText(feature, text)),
            isError = true,
        )
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
    miCloudToolResult(MiCloudFeature.ACCOUNT, text) {
        val action = call.args.argString("action") ?: "profile"
        when (action) {
            "profile" -> {
                val response = client.getJson("/status/lite/profile?ts=${now()}")
                val profile = parseProfile(response.json)
                val data = miCloudResponseData(response.json) ?: JsonObject(emptyMap())
                val card = MiCloudAccountCardData(
                    mode = ACCOUNT_MODE_PROFILE,
                    nickname = profile?.nickname.orEmpty(),
                    userId = profile?.userId.orEmpty(),
                    email = data.stringOrNull("email") ?: data.stringOrNull("mail").orEmpty(),
                    phone = data.stringOrNull("phone") ?: data.stringOrNull("mobile").orEmpty(),
                    data = data,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        guidance = accountGuidance(profile != null, text),
                        card = MiCloudCard(
                            title = text("小米账号", "Xiaomi account"),
                            payload = card.toJson(),
                        ),
                    ),
                )
            }

            "quota" -> {
                val response = client.getJson("/status/vip/quota/summary?ts=${now()}")
                val entries = parseQuotaEntries(response.json)
                val data = miCloudResponseData(response.json)
                val card = MiCloudAccountCardData(
                    mode = ACCOUNT_MODE_QUOTA,
                    entries = entries,
                    data = data,
                )
                textResult(
                    text = "",
                    details = toolResultDetails(
                        raw = response.body,
                        guidance = quotaGuidance(entries.size, text),
                        card = MiCloudCard(
                            title = text("小米云空间", "Xiaomi Cloud storage"),
                            payload = card.toJson(),
                        ),
                    ),
                )
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
