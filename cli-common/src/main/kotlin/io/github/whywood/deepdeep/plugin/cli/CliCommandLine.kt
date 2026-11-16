package io.github.whywood.deepdeep.plugin.cli

private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*$""")

object CliCommandLine {
    private const val SHELL_OPERATORS = "|;`><\n\r"

    fun hasShellOperators(command: String): Boolean {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < command.length) {
            val char = command[index]
            if (escaped) {
                escaped = false
                index++
                continue
            }
            if (char == '\\' && quote != '\'') {
                escaped = true
                index++
                continue
            }
            if (quote != null) {
                when {
                    char == quote -> quote = null
                    quote == '"' && (char == '`' ||
                        (char == '$' && command.getOrNull(index + 1) == '(')) -> return true
                }
                index++
                continue
            }
            when {
                char == '\'' || char == '"' -> quote = char
                char in SHELL_OPERATORS -> return true
                char == '$' && command.getOrNull(index + 1) == '(' -> return true
                char == '&' && command.getOrNull(index + 1) == '&' -> return true
            }
            index++
        }
        return false
    }

    fun tokenize(command: String): List<String> {
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

    fun envPrefixLength(tokens: List<String>): Int {
        var index = 0
        while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
        return index
    }

    fun isBinary(token: String, binaryName: String): Boolean =
        token == binaryName || token.endsWith("/$binaryName")

    fun readFlags(args: List<String>): Map<String, String> {
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

    fun diagnosticSummary(raw: String, binaryName: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "cmd=empty"
        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return "cmd=empty"
        val binaryIndex = envPrefixLength(tokens)
        if (binaryIndex >= tokens.size) return "cmd=no-cli-token"
        if (!isBinary(tokens[binaryIndex], binaryName)) {
            return "cmd=not-cli token0=${tokens.first().take(24)}"
        }
        val rest = tokens.drop(binaryIndex + 1)
        val domain = rest.getOrNull(0)?.take(24).orEmpty()
        val shortcut = rest.getOrNull(1)?.take(24).orEmpty()
        val flagNames = rest.drop(2)
            .filter { it.startsWith("-") }
            .map { it.substringBefore('=').take(24) }
            .joinToString(",")
        return "cmd=cli domain=$domain shortcut=$shortcut flags=[$flagNames] len=${raw.length}"
    }
}
