package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardSection
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

internal data class SysuGradesPlan(
    val semester: String?,
    val gpa: Double?,
    val credits: Double?,
    val rows: List<SysuGradeRow>,
)

internal data class SysuExamEntry(
    val name: String,
    val date: LocalDate?,
    val weekday: String?,
    val time: String?,
    val place: String?,
    val sections: String?,
    val daysUntil: Long?,
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
    val moreSlots: Int,
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
    private val DAYS_LEFT = Regex("""剩余\s*(\d+)\s*天""")
    private val DAYS_LEFT_EN = Regex("""(\d+)\s*days?\s*left""")
    private val INTEGRAL_FEE = Regex("""\d+(?:\.\d+)?\s*(?:积分|points?)\s*/\s*(?:时|hour)""")
    private val SLOT_TIME = Regex("""(\d{2}:\d{2})-(\d{2}:\d{2})""")
    private val SLOT_REMAIN = Regex("""余\s*(\d+)""")
    private val SLOT_REMAIN_EN = Regex("""\d{2}:\d{2}\s+(\d+)(?:\s+left)?\s*$""")
    private val DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val WEEKDAYS = mapOf(
        "周一" to 1,
        "周二" to 2,
        "周三" to 3,
        "周四" to 4,
        "周五" to 5,
        "周六" to 6,
        "周日" to 7,
        "周天" to 7,
        "星期一" to 1,
        "星期二" to 2,
        "星期三" to 3,
        "星期四" to 4,
        "星期五" to 5,
        "星期六" to 6,
        "星期日" to 7,
    )

    fun schedulePlan(
        model: CliCardModel,
        today: LocalDate = LocalDate.now(),
    ): SysuSchedulePlan? {
        val sections = model.sections
        if (sections.isEmpty()) return null
        val days = sections.mapIndexed { index, section ->
            val label = section.label.orEmpty().trim()
            val weekday = label.substringBefore(' ').trim()
            val dateText = label.substringAfter(' ', "").trim()
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
            SysuPlanDay(
                weekday = weekday.ifEmpty { "周${index + 1}" },
                date = date,
                dateLabel = dateText.ifEmpty { label },
                isToday = date != null && date == today,
            )
        }.sortedBy { WEEKDAYS[it.weekday] ?: Int.MAX_VALUE }
        val rawBlocks = mutableListOf<RawBlock>()
        sections.forEachIndexed { sourceIndex, section ->
            val sourceWeekday = section.label.orEmpty().trim().substringBefore(' ')
            val targetIndex = days.indexOfFirst { it.weekday == sourceWeekday }.takeIf { it >= 0 }
                ?: sourceIndex.coerceIn(days.indices)
            section.items.forEachIndexed { itemIndex, item ->
                val period = PERIOD.find(item.meta.orEmpty()) ?: PERIOD.find(item.subtitle.orEmpty()) ?: return@forEachIndexed
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
        val rows = model.sections.flatMap { it.items }.map { item -> gradeRow(item.title, item.subtitle, item.meta) }
        if (rows.isEmpty() && model.items.isEmpty()) return null
        val extraRows = model.items.map { item -> gradeRow(item.title, item.subtitle, item.meta) }
        val allRows = (rows + extraRows).distinctBy { it.name to it.code }
        if (allRows.isEmpty()) return null
        val label = model.sections.firstOrNull()?.label.orEmpty()
        val gpa = GPA.find(label)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: GPA.find(model.note.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
        val credits = CREDITS.find(model.note.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
        return SysuGradesPlan(
            semester = model.context,
            gpa = gpa,
            credits = credits,
            rows = allRows,
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
        for (section in model.sections) {
            val label = section.label.orEmpty()
            val date = DATE.find(label)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val weekday = label.replace(DATE, "").trim().ifBlank { null }
            for (item in section.items) {
                entries += examEntry(item.title, item.subtitle, date, weekday, today)
            }
        }
        model.items.forEach { item ->
            val date = DATE.find(item.meta.orEmpty())?.groupValues?.get(1)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            entries += examEntry(item.title, item.subtitle, date, null, today)
        }
        if (entries.isEmpty()) return null
        return SysuExamsPlan(
            term = model.context,
            entries = entries.sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.time.orEmpty() })),
        )
    }

    private fun examEntry(
        title: String,
        subtitle: String?,
        date: LocalDate?,
        weekday: String?,
        today: LocalDate,
    ): SysuExamEntry {
        val tokens = subtitle.orEmpty().split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val time = tokens.firstOrNull { it.contains(':') }
        val place = tokens.firstOrNull { it != time }
        return SysuExamEntry(
            name = title,
            date = date,
            weekday = weekday,
            time = time,
            place = place,
            sections = null,
            daysUntil = date?.let { ChronoUnit.DAYS.between(today, it) },
        )
    }

    fun lmsPlan(model: CliCardModel): SysuLmsPlan? {
        val rows = (model.sections.flatMap { it.items } + model.items).distinctBy { it.title to it.subtitle }
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
            )
        }
        return SysuLmsPlan(courses)
    }

    fun todoPlan(model: CliCardModel, today: LocalDate = LocalDate.now()): List<SysuTodoEntry>? {
        val rows = (model.sections.flatMap { it.items } + model.items).distinctBy { it.title to it.meta }
        if (rows.isEmpty()) return null
        return rows.map { item ->
            val due = parseDue(item.meta)
            SysuTodoEntry(
                name = item.title,
                course = item.subtitle?.takeIf { it.isNotBlank() },
                due = due,
                hasDueTime = due != null && item.meta.orEmpty().contains(':'),
                daysUntil = due?.toLocalDate()?.let { ChronoUnit.DAYS.between(today, it) },
            )
        }
    }

    private fun parseDue(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(text, DUE_FORMAT) }.getOrNull()?.let { return it }
        return runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
    }

    fun gymPlan(model: CliCardModel): SysuGymPlan? {
        val types = model.sections.mapNotNull { section -> gymType(section) }
            .ifEmpty { null }
            ?: return null
        val venueCount = model.total.takeIf { it > 0 } ?: types.sumOf { it.venues.size }
        return SysuGymPlan(
            types = types,
            venueCount = venueCount,
            window = WINDOW.find(model.note.orEmpty())?.groupValues?.get(1)?.trim(),
        )
    }

    private fun gymType(section: CliCardSection): SysuGymTypePlan? {
        val parts = section.label.orEmpty().split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val name = parts.firstOrNull() ?: return null
        val fee = parts.drop(1).firstOrNull { it.contains("积分") || it.contains("point") }
            ?.let { INTEGRAL_FEE.find(it)?.value ?: it }
        val venues = section.items.map { item -> gymVenue(item.title, item.subtitle) }
        return SysuGymTypePlan(name = name, fee = fee, venues = venues)
    }

    private fun gymVenue(name: String, subtitle: String?): SysuGymVenuePlan {
        val slots = mutableListOf<SysuGymSlotPlan>()
        var more = 0
        for (group in subtitle.orEmpty().split("；")) {
            val text = group.trim()
            if (text.isEmpty()) continue
            val date = DATE.find(text)?.value
            val groupSlots = (if (text.firstOrNull()?.isDigit() == true) text.substringAfter(' ', text) else text).split("、")
            for (slotText in groupSlots) {
                val slot = parseGymSlot(date, slotText.trim())
                if (slot != null) {
                    if (slots.size < MAX_CARD_SLOTS) slots += slot else more += 1
                }
            }
        }
        return SysuGymVenuePlan(name = name, slots = slots, moreSlots = more)
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
        val rows = (model.sections.flatMap { it.items } + model.items).distinctBy { it.title to it.meta }
        if (rows.isEmpty()) return null
        val services = rows.map { item ->
            val meta = item.meta.orEmpty()
            val subtitleTokens = item.subtitle.orEmpty().split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
            SysuNetpayServicePlan(
                name = item.title,
                status = subtitleTokens.firstOrNull(),
                address = subtitleTokens.drop(1).joinToString(" · ").ifEmpty { null },
                expiry = DATE.find(meta)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                daysLeft = DAYS_LEFT.find(meta)?.groupValues?.get(1)?.toLongOrNull()
                    ?: DAYS_LEFT_EN.find(meta)?.groupValues?.get(1)?.toLongOrNull(),
            )
        }
        val first = services.firstOrNull()
        val earliestExpiry = services.mapNotNull { it.expiry }.minOrNull()
        val earliestDaysLeft = first?.daysLeft ?: earliestExpiry?.let { ChronoUnit.DAYS.between(today, it) }
        return SysuNetpayPlan(
            identity = model.context,
            services = services,
            earliestExpiry = earliestExpiry,
            earliestDaysLeft = earliestDaysLeft,
        )
    }

    private const val MAX_CARD_SLOTS = 3
    private val WINDOW = Regex("""(?:预约窗口|Booking window)\s*(.+?)(?:\s*·|$)""")
}
