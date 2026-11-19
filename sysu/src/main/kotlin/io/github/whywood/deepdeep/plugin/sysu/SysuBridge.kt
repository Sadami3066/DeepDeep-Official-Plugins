package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallDecision
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.localizedText
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
    redactRaw = { host, raw -> SysuFailures.redactSensitive(raw, host.sysuStrings()) ?: raw },
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

internal fun SysuInvocation.site(): SysuSite? = when (this) {
    is SysuInvocation.Supported -> kind.site
    is SysuInvocation.Other -> SysuCommandParser.site(commandLine)
}

private fun mapSysuError(
    host: PluginHost,
    request: ToolExecutionResult,
    command: SysuInvocation,
): ToolResultDecision? {
    val strings = host.sysuStrings()
    val raw = request.result.text.orEmpty()
    val site = command.site()
    val payload = CliJsonEnvelope.LENIENT.element(raw)
    val errorMessage = ((payload as? JsonObject)?.get("error") as? JsonObject)?.get("message").scalarText()
    val kind = (command as? SysuInvocation.Supported)?.kind
    if (kind == SysuCommandKind.GymSlots) {
        val message = payload?.let { SysuJson.gymSlots(it)?.error?.message }
        if (!message.isNullOrBlank()) {
            val text = SysuFailures.actionable(message, SysuSite.Gym, strings) ?: strings.text(
                "场馆查询失败：${SysuText.cleanInline(message)}",
                "Venue query failed: ${SysuText.cleanInline(message)}",
            )
            return resultReplace(textResult(text, isError = true))
        }
    }
    val source = errorMessage ?: raw
    SysuFailures.actionable(source, site, strings)?.let { message ->
        return resultReplace(textResult(message, isError = true))
    }
    if (!errorMessage.isNullOrBlank()) {
        val safe = SysuFailures.redactSensitive(errorMessage, strings) ?: errorMessage
        return resultReplace(textResult(safe, isError = true))
    }
    SysuFailures.redactSensitive(raw, strings)?.let { safe ->
        return resultReplace(textResult(safe, isError = true))
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
                if (request.toolName != BASH_TOOL) return decision
                if (decision.action == ToolResultAction.REPLACE) return decision
                val text = request.result.text ?: return decision
                val strings = host.sysuStrings()
                val safe = SysuFailures.redactSensitive(text, strings)
                val hint = if (safe == null) SysuFailures.legacyHint(text, strings) else null
                if (safe == null && hint == null) return decision
                return resultReplace(textResult(safe ?: hint ?: text, details = request.result.details, isError = request.result.isError))
            }
        }
    }

    fun clear() {
        bridge.clear()
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
        "返回登录页",
    )
    private val SENSITIVE = Regex(
        "(?i)(tgc|set-cookie|jsessionid|session\\.json|mfa\\.json|cookie\\s*[:=]|ticket\\s*=|password\\s*=)",
    )
    private val SENSITIVE_VALUE = Regex(
        "(?i)\\b(tgc|ticket|jsessionid|cookie|session)\\s*[=:]\\s*[^\\s;，。]+",
    )
    private val SENSITIVE_WORD = Regex("(?i)\\btgc\\b")
    private val SESSION_FILE = Regex("(?i)(?:jwxt/)?(?:session|mfa)\\.json|lms\\.json|gym\\.json|netpay\\.json")
    private val EXIT_MARKER = Regex("""\n*\s*\[exit:\s*\d+]""")

    fun actionable(text: String?, site: SysuSite?, strings: SysuStrings): String? {
        val source = text?.takeIf { it.isNotBlank() } ?: return null
        val lower = source.lowercase()
        val code = CODE_MARKERS.any { lower.contains(it) } && mentionsVerificationCode(lower)
        val login = LOGIN_MARKERS.any { lower.contains(it) }
        if (!code && !login) return null
        val original = sanitizeSensitive(source, strings)
        val command = site?.loginCommand() ?: "sysu login"
        val advice = if (code) {
            strings.text(
                "需要企业微信验证码：打开「中山大学 CLI」插件页点「登录」，在提示处输入验证码；也可以在拿到验证码后执行 `$command --code <4 位>`。",
                "A WeCom verification code is required: open the SYSU CLI plugin page, tap Sign in, and enter the code. Or run `$command --code <4 digits>` after receiving it.",
            )
        } else {
            strings.text(
                "先在对话里执行 `$command` 重试（会复用统一认证）；仍失败再打开「中山大学 CLI」插件页重新登录。",
                "Run `$command` first to retry with the existing CAS session; if it still fails, sign in again from the SYSU CLI plugin page.",
            )
        }
        return listOf(original, advice).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun legacyHint(text: String, strings: SysuStrings): String? {
        if (!text.contains("undefined")) return null
        if (!text.contains("名下没有网络服务") && !text.contains("·  ·")) return null
        return strings.text(
            "网费服务数据读取不完整：请用 `sysu netpay` 重新查询；如果仍未登录，先执行 `sysu netpay login`，需要验证码再到「中山大学 CLI」插件页。",
            "Network service data was incomplete. Re-query with `sysu netpay`; if it is still signed out, run `sysu netpay login` and use the SYSU CLI plugin page if a code is required.",
        )
    }

    fun redactSensitive(text: String, strings: SysuStrings): String? {
        if (!SENSITIVE.containsMatchIn(text)) return null
        val note = strings.text(
            "（输出中的登录票据、Cookie 与会话信息已隐去）",
            "(Sign-in tickets, cookies and session data were removed.)",
        )
        val trimmed = text.trimStart()
        val body = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            sanitizeSensitive(text, strings)
        } else {
            text.lines().filterNot { SENSITIVE.containsMatchIn(it) }.joinToString("\n").trim()
        }
        return if (body.isEmpty()) note else "$note\n\n$body"
    }

    private fun sanitizeSensitive(text: String, strings: SysuStrings): String {
        val masked = strings.text("<已隐去>", "<removed>")
        val cleaned = EXIT_MARKER.replace(text, "").trim()
        return cleaned
            .replace(SENSITIVE_VALUE) { match -> "${match.groupValues[1]}=$masked" }
            .replace(SENSITIVE_WORD, strings.text("统一认证票据", "CAS ticket"))
            .replace(SESSION_FILE, strings.text("会话文件", "session file"))
            .trim()
    }

    private fun mentionsVerificationCode(lower: String): Boolean =
        lower.contains("验证码") || lower.contains("--code") || lower.contains("send-code") || lower.contains("mfa")
}
