package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.CliText
import io.github.whywood.deepdeep.plugin.cli.bridge.CliBridgeConfig
import io.github.whywood.deepdeep.plugin.cli.bridge.CliCommandParser
import io.github.whywood.deepdeep.plugin.cli.bridge.CliErrorMapper
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservation
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservationBuilder
import io.github.whywood.deepdeep.plugin.cli.bridge.CliToolBridge
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val UNAUTHORIZED_ADVICE = "请打开「企业微信」插件页，扫码连接后重试。"

private val WECOM_BRIDGE_CONFIG: CliBridgeConfig<WecomCommand, CliCardModel> = CliBridgeConfig(
    binaryName = WecomCommands.FILE_NAME,
    customType = WecomCommandKind.CHAT_GROUPS.customType,
    logTag = "wecom.bridge",
    parser = CliCommandParser { raw -> WecomCommands.parse(raw) },
    observer = CliObservationBuilder { host, command, envelope ->
        buildWecomObservation(host, command, envelope)
    },
    cardEncoder = CliCardCodec::encode,
    envelope = CliJsonEnvelope.LENIENT,
    payloadFinder = { raw -> WecomCliOutputParser.payloadElement(raw) },
    errorMapper = CliErrorMapper { _, request, _ -> mapWecomError(request) },
    maxOutputBytes = WecomCliOutputParser.MAX_OUTPUT_LENGTH,
)

internal class WecomToolBridge {
    private val bridge = CliToolBridge(WECOM_BRIDGE_CONFIG)

    fun hooks(host: PluginHost): PluginHooks {
        val base = bridge.hooks(host)
        return object : PluginHooks by base {
            override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision {
                val prepared = inlineContentFile(host, request)
                val decision = base.afterToolExecution(prepared)
                return if (prepared !== request) restoreRaw(decision, request, prepared) else decision
            }
        }
    }

    fun clear() {
        bridge.clear()
    }
}

private fun buildWecomObservation(
    host: PluginHost,
    command: WecomCommand,
    envelope: JsonObject,
): CliObservation<CliCardModel>? {
    val payload = WecomCliOutputParser.payload(envelope) ?: return null
    val parsed = WecomParsedParser.parse(command.kind, payload) ?: return null
    val guidance = WecomObservation.build(command, parsed).takeIf { it.isNotBlank() } ?: return null
    return CliObservation(
        summary = WecomObservation.summary(command, parsed),
        text = guidance,
        card = WecomChatCards.build(host, command, parsed),
        customType = command.kind.customType,
    )
}

private suspend fun mapWecomError(request: ToolExecutionResult): ToolResultDecision? {
    val failure = WecomCliOutputParser.parse(request.result.text.orEmpty()) as? WecomCliOutput.Failure
        ?: return null
    if (!failure.authRequired) return null
    return resultReplace(
        textResult(
            "${failure.message}\n\n$UNAUTHORIZED_ADVICE",
            request.result.details,
            isError = true,
        ),
    )
}

private fun restoreRaw(
    decision: ToolResultDecision,
    request: ToolExecutionResult,
    prepared: ToolExecutionResult,
): ToolResultDecision {
    val result = decision.result ?: return decision
    val text = result.text ?: return decision
    val originalRaw = CliText.stripHostNotices(CliText.stripAnsi(request.result.text.orEmpty())).trimEnd()
    val enrichedRaw = CliText.stripHostNotices(CliText.stripAnsi(prepared.result.text.orEmpty())).trimEnd()
    if (originalRaw.isBlank() || enrichedRaw.isBlank() || !text.contains(enrichedRaw)) return decision
    return resultReplace(textResult(text.replace(enrichedRaw, originalRaw), result.details, result.isError))
}

private suspend fun inlineContentFile(host: PluginHost, request: ToolExecutionResult): ToolExecutionResult {
    if (request.result.isError) return request
    val output = request.result.text.orEmpty()
    if (!output.contains("file_path")) return request
    val success = WecomCliOutputParser.parse(output) as? WecomCliOutput.Success ?: return request
    val payload = success.payload
    if (payload.stringValue("content") != null) return request
    val path = payload.stringValue("file_path") ?: return request
    val content = readContent(host, path) ?: return request
    val enriched = JsonObject(payload + ("content" to JsonPrimitive(content)))
    val marker = WecomCliOutputParser.exitMarker(output)
    val text = if (marker != null) "${enriched}\n\n$marker" else enriched.toString()
    return request.copy(result = textResult(text, details = request.result.details))
}

private suspend fun readContent(host: PluginHost, path: String): String? {
    val target = path.trim()
    if (target.isEmpty() || target.any { it == '\u0000' }) return null
    val viaLinux = runCatching {
        val linux = host.linux()
        if (!linux.available) return@runCatching null
        linux.exec("cat -- ${shellQuote(target)}")
    }.getOrNull()
    if (viaLinux != null && viaLinux.exitCode == 0 && viaLinux.stdout.isNotBlank()) return viaLinux.stdout
    return runCatching { host.files().readText(target) }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
