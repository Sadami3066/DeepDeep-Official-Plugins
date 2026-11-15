package io.github.whywood.deepdeep.plugin.wecom

internal enum class WecomCommandKind(
    val customType: String,
    val listLabelZh: String,
    val listLabelEn: String,
    val countUnitZh: String,
    val countUnitEn: String,
) {
    CHAT_GROUPS(
        customType = "wecom.chat.groups",
        listLabelZh = "群聊会话",
        listLabelEn = "Group chats",
        countUnitZh = "个会话",
        countUnitEn = "chats",
    ),
    CHAT_MESSAGES(
        customType = "wecom.chat.messages",
        listLabelZh = "会话消息",
        listLabelEn = "Messages",
        countUnitZh = "条消息",
        countUnitEn = "messages",
    ),
    DOC_SEARCH(
        customType = "wecom.doc.search",
        listLabelZh = "文档",
        listLabelEn = "Docs",
        countUnitZh = "篇文档",
        countUnitEn = "docs",
    ),
    DOC_CONTENT(
        customType = "wecom.doc.content",
        listLabelZh = "文档内容",
        listLabelEn = "Doc content",
        countUnitZh = "篇文档",
        countUnitEn = "docs",
    ),
    CALENDAR_SCHEDULES(
        customType = "wecom.calendar.schedules",
        listLabelZh = "日程",
        listLabelEn = "Schedules",
        countUnitZh = "个日程",
        countUnitEn = "schedules",
    ),
    TODO_LIST(
        customType = "wecom.todo.list",
        listLabelZh = "待办",
        listLabelEn = "To-dos",
        countUnitZh = "个待办",
        countUnitEn = "to-dos",
    ),
    CONTACT_USERS(
        customType = "wecom.contact.users",
        listLabelZh = "通讯录",
        listLabelEn = "Contacts",
        countUnitZh = "位成员",
        countUnitEn = "people",
    ),
}

internal data class WecomCommand(
    val kind: WecomCommandKind,
    val chatId: String? = null,
    val beginTime: String? = null,
    val endTime: String? = null,
    val keywords: List<String> = emptyList(),
    val docId: String? = null,
    val limit: Int? = null,
) {
    val contextLabel: String?
        get() = when (kind) {
            WecomCommandKind.CHAT_MESSAGES -> listOfNotNull(
                chatId?.takeIf { it.isNotBlank() }?.let { "会话 $it" },
                rangeLabel(beginTime, endTime),
            ).joinToString("，").ifBlank { null }
            WecomCommandKind.CHAT_GROUPS -> rangeLabel(beginTime, endTime)
            WecomCommandKind.CALENDAR_SCHEDULES -> rangeLabel(beginTime, endTime)
            WecomCommandKind.DOC_SEARCH, WecomCommandKind.CONTACT_USERS ->
                keywords.takeIf { it.isNotEmpty() }?.joinToString(" ")
            WecomCommandKind.DOC_CONTENT -> docId
            WecomCommandKind.TODO_LIST -> null
        }

    private fun rangeLabel(begin: String?, end: String?): String? = when {
        begin != null && end != null -> "$begin 至 $end"
        begin != null -> "$begin 起"
        end != null -> "截至 $end"
        else -> null
    }
}

internal object WecomCommands {
    const val FILE_NAME = "wecom-cli"
    const val MAX_COMMAND_LENGTH = 4096
    private const val MAX_TOKENS = 160

    private val ENV_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
    private val ALIASES = mapOf(
        "chat" to "chat",
        "chats" to "chat",
        "doc" to "doc",
        "docs" to "doc",
        "document" to "doc",
        "documents" to "doc",
        "contact" to "contact",
        "contacts" to "contact",
        "calendar" to "calendar",
        "todo" to "todo",
        "todos" to "todo",
    )

    private val VALUE_FLAGS = setOf(
        "--begin-time",
        "--end-time",
        "--chat-id",
        "--cursor",
        "--limit",
        "--docid",
        "--json",
        "--set",
        "--output",
        "--output-dir",
        "--page-count",
        "--page-delay",
        "--created-after",
        "--created-before",
        "--opened-after",
        "--opened-before",
        "--hl-fragment-len",
        "--number-of-fragments",
        "--search-scope",
        "--sort-by",
        "--sort-script",
    )

    private val VARIADIC_FLAGS = setOf(
        "--keywords",
        "--status-filter",
        "--doc-types",
        "--creator-userids",
        "--visitor-userids",
    )

    private val BOOLEAN_FLAGS = setOf(
        "--dry-run",
        "--doc",
        "--schema",
        "--help",
        "--version",
    )

    private val UNSUPPORTED_FLAGS = setOf(
        "--dry-run",
        "--doc",
        "--schema",
        "--help",
        "--version",
        "--page-count",
        "--output",
        "--output-dir",
        "-o",
        "-h",
        "-V",
    )

    fun parse(command: String): WecomCommand? {
        if (command.isBlank() || command.length > MAX_COMMAND_LENGTH) return null
        if (!command.contains(FILE_NAME)) return null
        val tokens = tokenize(command) ?: return null
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
        val binaryIndex = tokens.indexOfFirst { isBinaryToken(it) }
        if (binaryIndex < 0) return null
        for (index in 0 until binaryIndex) {
            if (!ENV_ASSIGNMENT.matches(tokens[index])) return null
        }
        if (tokens.drop(binaryIndex + 1).any(::isBinaryToken)) return null

        val arguments = tokens.drop(binaryIndex + 1)
        if (arguments.any { UNSUPPORTED_FLAGS.contains(it) || it.startsWith("--page-count=") }) return null
        if (arguments.any { it == "-o" || it.startsWith("--output") && it != "--output" }) return null

        val positionals = mutableListOf<String>()
        val flags = mutableMapOf<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            val token = arguments[index]
            if (token == "--") return null
            if (!token.startsWith("-") || token == "-") {
                if (flags.isNotEmpty()) return null
                positionals += token
                index++
                continue
            }
            val equalsIndex = token.indexOf('=')
            if (equalsIndex > 0) {
                val name = token.substring(0, equalsIndex)
                if (!isKnownFlag(name) || name in UNSUPPORTED_FLAGS) return null
                flags.getOrPut(name) { mutableListOf() } += token.substring(equalsIndex + 1)
                index++
                continue
            }
            if (token in UNSUPPORTED_FLAGS) return null
            if (token in BOOLEAN_FLAGS) {
                flags.getOrPut(token) { mutableListOf() } += ""
                index++
                continue
            }
            if (token in VARIADIC_FLAGS) {
                val values = mutableListOf<String>()
                var cursor = index + 1
                while (cursor < arguments.size && !arguments[cursor].startsWith("--")) {
                    values += arguments[cursor]
                    cursor++
                }
                flags.getOrPut(token) { mutableListOf() } += values
                index = cursor
                continue
            }
            if (token in VALUE_FLAGS) {
                if (index + 1 >= arguments.size) return null
                flags.getOrPut(token) { mutableListOf() } += arguments[index + 1]
                index += 2
                continue
            }
            return null
        }
        if (positionals.isEmpty()) return null

        val kind = resolveKind(positionals) ?: return null
        return WecomCommand(
            kind = kind,
            chatId = flags["--chat-id"]?.firstOrNull(),
            beginTime = flags["--begin-time"]?.firstOrNull(),
            endTime = flags["--end-time"]?.firstOrNull(),
            keywords = flags["--keywords"].orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(10),
            docId = flags["--docid"]?.firstOrNull(),
            limit = flags["--limit"]?.firstOrNull()?.toIntOrNull(),
        )
    }

    private fun resolveKind(positionals: List<String>): WecomCommandKind? {
        val normalized = positionals.mapIndexed { index, value ->
            if (index == 0) ALIASES[value] ?: value else value
        }
        return when (normalized) {
            listOf("chat", "groups", "list") -> WecomCommandKind.CHAT_GROUPS
            listOf("chat", "messages", "list") -> WecomCommandKind.CHAT_MESSAGES
            listOf("doc", "search") -> WecomCommandKind.DOC_SEARCH
            listOf("doc", "contents", "get") -> WecomCommandKind.DOC_CONTENT
            listOf("calendar", "schedules", "list") -> WecomCommandKind.CALENDAR_SCHEDULES
            listOf("todo", "list") -> WecomCommandKind.TODO_LIST
            listOf("contact", "users", "search") -> WecomCommandKind.CONTACT_USERS
            else -> null
        }
    }

    private fun isKnownFlag(token: String): Boolean =
        token in VALUE_FLAGS || token in VARIADIC_FLAGS || token in BOOLEAN_FLAGS

    private fun isBinaryToken(token: String): Boolean =
        token == FILE_NAME || token.endsWith("/$FILE_NAME")

    private fun tokenize(command: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var state = TokenState.PLAIN
        var index = 0
        var pending = false
        while (index < command.length) {
            val character = command[index]
            when (state) {
                TokenState.PLAIN -> when {
                    character == ' ' || character == '\t' -> {
                        flushToken(tokens, current, pending)?.let { pending = it }
                    }
                    character == '\n' || character == '\r' -> return null
                    character == '\'' -> {
                        state = TokenState.SINGLE
                        pending = true
                    }
                    character == '"' -> {
                        state = TokenState.DOUBLE
                        pending = true
                    }
                    character == '\\' -> {
                        index++
                        if (index >= command.length) return null
                        current.append(command[index])
                        pending = true
                    }
                    character in COMMAND_METACHARACTERS -> return null
                    else -> {
                        current.append(character)
                        pending = true
                    }
                }
                TokenState.SINGLE -> when (character) {
                    '\'' -> state = TokenState.PLAIN
                    else -> current.append(character)
                }
                TokenState.DOUBLE -> when (character) {
                    '"' -> state = TokenState.PLAIN
                    '\\' -> {
                        index++
                        if (index >= command.length) return null
                        current.append(command[index])
                    }
                    '$' -> {
                        if (index + 1 < command.length && command[index + 1] == '(') return null
                        current.append(character)
                    }
                    '`' -> return null
                    else -> current.append(character)
                }
            }
            index++
        }
        if (state != TokenState.PLAIN) return null
        flushToken(tokens, current, pending)
        return tokens
    }

    private fun flushToken(tokens: MutableList<String>, current: StringBuilder, pending: Boolean): Boolean {
        if (pending) {
            tokens += current.toString()
            current.setLength(0)
        }
        return false
    }

    private const val COMMAND_METACHARACTERS = "|&;<>()`"

    private enum class TokenState {
        PLAIN,
        SINGLE,
        DOUBLE,
    }
}
