package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.serialization.json.JsonObject

internal fun smsTool(client: MiCloudReadApi, text: MiCloudText): PluginTool = pluginTool(
    name = "mi_cloud_sms",
    label = "mi_cloud_sms",
    description = "List recent SMS threads (optionally with call records) from Xiaomi Cloud. Read-only.",
    parameters = smsParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult {
        val args = call.args
        val userId = requiredUserId(client)
        val limit = limitArg(args)
        val response = client.getJson(
            smsThreadPath(
                userId = userId,
                ts = now(),
                limit = limit,
                withPhoneCall = args.argBool("withPhoneCall") ?: true,
                syncTag = args.argString("syncTag") ?: "0",
                syncThreadTag = args.argString("syncThreadTag") ?: "0",
            ),
        )
        val payload = response.json.payloadObject()
        val entries = response.json.payloadArray("entries")
            .mapNotNull { it as? JsonObject }
        val watermark = cursorValue(payload.stringOrNull("watermark"))
        val nextSyncTag = cursorValue(payload.stringOrNull("syncTag")).ifBlank { watermark }
        val nextSyncThreadTag = cursorValue(payload.stringOrNull("syncThreadTag"))
        textResult(
            smsObservation(entries, nextSyncTag, nextSyncThreadTag, watermark, text),
            details = rawResultDetails(response.body),
        )
    }
}

internal fun smsThreadPath(
    userId: String,
    ts: Long,
    limit: Int,
    withPhoneCall: Boolean,
    syncTag: String,
    syncThreadTag: String,
): String = "/sms/${encodeComponent(userId)}/full/thread" +
    "?limit=$limit&readMode=older&withPhoneCall=$withPhoneCall&_dc=$ts" +
    "&syncTag=${encodeComponent(syncTag)}&syncThreadTag=${encodeComponent(syncThreadTag)}"

private val smsParameters: JsonObject = toolParams {
    put("limit", propInt("Number of threads to return", minimum = 1, maximum = MAX_LIST_LIMIT, default = DEFAULT_LIST_LIMIT))
    put("withPhoneCall", propBool("Include call records in the response (default true)", default = true))
    put("syncTag", propString("Sync tag returned by a previous call (default 0)"))
    put("syncThreadTag", propString("Thread sync tag returned by a previous call (default 0)"))
}
