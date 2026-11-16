package io.github.whywood.deepdeep.plugin.sysu

internal data class SysuGymSlotDisplay(
    val observationLines: List<String>,
    val cardText: String?,
    val countLabel: String?,
)

internal data class SysuGymSlotEntry(
    val date: String,
    val startLabel: String,
    val endLabel: String,
    val startMinute: Int?,
    val endMinute: Int?,
    val available: Int?,
)

internal data class SysuGroupedGymSlots(
    val slices: List<SysuGymSlotEntry>,
    val aggregates: List<SysuGymSlotEntry>,
)

internal fun groupGymSlots(slots: List<SysuGymSlot>): SysuGroupedGymSlots {
    val entries = slots.mapNotNull { slot ->
        val date = SysuFormat.date(slot.date) ?: return@mapNotNull null
        val startLabel = slot.start?.trim()?.takeIf { it.isNotEmpty() }?.take(5) ?: "—"
        val endLabel = slot.end?.trim()?.takeIf { it.isNotEmpty() }?.take(5) ?: "—"
        SysuGymSlotEntry(
            date = date,
            startLabel = startLabel,
            endLabel = endLabel,
            startMinute = minutesOf(startLabel),
            endMinute = minutesOf(endLabel),
            available = slot.available.intOrNull(),
        )
    }.distinctBy { Triple(it.date, it.startLabel, it.endLabel) }
    val aggregates = entries.filter { candidate ->
        val start = candidate.startMinute ?: return@filter false
        val end = candidate.endMinute ?: return@filter false
        val duration = end - start
        if (duration <= 0) return@filter false
        entries.any { other ->
            other !== candidate &&
                other.startMinute != null &&
                other.endMinute != null &&
                other.startMinute >= start &&
                other.endMinute <= end &&
                other.endMinute - other.startMinute in 1 until duration
        }
    }.toSet()
    val comparator = compareBy<SysuGymSlotEntry>({ it.startMinute ?: Int.MAX_VALUE }, { it.endMinute ?: Int.MAX_VALUE })
    return SysuGroupedGymSlots(
        slices = entries.filterNot { it in aggregates }.sortedWith(comparator),
        aggregates = entries.filter { it in aggregates }.sortedWith(comparator),
    )
}

private fun minutesOf(label: String): Int? {
    val parts = label.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..24 || minute !in 0..59) return null
    return hour * 60 + minute
}

internal object SysuGymDisplay {
    fun slots(venue: SysuGymVenue, strings: SysuStrings): SysuGymSlotDisplay {
        val byDate = linkedMapOf<String, MutableList<SysuGymSlot>>()
        for (slot in venue.slots) {
            val date = SysuFormat.date(slot.date) ?: continue
            byDate.getOrPut(date) { mutableListOf() } += slot
        }
        if (byDate.isEmpty()) {
            return SysuGymSlotDisplay(
                observationLines = listOf(strings.text("没有可约时段", "No available slots")),
                cardText = null,
                countLabel = null,
            )
        }
        val observation = mutableListOf<String>()
        val card = mutableListOf<String>()
        var total = 0
        for ((date, slots) in byDate) {
            val grouped = groupGymSlots(slots)
            total += grouped.slices.size + grouped.aggregates.size
            observation += observationLine(date, grouped, strings)
            card += cardLine(date, grouped, strings)
        }
        return SysuGymSlotDisplay(
            observationLines = observation,
            cardText = card.joinToString("；").ifEmpty { null },
            countLabel = strings.text("共 $total 个", "$total"),
        )
    }

    private fun observationLine(
        date: String,
        grouped: SysuGroupedGymSlots,
        strings: SysuStrings,
    ): String {
        val parts = mutableListOf<String>()
        grouped.slices.forEach { entry ->
            parts += "${entry.startLabel}-${entry.endLabel} ${strings.text("余", "")}${entry.available ?: "—"}"
        }
        grouped.aggregates.forEach { entry ->
            parts += strings.text(
                "长时段 ${entry.startLabel}-${entry.endLabel} 余${entry.available ?: "—"}",
                "long window ${entry.startLabel}-${entry.endLabel} ${entry.available ?: "—"} left",
            )
        }
        return "$date：" + parts.joinToString(strings.text("、", ", "))
    }

    private fun cardLine(
        date: String,
        grouped: SysuGroupedGymSlots,
        strings: SysuStrings,
    ): String {
        val parts = mutableListOf<String>()
        grouped.slices.forEach { entry ->
            parts += "${entry.startLabel}-${entry.endLabel} ${strings.text("余", "")}${entry.available ?: "—"}"
        }
        grouped.aggregates.forEach { entry ->
            parts += strings.text(
                "长时段 ${entry.startLabel}-${entry.endLabel} 余${entry.available ?: "—"}",
                "long window ${entry.startLabel}-${entry.endLabel} ${entry.available ?: "—"} left",
            )
        }
        return "$date ${parts.joinToString(strings.text("、", ", "))}".trimEnd()
    }
}
