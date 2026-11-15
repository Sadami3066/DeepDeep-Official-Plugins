package io.github.whywood.deepdeep.plugin.cli.bridge

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallDecision
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.resultKeep
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.toolContinue
import io.github.whywood.deepdeep.plugin.cli.CliCommandLine
import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
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
) {
    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 256 * 1024

        const val DEFAULT_MAX_PENDING: Int = 64
    }
}

class CliToolBridge<C : Any, CARD : Any>(private val config: CliBridgeConfig<C, CARD>) {
    private class Pending<C>(val sessionId: String, val command: C)

    private val pending = object : LinkedHashMap<String, Pending<C>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pending<C>>?): Boolean =
            size > config.maxPending
    }

    private val handled = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
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
            replace(host, request)
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
        }
    }

    private fun record(host: PluginHost, request: ToolCallRequest): ToolCallDecision {
        if (request.toolName != config.toolName) return toolContinue()
        val raw = (request.args[config.commandArgument] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val summary = CliCommandLine.diagnosticSummary(raw, config.binaryName)
        val command = config.parser.parse(raw)
        if (command == null) {
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
        if (request.result.isError) {
            log(host, "after call=${request.callId} skip=is-error")
            return config.errorMapper?.map(host, request, invocation.command) ?: resultKeep()
        }
        val output = request.result.text.orEmpty()
        val outputBytes = output.toByteArray(Charsets.UTF_8).size
        log(host, "after call=${request.callId} output-bytes=$outputBytes")
        if (output.isEmpty() || outputBytes > config.maxOutputBytes) {
            log(host, "after call=${request.callId} skip=output-size")
            return resultKeep()
        }
        val strictEnvelope = config.requireSuccessEnvelope && config.envelope.successField != null
        val observation: CliObservation<CARD>? = if (strictEnvelope) {
            val envelope = config.envelope.find(output)
            if (envelope == null) {
                warn(host, "after call=${request.callId} parse=json-miss bytes=$outputBytes")
                return resultKeep()
            }
            if (!config.envelope.isSuccess(envelope)) {
                warn(host, "after call=${request.callId} parse=not-ok")
                return resultKeep()
            }
            config.observer.build(host, invocation.command, config.envelope.stripNotice(envelope))
        } else {
            val finder = config.payloadFinder ?: config.envelope::element
            val payload = finder(output)
            if (payload == null) {
                warn(host, "after call=${request.callId} parse=payload-miss bytes=$outputBytes")
                return resultKeep()
            }
            config.observer.build(host, invocation.command, payload)
        }
        if (observation == null) {
            warn(host, "after call=${request.callId} parse=observation-miss command=${invocation.command}")
            return resultKeep()
        }
        val cardPayload = observation.card?.let { card ->
            runCatching { config.cardEncoder(card) }.getOrNull()
        }
        val details = cardPayload?.let { payload ->
            buildJsonObject {
                put(
                    CARD_DETAILS_KEY,
                    buildJsonObject {
                        put("customType", observation.customType ?: config.customType)
                        observation.summary.takeIf { it.isNotBlank() }?.let { put("title", it) }
                        put("payload", payload)
                    },
                )
            }
        }
        val withHint = cardPayload != null && !observation.isError
        log(
            host,
            "after call=${request.callId} observation=ok chars=${observation.text.length} " +
                "card=${cardPayload != null} hint=$withHint error=${observation.isError}",
        )
        val text = if (withHint) {
            val base = observation.text.trimEnd()
            if (base.isEmpty()) "$CARD_ID_LABEL${request.callId}" else "$base\n\n$CARD_ID_LABEL${request.callId}"
        } else {
            observation.text
        }
        return resultReplace(textResult(text, details = details, isError = observation.isError))
    }

    private companion object {
        const val CARD_DETAILS_KEY: String = "deepdeep.card"
        const val CARD_ID_LABEL: String = "卡片 id："
    }

    private fun clearSession(host: PluginHost, sessionId: String) {
        val removed = synchronized(this) {
            val before = pending.size
            pending.entries.removeAll { it.value.sessionId == sessionId }
            before - pending.size
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
}
