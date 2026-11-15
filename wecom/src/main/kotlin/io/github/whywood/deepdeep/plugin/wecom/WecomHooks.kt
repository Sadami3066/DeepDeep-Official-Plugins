package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.TextBlock
import io.github.whywood.deepdeep.plugin.api.ToolCallDecision
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.resultKeep
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.toolContinue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val BASH_TOOL_NAME = "bash"
internal const val MAX_TRACKED_CALLS = 64

internal const val UNAUTHORIZED_OBSERVATION =
    "企业微信未授权或登录已过期：请打开「企业微信」插件页，扫码连接后重试。"

internal fun interface WecomCardPoster {
    suspend fun post(sessionId: String, kind: WecomCommandKind, payload: WecomCardPayload, observation: String)
}

internal class WecomCallTracker(private val maxSize: Int = MAX_TRACKED_CALLS) {
    private val entries = object : LinkedHashMap<String, TrackedCall>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackedCall>?): Boolean =
            size > maxSize
    }

    data class TrackedCall(val sessionId: String, val command: WecomCommand)

    @Synchronized
    fun remember(callId: String, sessionId: String, command: WecomCommand) {
        entries[callId] = TrackedCall(sessionId, command)
    }

    @Synchronized
    fun take(callId: String): TrackedCall? = entries.remove(callId)

    @Synchronized
    fun clearSession(sessionId: String) {
        entries.entries.removeAll { it.value.sessionId == sessionId }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}

internal class WecomHooks(
    private val tracker: WecomCallTracker,
    private val poster: WecomCardPoster,
) : PluginHooks {
    override suspend fun beforeToolCall(request: ToolCallRequest): ToolCallDecision {
        return runCatching {
            if (request.toolName != BASH_TOOL_NAME) return toolContinue()
            val command = (request.args["command"] as? JsonPrimitive)?.contentOrNull ?: return toolContinue()
            val parsed = WecomCommands.parse(command) ?: return toolContinue()
            tracker.remember(request.callId, request.sessionId, parsed)
            toolContinue()
        }.getOrElse { toolContinue() }
    }

    override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision {
        val tracked = runCatching { tracker.take(request.callId) }.getOrNull() ?: return resultKeep()
        if (tracked.sessionId != request.sessionId) return resultKeep()
        return runCatching { handle(request, tracked.command) }.getOrElse { resultKeep() }
    }

    override suspend fun runFinished(event: RunFinishedEvent) {
        runCatching { tracker.clearSession(event.sessionId) }
    }

    override suspend fun sessionClosed(event: SessionClosedEvent) {
        runCatching { tracker.clearSession(event.sessionId) }
    }

    private suspend fun handle(request: ToolExecutionResult, command: WecomCommand): ToolResultDecision {
        val result = request.result
        val output = result.text ?: result.blocks.filterIsInstance<TextBlock>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotBlank() }
            ?: return resultKeep()
        when (val parsedOutput = WecomCliOutputParser.parse(output) ?: return resultKeep()) {
            is WecomCliOutput.Failure -> {
                if (!parsedOutput.authRequired) return resultKeep()
                return resultReplace(textResult(UNAUTHORIZED_OBSERVATION, result.details, isError = true))
            }
            is WecomCliOutput.Success -> {
                val parsed = WecomParsedParser.parse(command.kind, parsedOutput.payload) ?: return resultKeep()
                val observation = WecomObservation.build(command, parsed).takeIf { it.isNotBlank() }
                    ?: return resultKeep()
                val cardPayload = WecomCardPayloads.build(command, parsed)
                runCatching { poster.post(request.sessionId, command.kind, cardPayload, observation) }
                return resultReplace(textResult(observation, result.details, isError = false))
            }
        }
    }
}
