package io.github.whywood.deepdeep.plugin.sysu

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal enum class SysuCardKind(val wire: String) {
    Schedule("schedule"),
    Grades("grades"),
    Exams("exams"),
    Lms("lms"),
    Todo("todo"),
    Gym("gym"),
    Netpay("netpay"),
    ;

    companion object {
        fun fromWire(value: String?): SysuCardKind? = entries.firstOrNull { it.wire == value }
    }
}

internal object SysuCardState {
    const val READY = "ready"
    const val EMPTY = "empty"
    const val ERROR = "error"
    const val LOADING = "loading"
}

@Serializable
internal data class SysuCardPayload(
    val kind: String = SysuCardKind.Lms.wire,
    val state: String = SysuCardState.READY,
    val title: String = "",
    val subtitle: String? = null,
    val count: String? = null,
    val groups: List<SysuCardGroup> = emptyList(),
    val body: String? = null,
    val footer: String? = null,
)

@Serializable
internal data class SysuCardGroup(
    val label: String? = null,
    val rows: List<SysuCardRow> = emptyList(),
)

@Serializable
internal data class SysuCardRow(
    val title: String = "",
    val subtitle: String? = null,
    val value: String? = null,
    val subtitleMaxLines: Int = 1,
)

internal const val SYSU_CARD_TYPE = "sysu.card"

internal const val MAX_CARD_ROWS = 8

internal object SysuCards {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun ready(
        kind: SysuCardKind,
        title: String,
        subtitle: String? = null,
        count: String? = null,
        groups: List<SysuCardGroup> = emptyList(),
        footer: String? = null,
    ): SysuCardPayload = SysuCardPayload(
        kind = kind.wire,
        state = SysuCardState.READY,
        title = title,
        subtitle = subtitle,
        count = count,
        groups = groups,
        footer = footer,
    )

    fun empty(
        kind: SysuCardKind,
        title: String,
        subtitle: String? = null,
        body: String,
    ): SysuCardPayload = SysuCardPayload(
        kind = kind.wire,
        state = SysuCardState.EMPTY,
        title = title,
        subtitle = subtitle,
        body = body,
    )

    fun error(
        kind: SysuCardKind,
        title: String,
        body: String,
        subtitle: String? = null,
    ): SysuCardPayload = SysuCardPayload(
        kind = kind.wire,
        state = SysuCardState.ERROR,
        title = title,
        subtitle = subtitle,
        body = body,
    )

    fun loading(
        kind: SysuCardKind,
        title: String,
        body: String? = null,
    ): SysuCardPayload = SysuCardPayload(
        kind = kind.wire,
        state = SysuCardState.LOADING,
        title = title,
        body = body,
    )

    fun capRows(
        groups: List<SysuCardGroup>,
        max: Int = MAX_CARD_ROWS,
    ): Pair<List<SysuCardGroup>, Int> {
        var remaining = max
        var dropped = 0
        val limited = mutableListOf<SysuCardGroup>()
        for (group in groups) {
            if (remaining <= 0) {
                dropped += group.rows.size
                continue
            }
            val take = minOf(remaining, group.rows.size)
            remaining -= take
            dropped += group.rows.size - take
            if (take > 0) limited += group.copy(rows = group.rows.take(take))
        }
        return limited to dropped
    }

    fun footerWithOverflow(
        base: String?,
        dropped: Int,
        strings: SysuStrings,
    ): String? = listOfNotNull(
        base?.takeIf { it.isNotBlank() },
        if (dropped > 0) strings.text("还有 $dropped 项未显示", "$dropped more not shown") else null,
    ).joinToString(" · ").ifEmpty { null }

    fun encode(payload: SysuCardPayload): JsonObject =
        json.encodeToJsonElement(SysuCardPayload.serializer(), payload).jsonObject

    fun decode(payload: JsonObject): SysuCardPayload? =
        runCatching { json.decodeFromJsonElement(SysuCardPayload.serializer(), payload) }.getOrNull()

    fun summaryText(payload: SysuCardPayload): String = listOfNotNull(
        payload.title.takeIf { it.isNotBlank() },
        payload.count?.takeIf { it.isNotBlank() },
        payload.subtitle?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    fun normalized(payload: SysuCardPayload): SysuCardPayload {
        val state = when (payload.state) {
            SysuCardState.EMPTY, SysuCardState.ERROR, SysuCardState.LOADING -> payload.state
            else -> SysuCardState.READY
        }
        val (groups, _) = capRows(payload.groups)
        return payload.copy(state = state, groups = groups)
    }
}
