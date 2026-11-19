package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.PluginTool
import io.github.whywood.superpi.plugin.api.pluginTool
import io.github.whywood.superpi.plugin.api.textResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal fun smsTool(client: MiCloudReadApi, text: MiCloudText): PluginTool = pluginTool(
    name = "mi_cloud_sms",
    label = "mi_cloud_sms",
    description = "List recent SMS threads (optionally with call records) from Xiaomi Cloud. Read-only.",
    parameters = smsParameters,
) { call ->
    call.signal?.throwIfCancelled()
    miCloudToolResult(MiCloudFeature.SMS, text) {
        val args = call.args
        val userId = requiredUserId(client)
        val limit = limitArg(args)
        val withPhoneCall = args.argBool("withPhoneCall") ?: true
        val response = client.getJson(
            smsThreadPath(
                userId = userId,
                ts = now(),
                limit = limit,
                withPhoneCall = withPhoneCall,
                syncTag = args.argString("syncTag") ?: "0",
                syncThreadTag = args.argString("syncThreadTag") ?: "0",
            ),
        )
        val payload = response.json.payloadObject()
        val entries = smsItems(response.json.payloadArray("entries"))
        val watermark = payload.objectOrNull("watermark")
        val callEntries = payload.objectOrNull("phonecall_view")?.arrayOrNull("entries")
            ?: JsonArray(emptyList())
        val calls = callItems(callEntries)
        val nextSyncTag = cursorValue(payload.stringOrNull("syncTag"))
            .ifBlank { watermark.stringOrNull("syncTag").orEmpty() }
            .ifBlank { cursorValue(payload?.get("watermark")) }
        val nextSyncThreadTag = cursorValue(payload.stringOrNull("syncThreadTag"))
            .ifBlank { watermark.stringOrNull("syncThreadTag").orEmpty() }
        val card = MiCloudSmsCardData(
            entries = entries,
            calls = calls,
            nextSyncTag = nextSyncTag,
            nextSyncThreadTag = nextSyncThreadTag,
            watermark = watermark,
            withPhoneCall = withPhoneCall,
            limit = limit,
        )
        textResult(
            text = "",
            details = toolResultDetails(
                raw = response.body,
                guidance = smsListGuidance(entries.size, nextSyncTag, nextSyncThreadTag, text),
                card = MiCloudCard(
                    title = text.fmt("短信与通话（%d 条）", "SMS and calls (%d)", entries.size),
                    payload = card.toJson(),
                ),
            ),
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
