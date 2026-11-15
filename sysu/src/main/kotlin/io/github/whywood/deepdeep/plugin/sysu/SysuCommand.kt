package io.github.whywood.deepdeep.plugin.sysu

internal enum class SysuCommandKind {
    Schedule,
    Scores,
    Exams,
    Credits,
    LmsCourses,
    LmsTodo,
    GymSlots,
    Netpay,
}

internal sealed interface SysuInvocation {
    val commandLine: String

    data class Supported(
        override val commandLine: String,
        val kind: SysuCommandKind,
    ) : SysuInvocation

    data class Other(override val commandLine: String) : SysuInvocation
}

internal object SysuCommandParser {
    private val BOOL_FLAGS = setOf("--json", "--all", "--today", "--tomorrow")
    private val VALUE_FLAGS = setOf("--week", "--semester", "--days", "--date", "--campus")

    fun parse(command: String): SysuInvocation? {
        val source = command.trim()
        if (source.isEmpty()) return null
        val tokens = tokenize(source) ?: return null
        if (tokens.isEmpty()) return null
        if (tokens.first().substringAfterLast('/') != EXECUTABLE) return null
        return match(tokens.drop(1)) ?: SysuInvocation.Other(source)
    }

    private fun match(args: List<String>): SysuInvocation.Supported? {
        val positionals = mutableListOf<String>()
        val flags = linkedSetOf<String>()
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg.startsWith("-") -> {
                    if (arg.contains('=')) return null
                    if (arg in BOOL_FLAGS) {
                        flags += arg
                    } else if (arg in VALUE_FLAGS) {
                        val value = args.getOrNull(index + 1) ?: return null
                        if (value.startsWith("-")) return null
                        values[arg] = value
                        index += 1
                    } else {
                        return null
                    }
                }

                else -> positionals += arg
            }
            index += 1
        }
        if (JSON_FLAG !in flags) return null
        return when (positionals.firstOrNull()) {
            null -> null
            "jwxt" -> jwxt(positionals.drop(1), flags, values)
            "lms" -> lms(positionals.drop(1), flags, values)
            "gym" -> gym(positionals.drop(1), flags, values)
            "netpay" -> netpay(positionals.drop(1), flags, values)
            else -> null
        }
    }

    private fun jwxt(
        positionals: List<String>,
        flags: Set<String>,
        values: Map<String, String>,
    ): SysuInvocation.Supported? {
        val sub = positionals.firstOrNull() ?: "schedule"
        if (positionals.size > 1) return null
        val kind = when (sub) {
            "schedule" -> SysuCommandKind.Schedule
            "scores" -> SysuCommandKind.Scores
            "exams" -> SysuCommandKind.Exams
            "credits" -> SysuCommandKind.Credits
            else -> return null
        }
        val allowedFlags = when (kind) {
            SysuCommandKind.Schedule -> setOf(JSON_FLAG, "--today", "--tomorrow")
            SysuCommandKind.Scores -> setOf(JSON_FLAG, "--all")
            else -> setOf(JSON_FLAG)
        }
        val allowedValues = when (kind) {
            SysuCommandKind.Schedule -> setOf("--week")
            SysuCommandKind.Scores -> setOf("--semester")
            else -> emptySet()
        }
        if (!isAllowed(flags, allowedFlags) || !isAllowed(values.keys, allowedValues)) return null
        return supported(kind, positionals, values)
    }

    private fun lms(
        positionals: List<String>,
        flags: Set<String>,
        values: Map<String, String>,
    ): SysuInvocation.Supported? {
        val sub = positionals.firstOrNull() ?: "courses"
        if (positionals.size > 1) return null
        val kind = when (sub) {
            "courses", "list" -> SysuCommandKind.LmsCourses
            "todo" -> SysuCommandKind.LmsTodo
            else -> return null
        }
        val allowedValues = if (kind == SysuCommandKind.LmsTodo) setOf("--days") else emptySet()
        if (!isAllowed(flags, setOf(JSON_FLAG)) || !isAllowed(values.keys, allowedValues)) return null
        return supported(kind, positionals, values)
    }

    private fun gym(
        positionals: List<String>,
        flags: Set<String>,
        values: Map<String, String>,
    ): SysuInvocation.Supported? {
        if (positionals.firstOrNull() != "slots") return null
        val type = positionals.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        if (positionals.size > 2) return null
        if (!isAllowed(flags, setOf(JSON_FLAG, "--all"))) return null
        if (!isAllowed(values.keys, setOf("--days", "--date", "--campus"))) return null
        return supported(SysuCommandKind.GymSlots, listOf(type), values)
    }

    private fun netpay(
        positionals: List<String>,
        flags: Set<String>,
        values: Map<String, String>,
    ): SysuInvocation.Supported? {
        val sub = positionals.firstOrNull() ?: "info"
        if (sub != "info" || positionals.size > 1) return null
        if (!isAllowed(flags, setOf(JSON_FLAG)) || values.isNotEmpty()) return null
        return supported(SysuCommandKind.Netpay, emptyList(), values)
    }

    private fun supported(
        kind: SysuCommandKind,
        positionals: List<String>,
        values: Map<String, String>,
    ): SysuInvocation.Supported {
        val line = buildString {
            append(EXECUTABLE)
            val site = when (kind) {
                SysuCommandKind.Schedule, SysuCommandKind.Scores, SysuCommandKind.Exams, SysuCommandKind.Credits -> "jwxt"
                SysuCommandKind.LmsCourses, SysuCommandKind.LmsTodo -> "lms"
                SysuCommandKind.GymSlots -> "gym"
                SysuCommandKind.Netpay -> "netpay"
            }
            append(' ').append(site)
            positionals.forEach { append(' ').append(it) }
            values.forEach { (key, value) -> append(' ').append(key).append(' ').append(value) }
            append(' ').append(JSON_FLAG)
        }
        return SysuInvocation.Supported(line, kind)
    }

    private fun isAllowed(keys: Set<String>, allowed: Set<String>): Boolean = keys.all { it in allowed }

    private fun tokenize(input: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (character in input) {
            if (quote != null) {
                if (character == quote) {
                    quote = null
                } else {
                    current.append(character)
                }
                continue
            }
            when {
                character == '\'' || character == '"' -> {
                    quote = character
                    hasToken = true
                }

                character == '\\' || character in SHELL_CONTROL -> return null
                character == ' ' || character == '\t' -> {
                    if (hasToken) {
                        tokens += current.toString()
                        current.clear()
                        hasToken = false
                    }
                }

                else -> {
                    current.append(character)
                    hasToken = true
                }
            }
        }
        if (quote != null) return null
        if (hasToken) tokens += current.toString()
        return tokens
    }

    private const val EXECUTABLE = "sysu"
    private const val JSON_FLAG = "--json"
    private const val SHELL_CONTROL = "|&;<>`$()#\n\r"
}
