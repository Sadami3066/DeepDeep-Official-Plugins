package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.PluginHost
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal enum class FeishuCommandKind {
    CHAT_LIST,
    CHAT_MESSAGES,
    CALENDAR_AGENDA,
    DRIVE_SEARCH,
    DOCS_FETCH,
    TASKS,
}

internal data class FeishuCommand(
    val kind: FeishuCommandKind,
    val chatId: String? = null,
    val userId: String? = null,
    val start: String? = null,
    val end: String? = null,
    val doc: String? = null,
    val docFormat: String? = null,
    val query: String? = null,
    val calendarId: String? = null,
)

internal data class FeishuObservation(
    val summary: String,
    val text: String,
    val card: FeishuCardModel,
    val openUrl: String? = null,
)

internal object FeishuCliOutput {
    const val MAX_OUTPUT_BYTES = 256 * 1024
    private const val MAX_OBSERVED_ITEMS = MAX_CARD_ROWS
    private const val MAX_FACT_CHARS = 240
    private const val MAX_LINE_CHARS = 420

    private val ANSI_ESCAPE = Regex(
        """[\u001B\u009B](?:\[[0-9;?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001B\\)?)""",
    )
    private val DOTENV_LINE = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*$""")
    private val HTML_TAG = Regex("""<[^>]{0,400}>""")
    private val BLOCK_BREAK = Regex("""</(?:p|h[1-6]|li|tr|div|blockquote)>""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    fun findEnvelope(output: String): JsonObject? {
        val cleaned = stripAnsi(output)
        if (cleaned.isBlank()) return null
        val trimmed = buildString {
            cleaned.lineSequence().forEach { line ->
                if (DOTENV_LINE.matches(line.trim())) return@forEach
                appendLine(line)
            }
        }.trim()
        var start = trimmed.indexOf('{')
        while (start >= 0) {
            val end = matchBrace(trimmed, start)
            if (end > start) {
                val candidate = trimmed.substring(start, end + 1)
                runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull()?.let { root ->
                    val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
                    if (ok != null) return root
                }
            }
            start = trimmed.indexOf('{', start + 1)
        }
        return null
    }

    fun stripNotice(envelope: JsonObject): JsonObject =
        if (envelope["_notice"] == null) envelope else JsonObject(envelope.filterKeys { it != "_notice" })

    fun isSuccess(envelope: JsonObject): Boolean =
        (envelope["ok"] as? JsonPrimitive)?.booleanOrNull == true

    fun sanitizeText(raw: String): String {
        var text = stripAnsi(raw).replace(BLOCK_BREAK, "\n")
        text = text.replace(HTML_TAG, " ")
        text = decodeEntities(text)
        text = text.replace(WHITESPACE, " ").trim()
        return text
    }

    fun clip(text: String, max: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= max) trimmed else trimmed.take(max).trimEnd() + "…"
    }

    fun oneLine(text: String, max: Int = MAX_FACT_CHARS): String = clip(sanitizeText(text), max)

    fun multiLine(text: String, max: Int = MAX_LINE_CHARS): String = clip(text, max)

    fun shortTime(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching {
            OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrElse {
            if (value.length >= 16 && value[4] == '-' && value[7] == '-') value.take(16).substring(5) else value
        }
    }

    fun fullTime(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching {
            OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }.getOrElse { value }
    }

    fun cleanDocContent(raw: String): String {
        val withBreaks = raw.replace(BLOCK_BREAK, "\n")
        val text = withBreaks.replace(HTML_TAG, " ")
        return decodeEntities(text)
            .lineSequence()
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun feishuDocUrl(doc: String?): String? {
        val value = doc?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return "https://feishu.cn/docx/$value"
    }

    fun chatAppLink(chatId: String?): String? =
        chatId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "https://applink.feishu.cn/client/chat/open?openChatId=$it"
        }

    private fun stripAnsi(text: String): String = text.replace(ANSI_ESCAPE, "")

    private fun decodeEntities(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '&') {
                val end = text.indexOf(';', index + 1)
                if (end in (index + 2)..(index + 12)) {
                    val entity = text.substring(index + 1, end)
                    val decoded = when (entity.lowercase()) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos", "#39" -> "'"
                        "nbsp" -> " "
                        else -> null
                    }
                    if (decoded != null) {
                        builder.append(decoded)
                        index = end + 1
                        continue
                    }
                }
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    private fun matchBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return -1
    }
}

internal fun parseLarkCliCommand(raw: String): FeishuCommand? {
    val command = raw.trim()
    if (command.isEmpty()) return null
    if (command.any { it == '\n' || it == '\r' || it == '|' || it == ';' || it == '`' || it == '>' || it == '<' }) {
        return null
    }
    if (command.contains("&&") || command.contains("||") || command.contains("$(")) return null
    val tokens = tokenize(command)
    if (tokens.isEmpty()) return null
    var index = 0
    while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
    if (index >= tokens.size) return null
    val binary = tokens[index]
    if (!(binary == FeishuCli.BINARY || binary.endsWith("/${FeishuCli.BINARY}"))) return null
    val args = tokens.drop(index + 1)
    if (args.size < 2) return null
    val domain = args[0]
    val shortcut = args[1]
    val flags = readFlags(args.drop(2))
    if (flags.containsKey("--dry-run")) return null
    flags["--format"]?.let { format -> if (!format.equals("json", ignoreCase = true)) return null }
    if (flags.containsKey("--jq") || flags.containsKey("-q") || flags.containsKey("--concise")) return null
    return when {
        domain == "im" && shortcut == "+chat-list" -> FeishuCommand(FeishuCommandKind.CHAT_LIST)
        domain == "im" && shortcut == "+chat-messages-list" -> {
            val chatId = flags["--chat-id"]
            val userId = flags["--user-id"]
            if (chatId.isNullOrBlank() && userId.isNullOrBlank()) return null
            FeishuCommand(
                kind = FeishuCommandKind.CHAT_MESSAGES,
                chatId = chatId,
                userId = userId,
                start = flags["--start"] ?: flags["--start-time"],
                end = flags["--end"] ?: flags["--end-time"],
            )
        }
        domain == "calendar" && shortcut == "+agenda" -> FeishuCommand(
            kind = FeishuCommandKind.CALENDAR_AGENDA,
            start = flags["--start"],
            end = flags["--end"],
            calendarId = flags["--calendar-id"],
        )
        domain == "drive" && shortcut == "+search" -> FeishuCommand(
            kind = FeishuCommandKind.DRIVE_SEARCH,
            query = flags["--query"],
        )
        domain == "docs" && shortcut == "+fetch" -> {
            val doc = flags["--doc"]
            if (doc.isNullOrBlank()) return null
            FeishuCommand(
                kind = FeishuCommandKind.DOCS_FETCH,
                doc = doc,
                docFormat = flags["--doc-format"],
            )
        }
        domain == "task" && shortcut == "+get-my-tasks" -> FeishuCommand(FeishuCommandKind.TASKS)
        else -> null
    }
}

private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*$""")

private fun tokenize(command: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var hasToken = false
    command.forEach { char ->
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }
            char == '\\' && quote != '\'' -> escaped = true
            quote != null -> {
                if (char == quote) quote = null else current.append(char)
            }
            char == '\'' || char == '"' -> {
                quote = char
                hasToken = true
            }
            char.isWhitespace() -> {
                if (hasToken) {
                    tokens += current.toString()
                    current.clear()
                    hasToken = false
                }
            }
            else -> {
                current.append(char)
                hasToken = true
            }
        }
    }
    if (hasToken) tokens += current.toString()
    return tokens
}

private fun readFlags(args: List<String>): Map<String, String> {
    val flags = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val token = args[index]
        if (!token.startsWith("-")) {
            index++
            continue
        }
        val separator = token.indexOf('=')
        if (separator > 0) {
            flags[token.substring(0, separator)] = token.substring(separator + 1)
            index++
            continue
        }
        val next = args.getOrNull(index + 1)
        if (next != null && !next.startsWith("-")) {
            flags[token] = next
            index += 2
        } else {
            flags[token] = ""
            index++
        }
    }
    return flags
}

internal fun buildFeishuObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? =
    runCatching {
        when (command.kind) {
            FeishuCommandKind.CHAT_LIST -> chatListObservation(host, envelope)
            FeishuCommandKind.CHAT_MESSAGES -> messagesObservation(host, command, envelope)
            FeishuCommandKind.CALENDAR_AGENDA -> agendaObservation(host, command, envelope)
            FeishuCommandKind.DRIVE_SEARCH -> docsSearchObservation(host, command, envelope)
            FeishuCommandKind.DOCS_FETCH -> docsReadObservation(host, command, envelope)
            FeishuCommandKind.TASKS -> tasksObservation(host, envelope)
        }
    }.getOrNull()

private const val BRIDGE_LOG_PREFIX = "feishu.bridge"

internal fun PluginHost.feishuBridgeLog(message: String) {
    runCatching { logs().debug("$BRIDGE_LOG_PREFIX $message") }
}

internal fun PluginHost.feishuBridgeWarn(message: String, error: Throwable? = null) {
    runCatching { logs().warn("$BRIDGE_LOG_PREFIX $message", error) }
}

internal fun larkCliDiagnosticSummary(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "cmd=empty"
    val tokens = tokenize(trimmed)
    if (tokens.isEmpty()) return "cmd=empty"
    var index = 0
    while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
    if (index >= tokens.size) return "cmd=no-cli-token"
    val binary = tokens[index]
    if (binary != FeishuCli.BINARY && !binary.endsWith("/${FeishuCli.BINARY}")) {
        return "cmd=not-cli token0=${tokens.first().take(24)}"
    }
    val rest = tokens.drop(index + 1)
    val domain = rest.getOrNull(0)?.take(24).orEmpty()
    val shortcut = rest.getOrNull(1)?.take(24).orEmpty()
    val flagNames = rest.drop(2)
        .filter { it.startsWith("-") }
        .map { it.substringBefore('=').take(24) }
        .joinToString(",")
    return "cmd=cli domain=$domain shortcut=$shortcut flags=[$flagNames] len=${raw.length}"
}

internal class FeishuToolBridge {
    private data class PendingInvocation(
        val sessionId: String,
        val command: FeishuCommand,
    )

    private val pending = object : LinkedHashMap<String, PendingInvocation>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingInvocation>?): Boolean =
            size > MAX_PENDING
    }
    private val handled = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_PENDING
    }

    fun hooks(host: PluginHost): PluginHooks = object : PluginHooks {
        override suspend fun beforeToolCall(request: ToolCallRequest): ToolCallDecision = try {
            whileToolCall(host, request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            host.feishuBridgeWarn(
                "before call=${request.callId} hook-error=${error.javaClass.simpleName} ${error.message?.take(120).orEmpty()}",
            )
            toolContinue()
        }

        override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision = try {
            whileToolResult(host, request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            host.feishuBridgeWarn(
                "after call=${request.callId} hook-error=${error.javaClass.simpleName} ${error.message?.take(120).orEmpty()}",
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

    private fun clearSession(host: PluginHost, sessionId: String) {
        val removed = synchronized(this) {
            val before = pending.size
            pending.entries.removeAll { it.value.sessionId == sessionId }
            before - pending.size
        }
        host.feishuBridgeLog("clear pending=$removed remaining=${pendingSize()}")
    }

    private fun whileToolCall(host: PluginHost, request: ToolCallRequest): ToolCallDecision {
        if (request.toolName != BASH_TOOL) return toolContinue()
        val raw = (request.args["command"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val summary = larkCliDiagnosticSummary(raw)
        val command = parseLarkCliCommand(raw)
        if (command == null) {
            host.feishuBridgeLog("before call=${request.callId} match=none $summary")
            return toolContinue()
        }
        val size = synchronized(this) {
            pending[request.callId] = PendingInvocation(request.sessionId, command)
            pending.size
        }
        host.feishuBridgeLog("before call=${request.callId} match=${command.kind} pending=$size $summary")
        return toolContinue()
    }

    private suspend fun whileToolResult(host: PluginHost, request: ToolExecutionResult): ToolResultDecision {
        if (request.toolName != BASH_TOOL) return resultKeep()
        val invocation = synchronized(this) { pending.remove(request.callId) }
        if (invocation == null) {
            host.feishuBridgeLog("after call=${request.callId} pending=miss (before hook not recorded)")
            return resultKeep()
        }
        host.feishuBridgeLog("after call=${request.callId} pending=hit kind=${invocation.command.kind}")
        if (!markHandled(request.callId)) {
            host.feishuBridgeLog("after call=${request.callId} skip=duplicate")
            return resultKeep()
        }
        val isError = request.result.isError
        if (isError) {
            host.feishuBridgeLog("after call=${request.callId} skip=is-error")
            return resultKeep()
        }
        val output = request.result.text.orEmpty()
        val outputBytes = output.toByteArray(Charsets.UTF_8).size
        host.feishuBridgeLog("after call=${request.callId} output-bytes=$outputBytes")
        if (output.isEmpty() || outputBytes > FeishuCliOutput.MAX_OUTPUT_BYTES) {
            host.feishuBridgeLog("after call=${request.callId} skip=output-size")
            return resultKeep()
        }
        val envelope = FeishuCliOutput.findEnvelope(output)
        if (envelope == null) {
            host.feishuBridgeWarn("after call=${request.callId} parse=json-miss bytes=$outputBytes")
            return resultKeep()
        }
        if (!FeishuCliOutput.isSuccess(envelope)) {
            host.feishuBridgeWarn("after call=${request.callId} parse=not-ok")
            return resultKeep()
        }
        val hasNotice = envelope["_notice"] != null
        val observation = buildFeishuObservation(host, command = invocation.command, envelope = FeishuCliOutput.stripNotice(envelope))
        if (observation == null) {
            host.feishuBridgeWarn("after call=${request.callId} parse=observation-miss kind=${invocation.command.kind}")
            return resultKeep()
        }
        host.feishuBridgeLog(
            "after call=${request.callId} observation=ok kind=${observation.card.kind} " +
                "items=${observation.card.items.size} chars=${observation.text.length} notice=$hasNotice",
        )
        val cardStartedAt = System.nanoTime()
        val posted = postCard(host, request.sessionId, observation)
        val cardMs = (System.nanoTime() - cardStartedAt) / 1_000_000
        host.feishuBridgeLog("after call=${request.callId} card=$posted ms=$cardMs")
        if (!posted) {
            host.feishuBridgeWarn("after call=${request.callId} card=false")
            return resultKeep()
        }
        host.feishuBridgeLog("after call=${request.callId} decision=replace")
        return resultReplace(textResult(observation.text))
    }

    private suspend fun postCard(host: PluginHost, sessionId: String, observation: FeishuObservation): Boolean {
        if (sessionId.isBlank()) {
            host.feishuBridgeLog("card skip=blank-session")
            return false
        }
        val chatService = try {
            host.chat()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            host.feishuBridgeWarn("card chat-unavailable=${error.javaClass.simpleName} ${error.message?.take(120).orEmpty()}")
            return false
        }
        val payload = encodeFeishuCardPayload(observation.card)
        return try {
            chatService.postCustomMessage(
                sessionId = sessionId,
                customType = FeishuCardTypes.CHAT_CARD,
                payload = payload,
                text = observation.summary,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            host.feishuBridgeWarn("card post-error=${error.javaClass.simpleName} ${error.message?.take(160).orEmpty()}")
            false
        }
    }

    private fun pendingSize(): Int = synchronized(this) { pending.size }

    private fun markHandled(callId: String): Boolean = synchronized(this) {
        if (handled.containsKey(callId)) return false
        handled[callId] = true
        true
    }

    private companion object {
        const val BASH_TOOL = "bash"
        const val MAX_PENDING = 64
    }
}

private fun JsonObject.obj(name: String): JsonObject? =
    runCatching { (this[name] as? JsonObject) ?: this[name]?.jsonObject }.getOrNull()

private fun JsonObject.array(name: String): List<JsonElement> =
    runCatching { (this[name] as? JsonArray)?.toList() ?: this[name]?.jsonArray?.toList() }.getOrNull().orEmpty()

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.list(name: String): List<JsonObject> =
    array(name).mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }

private fun dataObject(envelope: JsonObject): JsonObject? = envelope.obj("data")

private fun chatListObservation(host: PluginHost, envelope: JsonObject): FeishuObservation? {
    val data = dataObject(envelope) ?: return null
    val chats = data.list("chats")
    val total = chats.size
    val zh = host.isChinese()
    val rows = chats.take(MAX_CARD_ROWS).map { chat ->
        val id = chat.string("chat_id")
        val name = chat.string("name") ?: id.orEmpty()
        val mode = chat.string("chat_mode")
        val modeLabel = when {
            mode == "p2p" -> if (zh) "单聊" else "Direct"
            mode == "topic" -> if (zh) "话题群" else "Topic group"
            else -> if (zh) "群聊" else "Group"
        }
        val description = chat.string("description")
        FeishuCardItem(
            title = name,
            subtitle = description?.let { FeishuCliOutput.oneLine(it, 80) },
            meta = modeLabel,
            url = chat.string("chat_app_link") ?: FeishuCliOutput.chatAppLink(id),
        )
    }
    val detailRows = chats.take(MAX_CARD_ROWS).mapIndexed { index, chat ->
        val id = chat.string("chat_id").orEmpty()
        val name = chat.string("name") ?: id
        val mode = when (chat.string("chat_mode")) {
            "p2p" -> if (zh) "单聊" else "direct"
            "topic" -> if (zh) "话题群" else "topic group"
            else -> if (zh) "群聊" else "group"
        }
        val description = chat.string("description")?.let { " — ${FeishuCliOutput.oneLine(it, 80)}" }.orEmpty()
        val link = chat.string("chat_app_link") ?: FeishuCliOutput.chatAppLink(id)
        val linkPart = link?.let { if (zh) "；链接：$it" else "; link: $it" }.orEmpty()
        "${index + 1}. $name（$mode，chat_id: $id）$description$linkPart"
    }
    val note = if (total > rows.size) {
        if (zh) "还有 ${total - rows.size} 个未显示" else "${total - rows.size} more not shown"
    } else {
        null
    }
    val header = if (zh) "飞书聊天列表（共 $total 个）" else "Feishu chats ($total total)"
    val text = buildString {
        appendLine(header)
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "当前身份没有加入任何聊天。" else "The current identity is not in any chats.")
        } else {
            detailRows.forEach { appendLine(it) }
            note?.let { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_CHAT_LIST,
            total = total,
            items = rows,
            openUrl = null,
            note = note,
        ),
    )
}

private fun messagesObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = dataObject(envelope) ?: return null
    val messages = data.list("messages")
    val total = messages.size
    val zh = host.isChinese()
    val chatId = messages.firstNotNullOfOrNull { it.string("chat_id") } ?: command.chatId
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    var longCount = 0
    messages.take(MAX_CARD_ROWS).forEachIndexed { index, message ->
        val messageId = message.string("message_id").orEmpty()
        val msgType = message.string("msg_type").orEmpty()
        val link = message.string("message_app_link")
        val rawContent = message.string("content").orEmpty()
        val plain = FeishuCliOutput.sanitizeText(rawContent)
        val sender = message.obj("sender")?.string("name").orEmpty().ifBlank {
            message.obj("sender")?.string("id").orEmpty()
        }
        val time = message.string("create_time")
        val interactive = msgType == "interactive"
        val long = interactive || plain.length > 240
        if (long) longCount++
        val summary = when {
            interactive -> if (zh) "交互消息" else "Interactive message"
            plain.isEmpty() -> if (zh) "（无文字内容）" else "(no text)"
            long -> FeishuCliOutput.oneLine(plain, 60)
            else -> plain
        }
        rows += FeishuCardItem(
            title = summary,
            subtitle = listOfNotNull(sender.takeIf { it.isNotBlank() }, msgTypeLabel(msgType, zh)).joinToString(" · "),
            meta = FeishuCliOutput.shortTime(time),
            url = link,
        )
        val timePart = time ?: if (zh) "未知时间" else "unknown time"
        val senderPart = sender.ifBlank { if (zh) "未知发送者" else "unknown sender" }
        val contentPart = when {
            interactive -> if (zh) "交互消息（内容较长，完整内容请打开飞书查看）" else "Interactive message (open Feishu for the full content)"
            plain.isEmpty() -> if (zh) "（无文字内容）" else "(no text)"
            else -> FeishuCliOutput.oneLine(plain)
        }
        val linkPart = link?.let { if (zh) "；链接：$it" else "; link: $it" }.orEmpty()
        detailRows += "${index + 1}. [$timePart] $senderPart（$msgType）：$contentPart" +
            (if (zh) "（消息 ID：$messageId$linkPart）" else " (message id: $messageId$linkPart)")
    }
    val header = if (zh) "飞书消息（共 $total 条）" else "Feishu messages ($total total)"
    val context = buildString {
        val chat = chatId?.let { if (zh) "会话：$it" else "chat: $it" }
        if (chat != null) append(chat)
        val range = timeRangeLabel(command.start, command.end, zh)
        if (range != null) append(if (isEmpty()) "" else (if (zh) "；" else "; ")).append(range)
    }.ifBlank { null }
    val note = when {
        total > rows.size -> if (zh) "还有 ${total - rows.size} 条未显示" else "${total - rows.size} more not shown"
        longCount > 0 -> if (zh) "长消息只显示摘要，点开在飞书查看完整内容" else "Long messages show a summary only; open Feishu for the full content"
        else -> null
    }
    val text = buildString {
        appendLine(header)
        context?.let { appendLine(it) }
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "这个时间段没有消息。" else "No messages in this time range.")
        } else {
            detailRows.forEach { appendLine(it) }
            note?.let { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_MESSAGES,
            context = context,
            total = total,
            items = rows,
            openUrl = FeishuCliOutput.chatAppLink(chatId) ?: rows.firstOrNull()?.url,
            note = note,
        ),
    )
}

private fun agendaObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val events = envelope.array("data").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
    val total = events.size
    val zh = host.isChinese()
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    events.take(MAX_CARD_ROWS).forEachIndexed { index, event ->
        val eventId = event.string("event_id").orEmpty()
        val summary = FeishuCliOutput.oneLine(event.string("summary").orEmpty(), 80)
            .ifBlank { if (zh) "（无标题日程）" else "(untitled event)" }
        val start = event.obj("start_time")
        val end = event.obj("end_time")
        val startLabel = eventTimeLabel(start)
        val endLabel = eventTimeLabel(end)
        val allDay = start != null && start.string("date") != null && start.string("datetime") == null
        val timeLabel = when {
            allDay -> if (zh) "全天" else "All day"
            startLabel != null && endLabel != null -> "$startLabel-$endLabel"
            else -> startLabel ?: endLabel ?: (if (zh) "时间未知" else "time unknown")
        }
        val location = event.obj("location")?.string("name")
        val freeBusy = event.string("free_busy_status")
        val rsvp = event.string("self_rsvp_status")
        rows += FeishuCardItem(
            title = summary,
            subtitle = listOfNotNull(timeLabel, location?.takeIf { it.isNotBlank() }).joinToString(" · "),
            meta = if (allDay) null else FeishuCliOutput.shortTime(start?.string("datetime")),
        )
        val facts = buildString {
            append("${index + 1}. $timeLabel $summary")
            location?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；地点：$it" else "; location: $it") }
            freeBusy?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；忙闲：$it" else "; busy status: $it") }
            rsvp?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；我的答复：$it" else "; my RSVP: $it") }
            append(if (zh) "（event_id: $eventId）" else " (event_id: $eventId)")
        }
        detailRows += facts
    }
    val header = if (zh) "飞书日程（共 $total 个）" else "Feishu agenda ($total events)"
    val range = timeRangeLabel(command.start, command.end, zh)
    val text = buildString {
        appendLine(header)
        range?.let { appendLine(it) }
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "这段时间没有日程，这是正常的空结果。" else "No events in this time range; that is a normal empty result.")
        } else {
            detailRows.forEach { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_AGENDA,
            context = range,
            total = total,
            items = rows,
            note = null,
        ),
    )
}

private fun docsSearchObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = dataObject(envelope) ?: return null
    val results = data.list("results")
    val total = results.size
    val zh = host.isChinese()
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    results.take(MAX_CARD_ROWS).forEachIndexed { index, result ->
        val title = FeishuCliOutput.oneLine(result.string("title").orEmpty(), 80)
            .ifBlank { if (zh) "（无标题文档）" else "(untitled doc)" }
        val type = result.string("doc_type").orEmpty()
        val token = result.string("token").orEmpty()
        val url = result.string("url")
        val edited = FeishuCliOutput.fullTime(result.string("edit_time_iso"))
        val owner = result.string("owner_id")
        rows += FeishuCardItem(
            title = title,
            subtitle = listOfNotNull(type.ifBlank { null }, edited?.let { if (zh) "编辑于 $it" else "edited $it" }).joinToString(" · "),
            meta = null,
            url = url,
        )
        detailRows += buildString {
            append("${index + 1}. $title")
            if (type.isNotBlank()) append("（$type）")
            edited?.let { append(if (zh) "；编辑时间：$it" else "; edited: $it") }
            owner?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；owner: $it" else "; owner: $it") }
            append(if (zh) "；token: $token" else "; token: $token")
            url?.let { append(if (zh) "；链接：$it" else "; link: $it") }
        }
    }
    val query = command.query?.trim()?.takeIf { it.isNotBlank() }
    val header = if (zh) "飞书云文档（共 $total 个）" else "Feishu docs ($total total)"
    val context = query?.let {
        if (zh) "搜索：${FeishuCliOutput.oneLine(it, 60)}" else "search: ${FeishuCliOutput.oneLine(it, 60)}"
    }
    val text = buildString {
        appendLine(header)
        context?.let { appendLine(it) }
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "没有找到匹配的文档，可以换关键词再试。" else "No matching docs; try another keyword.")
        } else {
            detailRows.forEach { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_DOCS_SEARCH,
            context = context,
            total = total,
            items = rows,
            openUrl = results.firstOrNull()?.string("url"),
        ),
    )
}

private fun docsReadObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = dataObject(envelope) ?: return null
    val document = data.obj("document") ?: return null
    val documentId = document.string("document_id") ?: command.doc
    val content = document.string("content").orEmpty()
    val plain = FeishuCliOutput.cleanDocContent(content)
    val titleFromXml = content.takeIf { it.contains("<title>") }
        ?.substringAfter("<title>")?.substringBefore("</title>")
        ?.let { FeishuCliOutput.oneLine(it, 80) }
    val titleFromMarkdown = plain.lineSequence().firstOrNull()
        ?.trim()?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { FeishuCliOutput.oneLine(it, 80) }
    val title = titleFromXml ?: titleFromMarkdown
    val paragraphs = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(6).toList()
    val zh = host.isChinese()
    val url = FeishuCliOutput.feishuDocUrl(command.doc ?: documentId)
    val excerpt = paragraphs.take(3).map { FeishuCliOutput.clip(it, 160) }
    val rows = excerpt.map { FeishuCardItem(title = it) }
    val header = if (zh) "飞书文档" else "Feishu document"
    val context = title?.takeIf { it.isNotBlank() }
    val note = if (zh) "正文约 ${plain.length} 字" else "${plain.length} chars"
    val text = buildString {
        appendLine(if (zh) "飞书文档：${context ?: documentId}" else "Feishu document: ${context ?: documentId}")
        appendLine(if (zh) "文档 ID：$documentId；版本：${document.int("revision_id") ?: 0}" else "document id: $documentId; revision: ${document.int("revision_id") ?: 0}")
        if (excerpt.isEmpty()) {
            appendLine(if (zh) "文档正文为空。" else "The document is empty.")
        } else {
            appendLine(if (zh) "内容节选：" else "Excerpt:")
            excerpt.forEach { appendLine("- $it") }
        }
        url?.let { appendLine(if (zh) "链接：$it" else "link: $it") }
    }.trim()
    return FeishuObservation(
        summary = if (zh) "已读取文档「${context ?: documentId}」" else "Read document \"${context ?: documentId}\"",
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_DOCS_READ,
            context = context,
            total = excerpt.size,
            items = rows,
            openUrl = url,
            note = note,
        ),
    )
}

private fun tasksObservation(host: PluginHost, envelope: JsonObject): FeishuObservation? {
    val data = dataObject(envelope) ?: return null
    val tasks = data.list("items")
    val total = tasks.size
    val zh = host.isChinese()
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    tasks.take(MAX_CARD_ROWS).forEachIndexed { index, task ->
        val guid = task.string("guid").orEmpty()
        val summary = FeishuCliOutput.oneLine(task.string("summary").orEmpty(), 80)
            .ifBlank { if (zh) "（无标题任务）" else "(untitled task)" }
        val completed = task.bool("completed") == true
        val due = FeishuCliOutput.fullTime(task.string("due_at"))
        val completedAt = FeishuCliOutput.fullTime(task.string("completed_at"))
        val url = task.string("url")
        val subtitle = when {
            completed && completedAt != null -> if (zh) "完成于 $completedAt" else "completed $completedAt"
            due != null -> if (zh) "截止 $due" else "due $due"
            else -> if (zh) "无截止时间" else "no due date"
        }
        rows += FeishuCardItem(
            title = summary,
            subtitle = subtitle,
            badge = if (completed) (if (zh) "已完成" else "Done") else (if (zh) "待办" else "To do"),
            meta = FeishuCliOutput.shortTime(task.string("due_at")),
            url = url,
        )
        detailRows += buildString {
            append("${index + 1}. $summary（${if (completed) (if (zh) "已完成" else "done") else (if (zh) "未完成" else "open")}）")
            if (completed) completedAt?.let { append(if (zh) "；完成时间：$it" else "; completed: $it") }
            else due?.let { append(if (zh) "；截止：$it" else "; due: $it") }
            append(if (zh) "；guid: $guid" else "; guid: $guid")
            url?.let { append(if (zh) "；链接：$it" else "; link: $it") }
        }
    }
    val open = tasks.count { it.bool("completed") != true }
    val header = if (zh) "飞书任务（共 $total 条，未完成 $open 条）" else "Feishu tasks ($total total, $open open)"
    val text = buildString {
        appendLine(header)
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "没有待办任务，这是正常的空结果。" else "No tasks; that is a normal empty result.")
        } else {
            detailRows.forEach { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_TASKS,
            total = total,
            items = rows,
            openUrl = tasks.firstOrNull()?.string("url"),
        ),
    )
}

private fun eventTimeLabel(time: JsonObject?): String? {
    if (time == null) return null
    time.string("datetime")?.let { return FeishuCliOutput.shortTime(it) }
    time.string("date")?.let { return it }
    time.string("timestamp")?.let { raw ->
        return runCatching {
            OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(raw.toLong()), java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrNull()
    }
    return null
}

private fun msgTypeLabel(msgType: String, zh: Boolean): String = when (msgType) {
    "text" -> if (zh) "文本" else "text"
    "post" -> if (zh) "富文本" else "post"
    "image" -> if (zh) "图片" else "image"
    "file" -> if (zh) "文件" else "file"
    "audio" -> if (zh) "语音" else "audio"
    "video", "media" -> if (zh) "视频" else "video"
    "interactive" -> if (zh) "交互消息" else "interactive"
    "sticker" -> if (zh) "表情" else "sticker"
    "system" -> if (zh) "系统消息" else "system"
    else -> msgType.ifBlank { if (zh) "消息" else "message" }
}

private fun timeRangeLabel(start: String?, end: String?, zh: Boolean): String? {
    val from = FeishuCliOutput.shortTime(start)
    val to = FeishuCliOutput.shortTime(end)
    return when {
        from != null && to != null -> if (zh) "时间范围：$from 至 $to" else "range: $from to $to"
        from != null -> if (zh) "起始：$from" else "from: $from"
        to != null -> if (zh) "截止：$to" else "until: $to"
        else -> null
    }
}
