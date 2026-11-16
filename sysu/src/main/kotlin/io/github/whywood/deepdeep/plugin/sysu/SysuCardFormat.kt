package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardSection
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal data class SysuPlanDay(
    val weekday: String,
    val date: LocalDate?,
    val dateLabel: String,
    val isToday: Boolean,
)

internal data class SysuPlanBlock(
    val dayIndex: Int,
    val start: Int,
    val end: Int,
    val lane: Int,
    val lanes: Int,
    val title: String,
    val subtitle: String?,
    val isExam: Boolean,
)

internal data class SysuSchedulePlan(
    val days: List<SysuPlanDay>,
    val blocks: List<SysuPlanBlock>,
    val minPeriod: Int,
    val maxPeriod: Int,
    val courseCount: Int,
    val todayCount: Int,
    val hasExam: Boolean,
)

internal data class SysuGradeRow(
    val name: String,
    val code: String?,
    val credits: Double?,
    val score: String?,
    val point: Double?,
)

internal data class SysuGradeGroup(
    val label: String,
    val gpa: Double?,
    val credits: Double?,
    val rows: List<SysuGradeRow>,
)

internal data class SysuGradesPlan(
    val semester: String?,
    val gpa: Double?,
    val credits: Double?,
    val groups: List<SysuGradeGroup>,
) {
    val rows: List<SysuGradeRow> get() = groups.flatMap { it.rows }
}

internal data class SysuExamEntry(
    val name: String,
    val date: LocalDate?,
    val weekday: String?,
    val time: String?,
    val place: String?,
    val sections: String?,
    val daysUntil: Long?,
    val details: List<String>,
)

internal data class SysuExamsPlan(
    val term: String?,
    val entries: List<SysuExamEntry>,
)

internal data class SysuLmsCoursePlan(
    val name: String,
    val courseId: String?,
    val progress: Int?,
    val end: LocalDate?,
    val details: List<String>,
)

internal data class SysuLmsPlan(
    val courses: List<SysuLmsCoursePlan>,
) {
    val averageProgress: Int?
        get() {
            val values = courses.mapNotNull { it.progress }
            return if (values.isEmpty()) null else values.sum() / values.size
        }
}

internal data class SysuTodoEntry(
    val name: String,
    val course: String?,
    val due: LocalDateTime?,
    val hasDueTime: Boolean,
    val daysUntil: Long?,
    val details: List<String>,
    val url: String?,
)

internal data class SysuGymSlotPlan(
    val date: String?,
    val time: String,
    val remaining: Int?,
    val longWindow: Boolean,
)

internal data class SysuGymVenuePlan(
    val name: String,
    val slots: List<SysuGymSlotPlan>,
)

internal data class SysuGymTypePlan(
    val name: String,
    val fee: String?,
    val venues: List<SysuGymVenuePlan>,
)

internal data class SysuGymPlan(
    val types: List<SysuGymTypePlan>,
    val venueCount: Int,
    val window: String?,
)

internal data class SysuNetpayServicePlan(
    val name: String,
    val status: String?,
    val address: String?,
    val expiry: LocalDate?,
    val daysLeft: Long?,
    val details: List<String>,
)

internal data class SysuNetpayPlan(
    val identity: String?,
    val services: List<SysuNetpayServicePlan>,
    val earliestExpiry: LocalDate?,
    val earliestDaysLeft: Long?,
)

internal object SysuCardFormat {
    private val PERIOD = Regex("""第\s*(\d+)(?:\s*[-–~]\s*(\d+))?\s*节""")
    private val GPA = Regex("""(?:加权绩点|GPA)\s*([\d.]+)""")
    private val CREDITS = Regex("""([\d.]+)\s*(?:学分|credits?)""")
    private val PERCENT = Regex("""(\d+)\s*%""")
    private val DATE = Regex("""(\d{4}-\d{2}-\d{2})""")
    private val ENGLISH_DATE = Regex("""([A-Za-z]{3,9})\.?\s+(\d{1,2}),\s*(\d{4})""")
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val DAYS_LEFT = Regex("""剩余\s*(\d+)\s*天""")
    private val DAYS_LEFT_EN = Regex("""(\d+)\s*days?\s*left""")
    private val SLOT_TIME = Regex("""(\d{2}:\d{2})-(\d{2}:\d{2})""")
    private val SLOT_REMAIN = Regex("""余\s*(\d+)""")
    private val SLOT_REMAIN_EN = Regex("""\d{2}:\d{2}\s+(\d+)(?:\s+left)?\s*$""")
    private val DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val WINDOW = Regex("""(?:预约窗口|Booking window)\s*(.+?)(?:\s*·|$)""")
    private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun schedulePlan(
        model: CliCardModel,
        today: LocalDate = LocalDate.now(),
    ): SysuSchedulePlan? {
        val sections = model.sections
        if (sections.isEmpty()) return null
        val parsed = sections.map { section ->
            val label = section.label.orEmpty().trim()
            val weekday = label.substringBefore(' ').trim()
            val dateText = label.substringAfter(' ', "").trim()
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
            Triple(label, weekday, date)
        }
        val rangeStart = DATE.find(model.note.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val anchor = rangeStart
            ?: parsed.mapNotNull { it.third }.minOrNull()
            ?: today
        val weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            SysuPlanDay(
                weekday = WEEKDAY_NAMES[date.dayOfWeek.value - 1],
                date = date,
                dateLabel = date.toString(),
                isToday = date == today,
            )
        }
        val rawBlocks = mutableListOf<RawBlock>()
        sections.forEachIndexed { sourceIndex, section ->
            val (label, sourceWeekday, sourceDate) = parsed[sourceIndex]
            val targetIndex = when {
                sourceDate != null -> days.indexOfFirst { it.date == sourceDate }
                else -> days.indexOfFirst { it.weekday == sourceWeekday }
            }
            if (targetIndex < 0) return@forEachIndexed
            section.items.forEachIndexed { itemIndex, item ->
                val period = PERIOD.find(item.meta.orEmpty()) ?: PERIOD.find(item.subtitle.orEmpty())
                    ?: return@forEachIndexed
                val start = period.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                val end = period.groupValues[2].toIntOrNull()?.coerceAtLeast(start) ?: start
                rawBlocks += RawBlock(
                    dayIndex = targetIndex,
                    start = start,
                    end = end,
                    order = sourceIndex * 100 + itemIndex,
                    title = item.title,
                    subtitle = item.subtitle,
                    isExam = item.subtitle.orEmpty().startsWith("考试") ||
                        item.subtitle.orEmpty().startsWith("Exam"),
                )
            }
        }
        if (rawBlocks.isEmpty()) return null
        val blocks = assignLanes(rawBlocks)
        val minPeriod = blocks.minOf { it.start }
        val maxPeriod = blocks.maxOf { it.end }
        val todayIndex = days.indexOfFirst { it.isToday }
        val todayCount = if (todayIndex >= 0) blocks.count { it.dayIndex == todayIndex } else 0
        return SysuSchedulePlan(
            days = days,
            blocks = blocks,
            minPeriod = minPeriod,
            maxPeriod = maxPeriod,
            courseCount = model.total.takeIf { it > 0 } ?: sections.sumOf { it.items.size },
            todayCount = todayCount,
            hasExam = blocks.any { it.isExam },
        )
    }

    private data class RawBlock(
        val dayIndex: Int,
        val start: Int,
        val end: Int,
        val order: Int,
        val title: String,
        val subtitle: String?,
        val isExam: Boolean,
    )

    private fun assignLanes(raw: List<RawBlock>): List<SysuPlanBlock> {
        val result = mutableListOf<SysuPlanBlock>()
        raw.groupBy { it.dayIndex }.forEach { (_, dayBlocks) ->
            val sorted = dayBlocks.sortedWith(compareBy({ it.start }, { it.end }, { it.order }))
            val cluster = mutableListOf<RawBlock>()
            var clusterEnd = Int.MIN_VALUE
            fun flush() {
                if (cluster.isEmpty()) return
                val laneEnds = mutableListOf<Int>()
                val lanes = mutableMapOf<RawBlock, Int>()
                for (block in cluster.sortedBy { it.start }) {
                    val lane = laneEnds.indexOfFirst { it < block.start }
                    if (lane >= 0) {
                        laneEnds[lane] = block.end
                        lanes[block] = lane
                    } else {
                        lanes[block] = laneEnds.size
                        laneEnds += block.end
                    }
                }
                val laneCount = laneEnds.size
                cluster.forEach { block ->
                    result += SysuPlanBlock(
                        dayIndex = block.dayIndex,
                        start = block.start,
                        end = block.end,
                        lane = lanes[block] ?: 0,
                        lanes = laneCount,
                        title = block.title,
                        subtitle = block.subtitle,
                        isExam = block.isExam,
                    )
                }
                cluster.clear()
            }
            for (block in sorted) {
                if (cluster.isNotEmpty() && block.start > clusterEnd) {
                    flush()
                    clusterEnd = Int.MIN_VALUE
                }
                cluster += block
                clusterEnd = maxOf(clusterEnd, block.end)
            }
            flush()
        }
        return result
    }

    fun gradesPlan(model: CliCardModel): SysuGradesPlan? {
        val groups = mutableListOf<SysuGradeGroup>()
        if (model.sections.isNotEmpty()) {
            for (section in model.sections) {
                val label = section.label.orEmpty()
                groups += SysuGradeGroup(
                    label = label,
                    gpa = GPA.find(label)?.groupValues?.get(1)?.toDoubleOrNull(),
                    credits = CREDITS.find(label)?.groupValues?.get(1)?.toDoubleOrNull(),
                    rows = section.items.map { gradeRow(it.title, it.subtitle, it.meta) },
                )
            }
        } else if (model.items.isNotEmpty()) {
            groups += SysuGradeGroup(
                label = model.context.orEmpty(),
                gpa = GPA.find(model.note.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull(),
                credits = CREDITS.find(model.note.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull(),
                rows = model.items.map { gradeRow(it.title, it.subtitle, it.meta) },
            )
        }
        if (groups.all { it.rows.isEmpty() }) return null
        val first = groups.firstOrNull()
        return SysuGradesPlan(
            semester = model.context,
            gpa = first?.gpa ?: GPA.find(model.note.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull(),
            credits = first?.credits ?: CREDITS.find(model.note.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull(),
            groups = groups,
        )
    }

    private fun gradeRow(title: String, subtitle: String?, meta: String?): SysuGradeRow {
        val tokens = meta.orEmpty().split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val credits = tokens.firstOrNull { it.endsWith("学分") || it.endsWith("credits") }
            ?.takeWhile { it.isDigit() || it == '.' }?.toDoubleOrNull()
        val point = tokens.firstOrNull { it.startsWith("绩点") || it.startsWith("GP") }
            ?.substringAfter(' ')?.toDoubleOrNull()
        val score = tokens.firstOrNull { token ->
            !token.endsWith("学分") && !token.endsWith("credits") &&
                !token.startsWith("绩点") && !token.startsWith("GP")
        }
        return SysuGradeRow(
            name = title,
            code = subtitle?.takeIf { it.isNotBlank() },
            credits = credits,
            score = score,
            point = point,
        )
    }

    fun examsPlan(
        model: CliCardModel,
        today: LocalDate = LocalDate.now(),
    ): SysuExamsPlan? {
        val entries = mutableListOf<SysuExamEntry>()
        if (model.sections.isNotEmpty()) {
            for (section in model.sections) {
                val label = section.label.orEmpty()
                val date = DATE.find(label)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val weekday = label.replace(DATE, "").trim().ifBlank { null }
                for (item in section.items) {
                    entries += examEntry(item, date, weekday, today)
                }
            }
        } else {
            model.items.forEach { item ->
                val date = DATE.find(item.meta.orEmpty())?.groupValues?.get(1)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                entries += examEntry(item, date, null, today)
            }
        }
        val unique = entries.distinctBy { Triple(it.name, it.date, it.time) }
        if (unique.isEmpty()) return null
        return SysuExamsPlan(
            term = model.context,
            entries = unique.sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.time.orEmpty() })),
        )
    }

    private fun examEntry(
        item: CliCardItem,
        date: LocalDate?,
        weekday: String?,
        today: LocalDate,
    ): SysuExamEntry {
        val tokens = item.subtitle.orEmpty().split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val time = tokens.firstOrNull { it.contains(':') }
        val place = tokens.firstOrNull { it != time }
        return SysuExamEntry(
            name = item.title,
            date = date,
            weekday = weekday,
            time = time,
            place = place,
            sections = item.meta?.takeIf { it.isNotBlank() },
            daysUntil = date?.let { ChronoUnit.DAYS.between(today, it) },
            details = bodyLines(item.body),
        )
    }

    fun lmsPlan(model: CliCardModel): SysuLmsPlan? {
        val rows = rowItems(model)
        if (rows.isEmpty()) return null
        val courses = rows.map { item ->
            val meta = item.meta.orEmpty()
            SysuLmsCoursePlan(
                name = item.title,
                courseId = item.subtitle?.let { subtitle ->
                    Regex("""\d{2,}""").find(subtitle)?.value
                },
                progress = PERCENT.find(meta)?.groupValues?.get(1)?.toIntOrNull(),
                end = DATE.find(meta)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                details = bodyLines(item.body),
            )
        }
        return SysuLmsPlan(courses)
    }

    fun todoPlan(model: CliCardModel, today: LocalDate = LocalDate.now()): List<SysuTodoEntry>? {
        val rows = rowItems(model)
        if (rows.isEmpty()) return null
        return rows.map { item ->
            val due = parseDue(item.meta)
            SysuTodoEntry(
                name = item.title,
                course = item.subtitle?.takeIf { it.isNotBlank() },
                due = due,
                hasDueTime = due != null && item.meta.orEmpty().contains(':'),
                daysUntil = due?.toLocalDate()?.let { ChronoUnit.DAYS.between(today, it) },
                details = bodyLines(item.body),
                url = item.url,
            )
        }
    }

    private fun parseDue(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(text, DUE_FORMAT) }.getOrNull()?.let { return it }
        runCatching { Instant.parse(text) }.getOrNull()?.let { return LocalDateTime.ofInstant(it, ZONE) }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let { return it.atZoneSameInstant(ZONE).toLocalDateTime() }
        return runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
    }

    fun gymPlan(model: CliCardModel): SysuGymPlan? {
        val types = model.sections.mapNotNull { section -> gymType(section) }
        if (types.isEmpty()) return null
        val venueCount = model.total.takeIf { it > 0 } ?: types.sumOf { it.venues.size }
        return SysuGymPlan(
            types = types,
            venueCount = venueCount,
            window = WINDOW.find(model.note.orEmpty())?.groupValues?.get(1)?.trim(),
        )
    }

    private fun gymType(section: CliCardSection): SysuGymTypePlan? {
        val label = section.label.orEmpty().trim()
        if (label.isEmpty()) return null
        val parts = label.split("｜").map { it.trim() }.filter { it.isNotEmpty() }
        val legacy = label.split(" · ", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        val name = parts.firstOrNull() ?: return null
        val fee = when {
            parts.size > 1 -> parts.drop(1).joinToString(" · ")
            legacy.size > 1 && (legacy[1].contains("积分") || legacy[1].contains("points") || legacy[1].contains("现金")) -> legacy[1]
            else -> null
        }
        val venues = section.items.map { item -> gymVenue(item.title, item.subtitle) }
        return SysuGymTypePlan(
            name = name,
            fee = fee,
            venues = venues,
        )
    }

    private fun gymVenue(name: String, subtitle: String?): SysuGymVenuePlan {
        val slots = mutableListOf<SysuGymSlotPlan>()
        for (group in subtitle.orEmpty().split("；")) {
            val text = group.trim()
            if (text.isEmpty()) continue
            val date = DATE.find(text)?.value
            val groupSlots = (if (text.firstOrNull()?.isDigit() == true) text.substringAfter(' ', text) else text).split("、")
            for (slotText in groupSlots) {
                val slot = parseGymSlot(date, slotText.trim())
                if (slot != null) slots += slot
            }
        }
        return SysuGymVenuePlan(name = name, slots = slots)
    }

    private fun parseGymSlot(date: String?, text: String): SysuGymSlotPlan? {
        if (text.isEmpty()) return null
        val time = SLOT_TIME.find(text)?.value ?: return null
        val longWindow = text.contains("长时段") || text.contains("全天") || text.contains("long window")
        return SysuGymSlotPlan(
            date = date,
            time = time,
            remaining = SLOT_REMAIN.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: SLOT_REMAIN_EN.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            longWindow = longWindow,
        )
    }

    fun netpayPlan(model: CliCardModel, today: LocalDate = LocalDate.now()): SysuNetpayPlan? {
        val rows = rowItems(model)
        if (rows.isEmpty()) return null
        val services = rows.map { item ->
            val meta = item.meta.orEmpty()
            val subtitleTokens = item.subtitle.orEmpty().split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
            SysuNetpayServicePlan(
                name = item.title,
                status = subtitleTokens.firstOrNull(),
                address = subtitleTokens.drop(1).joinToString(" · ").ifEmpty { null },
                expiry = parseExpiry(meta) ?: parseExpiry(item.subtitle),
                daysLeft = DAYS_LEFT.find(meta)?.groupValues?.get(1)?.toLongOrNull()
                    ?: DAYS_LEFT_EN.find(meta)?.groupValues?.get(1)?.toLongOrNull(),
                details = bodyLines(item.body),
            )
        }
        val earliestExpiry = services.mapNotNull { it.expiry }.minOrNull()
        val earliestDaysLeft = services.firstOrNull()?.daysLeft ?: earliestExpiry?.let { ChronoUnit.DAYS.between(today, it) }
        return SysuNetpayPlan(
            identity = model.context,
            services = services,
            earliestExpiry = earliestExpiry,
            earliestDaysLeft = earliestDaysLeft,
        )
    }

    private fun parseExpiry(value: String?): LocalDate? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        DATE.find(text)?.groupValues?.get(1)?.let { return runCatching { LocalDate.parse(it) }.getOrNull() }
        val match = ENGLISH_DATE.find(text) ?: return null
        val month = MONTHS[match.groupValues[1].take(3).lowercase()] ?: return null
        return runCatching {
            LocalDate.of(match.groupValues[3].toInt(), month, match.groupValues[2].toInt())
        }.getOrNull()
    }

    private fun rowItems(model: CliCardModel): List<CliCardItem> =
        if (model.sections.isEmpty()) model.items else model.sections.flatMap { it.items }

    private fun bodyLines(body: String?): List<String> = body.orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
