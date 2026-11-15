package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.bridge.CliBridgeConfig
import io.github.whywood.deepdeep.plugin.cli.bridge.CliCommandParser
import io.github.whywood.deepdeep.plugin.cli.bridge.CliErrorMapper
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservation
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservationBuilder
import io.github.whywood.deepdeep.plugin.cli.bridge.CliToolBridge
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import kotlinx.serialization.json.JsonObject

internal const val UNAUTHORIZED_OBSERVATION =
    "企业微信未授权或登录已过期：请打开「企业微信」插件页，扫码连接后重试。"

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
)

internal class WecomToolBridge {
    private val bridge = CliToolBridge(WECOM_BRIDGE_CONFIG)

    fun hooks(host: PluginHost): PluginHooks = bridge.hooks(host)

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
    val text = WecomObservation.build(command, parsed).takeIf { it.isNotBlank() } ?: return null
    return CliObservation(
        summary = text.lineSequence().firstOrNull().orEmpty().take(120),
        text = text,
        card = WecomChatCards.build(host, command, parsed),
        customType = command.kind.customType,
    )
}

private suspend fun mapWecomError(request: ToolExecutionResult): ToolResultDecision? {
    val failure = WecomCliOutputParser.parse(request.result.text.orEmpty()) as? WecomCliOutput.Failure
        ?: return null
    if (!failure.authRequired) return null
    return resultReplace(
        textResult(UNAUTHORIZED_OBSERVATION, request.result.details, isError = true),
    )
}
