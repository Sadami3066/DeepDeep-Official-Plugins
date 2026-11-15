package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallDecision
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.resultKeep
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.toolContinue
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface SysuDecision {
    data object Keep : SysuDecision

    data class Replace(
        val text: String,
        val isError: Boolean,
        val card: SysuCardPayload?,
    ) : SysuDecision
}

internal object SysuBridgePlanner {
    fun plan(
        invocation: SysuInvocation,
        result: PluginToolResult,
        strings: SysuStrings,
    ): SysuDecision {
        val raw = result.text.orEmpty()
        if (result.isError) {
            SysuFailures.actionable(raw, strings)?.let { message ->
                return SysuDecision.Replace(message, true, card = null)
            }
            return SysuFailures.redactSensitive(raw, strings)?.let { safe ->
                SysuDecision.Replace(safe, false, card = null)
            } ?: SysuDecision.Keep
        }
        val supported = invocation as? SysuInvocation.Supported
        if (supported != null) {
            val rendered = SysuObservation.render(supported.kind, raw, strings)
            if (rendered != null) {
                return SysuDecision.Replace(rendered.observation, rendered.isError, rendered.card)
            }
        }
        SysuFailures.legacyHint(invocation.commandLine, raw, strings)?.let { hint ->
            return SysuDecision.Replace(hint, false, card = null)
        }
        return SysuFailures.redactSensitive(raw, strings)?.let { safe ->
            SysuDecision.Replace(safe, false, card = null)
        } ?: SysuDecision.Keep
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

    fun legacyHint(commandLine: String, text: String, strings: SysuStrings): String? {
        if (!commandLine.contains("netpay")) return null
        if (!text.contains("undefined") && !text.contains("·  ·")) return null
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

internal class SysuBridge(
    private val host: PluginHost,
    maxEntries: Int = SysuInvocationRegistry.MAX_ENTRIES,
) {
    private val registry = SysuInvocationRegistry(maxEntries)
    private val strings = SysuStrings { zh, en ->
        host.text(localizedText(default = zh, zh = zh, en = en))
    }

    val hooks: PluginHooks = object : PluginHooks {
        override suspend fun beforeToolCall(request: ToolCallRequest): ToolCallDecision {
            runCatching {
                if (request.toolName != BASH_TOOL) return@runCatching
                val command = (request.args["command"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching
                val invocation = SysuCommandParser.parse(command) ?: return@runCatching
                registry.record(request.sessionId, request.callId, invocation)
            }
            return toolContinue()
        }

        override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision {
            if (request.toolName != BASH_TOOL) return resultKeep()
            val invocation = registry.take(request.sessionId, request.callId) ?: return resultKeep()
            val decision = runCatching { SysuBridgePlanner.plan(invocation, request.result, strings) }
                .getOrDefault(SysuDecision.Keep)
            return when (decision) {
                SysuDecision.Keep -> resultKeep()
                is SysuDecision.Replace -> {
                    decision.card?.let { payload -> postCard(request.sessionId, payload) }
                    resultReplace(textResult(decision.text, isError = decision.isError))
                }
            }
        }

        override suspend fun runFinished(event: RunFinishedEvent) {
            registry.clearSession(event.sessionId)
        }

        override suspend fun sessionClosed(event: SessionClosedEvent) {
            registry.clearSession(event.sessionId)
        }
    }

    private suspend fun postCard(sessionId: String, payload: SysuCardPayload) {
        if (sessionId.isBlank()) return
        try {
            withTimeoutOrNull(CARD_POST_TIMEOUT_MS) {
                host.chat().postCustomMessage(
                    sessionId = sessionId,
                    customType = SYSU_CARD_TYPE,
                    payload = SysuCards.encode(SysuCards.normalized(payload)),
                    text = SysuCards.summaryText(payload),
                    display = true,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val BASH_TOOL = "bash"
        const val CARD_POST_TIMEOUT_MS = 900L
    }
}
