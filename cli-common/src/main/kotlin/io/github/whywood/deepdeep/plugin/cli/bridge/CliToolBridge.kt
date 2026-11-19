package io.github.whywood.deepdeep.plugin.cli.bridge

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.Cards
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallDecision
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.resultKeep
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.toolContinue
import io.github.whywood.deepdeep.plugin.cli.CliCommandLine
import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.CliText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class CliObservation<CARD : Any>(
    val summary: String,
    val text: String,
    val card: CARD? = null,
    val customType: String? = null,
    val isError: Boolean = false,
)

fun interface CliCommandParser<C : Any> {
    fun parse(rawCommand: String): C?
}

fun interface CliObservationBuilder<C : Any, CARD : Any> {
    fun build(host: PluginHost, command: C, envelope: JsonObject): CliObservation<CARD>?

    fun build(host: PluginHost, command: C, payload: JsonElement): CliObservation<CARD>? =
        build(host, command, payload as? JsonObject ?: return null)
}

fun interface CliErrorMapper<C : Any> {
    suspend fun map(host: PluginHost, request: ToolExecutionResult, command: C): ToolResultDecision?
}

class CliBridgeConfig<C : Any, CARD : Any>(
    val binaryName: String,
    val customType: String,
    val logTag: String,
    val parser: CliCommandParser<C>,
    val observer: CliObservationBuilder<C, CARD>,
    val cardEncoder: (CARD) -> JsonObject,
    val toolName: String = "bash",
    val commandArgument: String = "command",
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    val maxPending: Int = DEFAULT_MAX_PENDING,
    val envelope: CliJsonEnvelope = CliJsonEnvelope.DEFAULT,
    val errorMapper: CliErrorMapper<C>? = null,
    val payloadFinder: ((String) -> JsonElement?)? = null,
    val requireSuccessEnvelope: Boolean = true,
    val redactRaw: suspend (PluginHost, String) -> String = { _, raw -> raw },
) {
    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 256 * 1024

        const val DEFAULT_MAX_PENDING: Int = 64
    }
}

class CliToolBridge<C : Any, CARD : Any>(private val config: CliBridgeConfig<C, CARD>) {
    private class Pending<C>(val sessionId: String, val command: C)

    private class Unsupported(val sessionId: String, val suggestion: String)

    private class CliOutput(
        val text: String,
        val truncated: Boolean,
        val recovered: Boolean,
    )

    private val pending = object : LinkedHashMap<String, Pending<C>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pending<C>>?): Boolean =
            size > config.maxPending
    }

    private val handled = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > config.maxPending
    }

    private val unsupported = object : LinkedHashMap<String, Unsupported>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unsupported>?): Boolean =
            size > config.maxPending
    }

    fun hooks(host: PluginHost): PluginHooks = object : PluginHooks {
        override suspend fun beforeToolCall(request: ToolCallRequest): ToolCallDecision = try {
            record(host, request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            warn(
                host,
                "before call=${request.callId} hook-error=${error.javaClass.simpleName} " +
                    error.message?.take(120).orEmpty(),
            )
            toolContinue()
        }

        override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision = try {
            withRetryHint(request, replace(host, request))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            warn(
                host,
                "after call=${request.callId} hook-error=${error.javaClass.simpleName} " +
                    error.message?.take(120).orEmpty(),
            )
            resultKeep()
        }

        override suspend fun runFinished(event: RunFinishedEvent) {
            clearSession(host, event.sessionId)
        }

        override suspend fun sessionClosed(event: SessionClosedEvent) {
            clearSession(host, event.sessionId)
        }
    }

    fun clear() {
        synchronized(this) {
            pending.clear()
            handled.clear()
            unsupported.clear()
        }
    }

    private fun record(host: PluginHost, request: ToolCallRequest): ToolCallDecision {
        if (request.toolName != config.toolName) return toolContinue()
        val raw = (request.args[config.commandArgument] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val summary = CliCommandLine.diagnosticSummary(raw, config.binaryName)
        val command = config.parser.parse(raw)
        if (command == null) {
            retrySuggestion(raw)?.let { suggestion ->
                synchronized(this) { unsupported[request.callId] = Unsupported(request.sessionId, suggestion) }
            }
            log(host, "before call=${request.callId} match=none $summary")
            return toolContinue()
        }
        val size = synchronized(this) {
            pending[request.callId] = Pending(request.sessionId, command)
            pending.size
        }
        log(host, "before call=${request.callId} match=$command pending=$size $summary")
        return toolContinue()
    }

    private suspend fun replace(host: PluginHost, request: ToolExecutionResult): ToolResultDecision {
        if (request.toolName != config.toolName) return resultKeep()
        val invocation = synchronized(this) { pending.remove(request.callId) }
        if (invocation == null) {
            log(host, "after call=${request.callId} pending=miss (before hook not recorded)")
            return resultKeep()
        }
        log(host, "after call=${request.callId} pending=hit command=${invocation.command}")
        if (!markHandled(request.callId)) {
            log(host, "after call=${request.callId} skip=duplicate")
            return resultKeep()
        }
        val source = recoverOutput(host, request)
        if (request.result.isError) {
            log(host, "after call=${request.callId} skip=is-error")
            val mapped = config.errorMapper?.map(host, request, invocation.command)
            if (mapped != null) return withRaw(host, request, source, mapped)
            return truncationHint(request, source)
        }
        val output = source.text
        val outputBytes = output.toByteArray(Charsets.UTF_8).size
        log(host, "after call=${request.callId} output-bytes=$outputBytes recovered=${source.recovered}")
        if (output.isEmpty() || outputBytes > config.maxOutputBytes) {
            log(host, "after call=${request.callId} skip=output-size")
            return truncationHint(request, source)
        }
        val strictEnvelope = config.requireSuccessEnvelope && config.envelope.successField != null
        val observation: CliObservation<CARD>? = if (strictEnvelope) {
            val envelope = config.envelope.find(output)
            if (envelope == null) {
                warn(host, "after call=${request.callId} parse=json-miss bytes=$outputBytes")
                return truncationHint(request, source)
            }
            if (!config.envelope.isSuccess(envelope)) {
                warn(host, "after call=${request.callId} parse=not-ok")
                return truncationHint(request, source)
            }
            config.observer.build(host, invocation.command, config.envelope.stripNotice(envelope))
        } else {
            val finder = config.payloadFinder ?: config.envelope::element
            val payload = finder(output)
            if (payload == null) {
                warn(host, "after call=${request.callId} parse=payload-miss bytes=$outputBytes")
                return truncationHint(request, source)
            }
            config.observer.build(host, invocation.command, payload)
        }
        if (observation == null) {
            warn(host, "after call=${request.callId} parse=observation-miss command=${invocation.command}")
            return truncationHint(request, source)
        }
        val raw = redactedRaw(host, source)
        val cardPayload = observation.card?.let { value ->
            runCatching { config.cardEncoder(value) }.getOrNull()
        }
        val guidance = observation.text.trim().takeIf { it.isNotEmpty() }
        log(
            host,
            "after call=${request.callId} observation=ok guidance=${guidance?.length ?: 0} " +
                "card=${cardPayload != null} raw=${raw != null} error=${observation.isError}",
        )
        val details = buildJsonObject {
            if (cardPayload != null) {
                put(
                    Cards.KEY,
                    Cards.descriptor(
                        customType = observation.customType ?: config.customType,
                        title = observation.summary.takeIf { it.isNotBlank() },
                        payload = cardPayload,
                    ),
                )
            }
            raw?.let { put(Cards.RAW_KEY, JsonPrimitive(it)) }
            guidance?.let { put(Cards.GUIDANCE_KEY, JsonPrimitive(it)) }
        }.takeIf { it.isNotEmpty() }
        return resultReplace(Cards.deliver(request.callId, textResult("", details, observation.isError)))
    }

    private suspend fun withRaw(
        host: PluginHost,
        request: ToolExecutionResult,
        source: CliOutput,
        decision: ToolResultDecision,
    ): ToolResultDecision {
        val raw = redactedRaw(host, source) ?: return decision
        if (decision.action != ToolResultAction.REPLACE) return decision
        val result = decision.result ?: return decision
        val details = buildJsonObject {
            (result.details ?: JsonObject(emptyMap())).forEach { (key, value) -> put(key, value) }
            put(Cards.RAW_KEY, JsonPrimitive(raw))
        }
        return resultReplace(Cards.deliver(request.callId, textResult(result.text.orEmpty(), details, result.isError)))
    }

    private fun withRetryHint(request: ToolExecutionResult, decision: ToolResultDecision): ToolResultDecision {
        val suggestion = synchronized(this) { unsupported.remove(request.callId)?.suggestion } ?: return decision
        val base = if (decision.action == ToolResultAction.REPLACE) {
            decision.result ?: return decision
        } else {
            request.result
        }
        if (base.isError) return decision
        val hint = "如有卡片展示需要，请重新执行完整命令：$suggestion"
        val text = base.text.orEmpty().let { existing ->
            if (existing.isBlank()) hint else "$existing\n\n$hint"
        }
        return resultReplace(textResult(text, base.details, base.isError))
    }

    private fun retrySuggestion(raw: String): String? {
        if (config.parser.parse(raw) != null) return null
        val base = raw.takeWhile { it !in SHELL_BREAKS }.trim()
        if (base.isEmpty() || base == raw.trim()) return null
        val tokens = CliCommandLine.tokenize(base)
        if (tokens.isEmpty()) return null
        if (!CliCommandLine.isBinary(tokens.first(), config.binaryName)) return null
        return if (config.parser.parse(base) != null) base else null
    }

    private suspend fun redactedRaw(host: PluginHost, source: CliOutput): String? {
        val cleaned = CliText.stripHostNotices(CliText.stripAnsi(source.text)).trimEnd()
        if (cleaned.isBlank()) return null
        return runCatching { config.redactRaw(host, cleaned) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun truncationHint(request: ToolExecutionResult, source: CliOutput): ToolResultDecision {
        if (!source.truncated || source.recovered) return resultKeep()
        val text = request.result.text.orEmpty().trimEnd()
        val withHint = if (text.contains(TRUNCATION_HINT)) text else "$text\n\n$TRUNCATION_HINT"
        return resultReplace(textResult(withHint, details = request.result.details, isError = request.result.isError))
    }

    private fun recoverOutput(host: PluginHost, request: ToolExecutionResult): CliOutput {
        val text = request.result.text.orEmpty()
        val details = request.result.details as? JsonObject ?: return CliOutput(text, false, false)
        val truncated = ((details["truncation"] as? JsonObject)?.get("truncated") as? JsonPrimitive)
            ?.contentOrNull
            ?.toBoolean() == true
        if (!truncated) return CliOutput(text, false, false)
        val path = (details["fullOutputPath"] as? JsonPrimitive)?.contentOrNull
        if (path.isNullOrBlank()) return CliOutput(text, true, false)
        val file = File(path)
        if (!file.isFile || file.length() > MAX_RECOVERED_OUTPUT_BYTES) {
            log(host, "recover skip=file path=$path exists=${file.isFile} bytes=${file.length()}")
            return CliOutput(text, true, false)
        }
        val recovered = runCatching { file.readText() }.getOrNull()
        if (recovered == null) {
            warn(host, "recover read-error path=$path")
            return CliOutput(text, true, false)
        }
        log(host, "recover ok chars=${recovered.length}")
        return CliOutput(recovered, true, true)
    }

    private fun clearSession(host: PluginHost, sessionId: String) {
        val removed = synchronized(this) {
            val before = pending.size + unsupported.size
            pending.entries.removeAll { it.value.sessionId == sessionId }
            unsupported.entries.removeAll { it.value.sessionId == sessionId }
            before - pending.size - unsupported.size
        }
        log(host, "clear pending=$removed remaining=${pendingSize()}")
    }

    private fun pendingSize(): Int = synchronized(this) { pending.size }

    private fun markHandled(callId: String): Boolean = synchronized(this) {
        if (handled.containsKey(callId)) return false
        handled[callId] = true
        true
    }

    private fun log(host: PluginHost, message: String) {
        runCatching { host.logs().debug("${config.logTag} $message") }
    }

    private fun warn(host: PluginHost, message: String) {
        runCatching { host.logs().warn("${config.logTag} $message") }
    }

    private companion object {
        const val MAX_RECOVERED_OUTPUT_BYTES: Long = 4L * 1024L * 1024L
        const val SHELL_BREAKS: String = "|><;&`"
        const val TRUNCATION_HINT: String =
            "（上游输出超过单次上限已被截断，无法完整读取；请缩小范围后重试，例如 --page-size、--page-token 或更短的时间区间。）"
    }
}
