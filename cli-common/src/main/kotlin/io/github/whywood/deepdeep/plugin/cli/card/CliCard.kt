package io.github.whywood.deepdeep.plugin.cli.card

import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class CliCardState(val wire: String) {
    READY("ready"),
    EMPTY("empty"),
    ERROR("error"),
    LOADING("loading"),
    ;

    companion object {
        fun fromWire(value: String?): CliCardState? = entries.firstOrNull { it.wire == value }
    }
}

data class CliCardItem(
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val badge: String? = null,
    val url: String? = null,
)

data class CliCardSection(
    val label: String? = null,
    val items: List<CliCardItem> = emptyList(),
)

data class CliCardModel(
    val kind: String,
    val state: CliCardState = CliCardState.READY,
    val title: String? = null,
    val trailing: String? = null,
    val context: String? = null,
    val total: Int = 0,
    val items: List<CliCardItem> = emptyList(),
    val sections: List<CliCardSection> = emptyList(),
    val summary: String? = null,
    val openUrl: String? = null,
    val note: String? = null,
) {
    val dataRowCount: Int
        get() = if (sections.isEmpty()) items.size else sections.sumOf { it.items.size }

    val isEmpty: Boolean get() = state == CliCardState.EMPTY || dataRowCount == 0

    val flatItems: List<CliCardItem>
        get() = if (sections.isEmpty()) items else sections.flatMap { it.items }
}

object CliCardCodec {
    const val MAX_ROWS: Int = 8

    private const val WIRE_STATE = "state"
    private const val WIRE_KIND = "kind"
    private const val WIRE_TITLE = "title"
    private const val WIRE_TRAILING = "trailing"
    private const val WIRE_CONTEXT = "context"
    private const val WIRE_TOTAL = "total"
    private const val WIRE_ITEMS = "items"
    private const val WIRE_SECTIONS = "sections"
    private const val WIRE_SUMMARY = "summary"
    private const val WIRE_OPEN_URL = "open_url"
    private const val WIRE_NOTE = "note"
    private const val WIRE_LABEL = "label"
    private const val WIRE_SUBTITLE = "subtitle"
    private const val WIRE_META = "meta"
    private const val WIRE_BADGE = "badge"
    private const val WIRE_URL = "url"

    fun encode(model: CliCardModel): JsonObject = buildJsonObject {
        put(WIRE_KIND, model.kind)
        if (model.state != CliCardState.READY) put(WIRE_STATE, model.state.wire)
        model.title?.takeIf { it.isNotBlank() }?.let { put(WIRE_TITLE, it) }
        model.trailing?.takeIf { it.isNotBlank() }?.let { put(WIRE_TRAILING, it) }
        model.context?.takeIf { it.isNotBlank() }?.let { put(WIRE_CONTEXT, it) }
        put(WIRE_TOTAL, model.total)
        val flat = if (model.sections.isEmpty()) model.items else model.items.ifEmpty { model.flatItems }
        put(WIRE_ITEMS, encodeItems(flat))
        if (model.sections.isNotEmpty()) {
            put(
                WIRE_SECTIONS,
                buildJsonArray {
                    model.sections.forEach { section ->
                        add(
                            buildJsonObject {
                                section.label?.takeIf { it.isNotBlank() }?.let { put(WIRE_LABEL, it) }
                                put(WIRE_ITEMS, encodeItems(section.items))
                            },
                        )
                    }
                },
            )
        }
        model.summary?.takeIf { it.isNotBlank() }?.let { put(WIRE_SUMMARY, it) }
        model.openUrl?.takeIf { it.isNotBlank() }?.let { put(WIRE_OPEN_URL, it) }
        model.note?.takeIf { it.isNotBlank() }?.let { put(WIRE_NOTE, it) }
    }

    fun decode(payload: JsonObject, supportedKinds: Set<String>): CliCardModel? = runCatching {
        val kind = payload.stringValue(WIRE_KIND) ?: return null
        if (kind !in supportedKinds) return null
        val flatItems = decodeItemsElement(payload[WIRE_ITEMS]) ?: return null
        val sections = decodeSections(payload[WIRE_SECTIONS]) ?: return null
        val cappedSections = capSections(sections)
        val items = if (cappedSections.isEmpty()) flatItems.take(MAX_ROWS) else cappedSections.flatMap { it.items }
        CliCardModel(
            kind = kind,
            state = payload.stringValue(WIRE_STATE)?.let(CliCardState::fromWire) ?: CliCardState.READY,
            title = payload.stringValue(WIRE_TITLE),
            trailing = payload.stringValue(WIRE_TRAILING),
            context = payload.stringValue(WIRE_CONTEXT),
            total = payload.intValue(WIRE_TOTAL) ?: items.size,
            items = items,
            sections = cappedSections,
            summary = payload.stringValue(WIRE_SUMMARY),
            openUrl = payload.stringValue(WIRE_OPEN_URL),
            note = payload.stringValue(WIRE_NOTE),
        )
    }.getOrNull()

    private fun encodeItems(items: List<CliCardItem>): JsonArray = buildJsonArray {
        items.forEach { item ->
            add(
                buildJsonObject {
                    put(WIRE_TITLE, item.title)
                    item.subtitle?.takeIf { it.isNotBlank() }?.let { put(WIRE_SUBTITLE, it) }
                    item.meta?.takeIf { it.isNotBlank() }?.let { put(WIRE_META, it) }
                    item.badge?.takeIf { it.isNotBlank() }?.let { put(WIRE_BADGE, it) }
                    item.url?.takeIf { it.isNotBlank() }?.let { put(WIRE_URL, it) }
                },
            )
        }
    }

    private fun decodeItemsElement(element: kotlinx.serialization.json.JsonElement?): List<CliCardItem>? {
        if (element == null) return emptyList()
        val array = runCatching { element.jsonArray }.getOrNull() ?: return null
        return decodeItems(array)
    }

    private fun decodeItems(array: JsonArray): List<CliCardItem> = array.mapNotNull { element ->
        val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val title = row.stringValue(WIRE_TITLE) ?: return@mapNotNull null
        CliCardItem(
            title = title,
            subtitle = row.stringValue(WIRE_SUBTITLE),
            meta = row.stringValue(WIRE_META),
            badge = row.stringValue(WIRE_BADGE),
            url = row.stringValue(WIRE_URL),
        )
    }

    private fun decodeSections(element: kotlinx.serialization.json.JsonElement?): List<CliCardSection>? {
        if (element == null) return emptyList()
        val array = runCatching { element.jsonArray }.getOrNull() ?: return null
        return array.mapNotNull { sectionElement ->
            val section = runCatching { sectionElement.jsonObject }.getOrNull() ?: return@mapNotNull null
            val itemsElement = section[WIRE_ITEMS]
            val items = if (itemsElement == null) {
                emptyList()
            } else {
                runCatching { itemsElement.jsonArray }.getOrNull()?.let(::decodeItems).orEmpty()
            }
            CliCardSection(label = section.stringValue(WIRE_LABEL), items = items)
        }
    }

    private fun capSections(sections: List<CliCardSection>): List<CliCardSection> {
        var remaining = MAX_ROWS
        val capped = mutableListOf<CliCardSection>()
        for (section in sections) {
            if (remaining <= 0) break
            val take = minOf(remaining, section.items.size)
            remaining -= take
            if (take > 0) capped += section.copy(items = section.items.take(take))
        }
        return capped
    }
}
