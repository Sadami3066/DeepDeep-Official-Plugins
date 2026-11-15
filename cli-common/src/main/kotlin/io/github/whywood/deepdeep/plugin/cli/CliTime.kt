package io.github.whywood.deepdeep.plugin.cli

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CliTime {
    private val SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun shortDateTime(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching {
            OffsetDateTime.parse(value).format(SHORT)
        }.getOrElse {
            if (value.length >= 16 && value[4] == '-' && value[7] == '-') value.take(16).substring(5) else value
        }
    }

    fun fullDateTime(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching {
            OffsetDateTime.parse(value).format(FULL)
        }.getOrElse { value }
    }

    fun shortEpochSeconds(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching {
            OffsetDateTime.ofInstant(Instant.ofEpochSecond(value.toLong()), ZoneId.systemDefault()).format(SHORT)
        }.getOrNull()
    }
}
