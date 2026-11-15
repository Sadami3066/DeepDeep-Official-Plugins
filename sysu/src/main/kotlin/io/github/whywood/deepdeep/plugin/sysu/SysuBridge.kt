package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.localizedText
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal const val SYSU_CARD_TYPE = "sysu.card"

private const val SYSU_BINARY = "sysu"
private const val BASH_TOOL = "bash"

private val SYSU_OBSERVER: CliObservationBuilder<SysuInvocation, CliCardModel> =
    object : CliObservationBuilder<SysuInvocation, CliCardModel> {
        override fun build(
            host: PluginHost,
            command: SysuInvocation,
            envelope: JsonObject,
        ): CliObservation<CliCardModel>? = observe(host, command, envelope)

        override fun build(
            host: PluginHost,
            command: SysuInvocation,
            payload: JsonElement,
        ): CliObservation<CliCardModel>? = observe(host, command, payload)
    }

private val SYSU_BRIDGE_CONFIG: CliBridgeConfig<SysuInvocation, CliCardModel> = CliBridgeConfig(
    binaryName = SYSU_BINARY,
    customType = SYSU_CARD_TYPE,
    logTag = "sysu.bridge",
    parser = CliCommandParser { raw -> SysuCommandParser.parse(raw) },
    observer = SYSU_OBSERVER,
    cardEncoder = { model -> CliCardCodec.encode(model) },
    envelope = CliJsonEnvelope.LENIENT,
    requireSuccessEnvelope = false,
    errorMapper = CliErrorMapper { host, request, command ->
        mapSysuError(host, request, command)
    },
)

private fun observe(
    host: PluginHost,
    command: SysuInvocation,
    payload: JsonElement,
): CliObservation<CliCardModel>? {
    val supported = command as? SysuInvocation.Supported ?: return null
    val rendered = SysuObservation.render(supported.kind, payload, host.sysuStrings()) ?: return null
    return CliObservation(
        summary = rendered.summary,
        text = rendered.text,
        card = rendered.card,
        isError = rendered.isError,
    )
}

private fun mapSysuError(
    host: PluginHost,
    request: ToolExecutionResult,
    command: SysuInvocation,
): ToolResultDecision? {
    val strings = host.sysuStrings()
    val raw = request.result.text.orEmpty()
    SysuFailures.actionable(raw, strings)?.let { message ->
        return resultReplace(textResult(message, isError = true))
    }
    SysuFailures.redactSensitive(raw, strings)?.let { safe ->
        return resultReplace(textResult(safe, isError = true))
    }
    val kind = (command as? SysuInvocation.Supported)?.kind
    if (kind == SysuCommandKind.GymSlots) {
        val payload = CliJsonEnvelope.LENIENT.element(raw)
        val message = payload?.let { SysuJson.gymSlots(it)?.error?.message }
        if (!message.isNullOrBlank()) {
            val text = SysuFailures.actionable(message, strings) ?: strings.text(
                "场馆查询失败：${SysuText.cleanInline(message)}",
                "Venue query failed: ${SysuText.cleanInline(message)}",
            )
            return resultReplace(textResult(text, isError = true))
        }
    }
    return null
}

internal fun PluginHost.sysuStrings(): SysuStrings = SysuStrings { zh, en ->
    text(localizedText(default = zh, zh = zh, en = en))
}

internal class SysuToolBridge {
    private val bridge = CliToolBridge(SYSU_BRIDGE_CONFIG)

    fun hooks(host: PluginHost): PluginHooks {
        val base = bridge.hooks(host)
        return object : PluginHooks by base {
            override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision {
                val decision = base.afterToolExecution(request)
                if (decision.action != ToolResultAction.KEEP) return decision
                if (request.toolName != BASH_TOOL) return decision
                val strings = host.sysuStrings()
                val raw = request.result.text.orEmpty()
                SysuFailures.redactSensitive(raw, strings)?.let { safe ->
                    return resultReplace(textResult(safe, isError = request.result.isError))
                }
                SysuFailures.legacyHint(raw, strings)?.let { hint ->
                    return resultReplace(textResult(hint, isError = request.result.isError))
                }
                return decision
            }
        }
    }
}

internal object SysuFailures {
    private val CODE_MARKERS = listOf("验证码", "send-code", "--code", "mfa")
    private val LOGIN_MARKERS = listOf(
        "tgc",
        "会话失效",
        "会话已失效",
        "自动续期失败",
        "重新登",
        "未登录",
        "没有会话文件",
        "登录信息",
        "登陆信息",
        "登录状态",
        "cas 要求",
        "token 失效",
        "已失效（cas",
        "已失效(cas",
        "login --code",
    )
    private val SENSITIVE = Regex(
        "(?i)(tgc|set-cookie|jsessionid|session\\.json|mfa\\.json|cookie\\s*[:=]|ticket\\s*=|password\\s*=)",
    )

    fun actionable(text: String?, strings: SysuStrings): String? {
        val source = text?.takeIf { it.isNotBlank() } ?: return null
        val lower = source.lowercase()
        if (CODE_MARKERS.none { lower.contains(it) } && LOGIN_MARKERS.none { lower.contains(it) }) return null
        if (CODE_MARKERS.any { lower.contains(it) } && mentionsVerificationCode(lower)) {
            return strings.text(
                "登录需要企业微信验证码：请在「中山大学 CLI」插件页点「登录」，输入页面提示的验证码后重试。",
                "A WeCom verification code is required: open the SYSU CLI plugin page, tap Sign in, enter the code shown there, then retry.",
            )
        }
        return strings.text(
            "登录已失效：请在「中山大学 CLI」插件页重新登录后重试。",
            "Sign-in expired: open the SYSU CLI plugin page, sign in again, then retry.",
        )
    }

    fun legacyHint(text: String, strings: SysuStrings): String? {
        if (!text.contains("undefined")) return null
        if (!text.contains("名下没有网络服务") && !text.contains("·  ·")) return null
        return strings.text(
            "网费服务数据读取不完整：请用 `sysu netpay --json` 重新查询；如果仍未登录，请在「中山大学 CLI」插件页重新登录。",
            "Network service data was incomplete. Re-query with `sysu netpay --json`; if it is still signed out, sign in again from the SYSU CLI plugin page.",
        )
    }

    fun redactSensitive(text: String, strings: SysuStrings): String? {
        if (!SENSITIVE.containsMatchIn(text)) return null
        val kept = text.lines().filterNot { SENSITIVE.containsMatchIn(it) }
        val note = strings.text(
            "（输出包含登录票据或会话信息，已省略。）",
            "(Output contained sign-in or session data and was omitted.)",
        )
        val body = kept.joinToString("\n").trim()
        return if (body.isEmpty()) note else "$note\n\n$body"
    }

    private fun mentionsVerificationCode(lower: String): Boolean =
        lower.contains("验证码") || lower.contains("--code") || lower.contains("send-code") || lower.contains("mfa")
}
