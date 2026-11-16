package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardSection
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun interface SysuStrings {
    fun text(zh: String, en: String): String
}

internal val ChineseStrings: SysuStrings = SysuStrings { zh, _ -> zh }

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

internal val SYSU_CARD_KINDS: Set<String> = SysuCardKind.entries.map { it.wire }.toSet()

internal data class SysuRendered(
    val summary: String,
    val text: String,
    val card: CliCardModel?,
    val isError: Boolean = false,
)

internal object SysuObservation {
    fun render(kind: SysuCommandKind, payload: JsonElement, strings: SysuStrings): SysuRendered? = when (kind) {
        SysuCommandKind.Schedule -> schedule(payload, strings)
        SysuCommandKind.Scores -> scores(payload, strings)
        SysuCommandKind.Exams -> exams(payload, strings)
        SysuCommandKind.Credits -> credits(payload, strings)
        SysuCommandKind.LmsCourses -> lmsCourses(payload, strings)
        SysuCommandKind.LmsTodo -> lmsTodo(payload, strings)
        SysuCommandKind.GymSlots -> gymSlots(payload, strings)
        SysuCommandKind.Netpay -> netpay(payload, strings)
    }

    private fun schedule(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.schedule(payload) ?: return null
        val term = data.term.orDash()
        val week = data.week.scalarText()
        val start = SysuFormat.date(data.startTime)
        val end = SysuFormat.date(data.endTime)
        val title = strings.text("课表", "Timetable")
        val range = when {
            start != null && end != null && start != end -> "$start ~ $end"
            start != null -> start
            else -> null
        }
        val subtitle = listOfNotNull(
            term.takeIf { it != "—" },
            week?.let { strings.text("第 $it 周", "Week $it") },
        ).joinToString(" · ")
        val header = buildString {
            append(strings.text("课表", "Timetable"))
            append(" · ").append(term)
            if (week != null) append(" · ").append(strings.text("第 $week 周", "Week $week"))
            if (range != null) append("（").append(range).append("）")
        }
        if (data.days.isEmpty()) {
            val body = strings.text("本周没有课程。", "No classes this week.")
            return SysuRendered(
                summary = cardSummary(title, null, subtitle),
                text = "$header\n$body",
                card = emptyCard(SysuCardKind.Schedule, title, subtitle, body),
            )
        }
        val lines = mutableListOf(header)
        val sections = mutableListOf<CliCardSection>()
        var rowCount = 0
        for (day in data.days) {
            val date = SysuFormat.date(day.date)
            val weekday = SysuFormat.weekday(day.weekday)
            val dayLabel = listOfNotNull(weekday, date).joinToString(" ")
            lines += ""
            lines += "$dayLabel（${day.items.size} ${strings.text("门", "classes")}）"
            val rows = mutableListOf<CliCardItem>()
            for (item in day.items) {
                val exam = item.type == "exam"
                val name = item.name.orDash()
                val detail = listOfNotNull(
                    item.teacher?.takeIf { it.isNotBlank() },
                    SysuFormat.place(item.place),
                ).joinToString(" · ")
                val cardDetail = if (exam) {
                    listOf(strings.text("考试", "Exam"), detail).filter { it.isNotBlank() }.joinToString(" · ")
                } else {
                    detail
                }
                val time = item.time?.takeIf { it.isNotBlank() }
                val sectionsLabel = SysuFormat.sections(item.sections)
                lines += "  - ${if (exam) strings.text("考试 ", "Exam ") else ""}" +
                    "${sectionsLabel ?: strings.text("节次未知", "No period")} " +
                    "$name${if (detail.isNotEmpty()) "｜$detail" else ""}" +
                    (if (time != null) "｜$time" else "")
                rows += CliCardItem(
                    title = name,
                    subtitle = cardDetail.ifEmpty { null },
                    meta = sectionsLabel ?: time,
                )
                rowCount += 1
            }
            sections += CliCardSection(label = dayLabel, items = rows)
        }
        val trailing = strings.text("$rowCount 门", "$rowCount classes")
        return SysuRendered(
            summary = cardSummary(title, trailing, subtitle),
            text = lines.joinToString("\n").trimEnd(),
            card = readyCard(
                kind = SysuCardKind.Schedule,
                title = title,
                trailing = trailing,
                context = subtitle,
                total = rowCount,
                sections = sections,
                note = range,
            ),
        )
    }

    private fun scores(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val semesters = SysuJson.scores(payload) ?: return null
        val title = strings.text("成绩", "Grades")
        if (semesters.isEmpty()) {
            val body = strings.text("还没有任何成绩记录。", "No grades recorded yet.")
            return SysuRendered(body, body, emptyCard(SysuCardKind.Grades, title, null, body))
        }
        val lines = mutableListOf<String>()
        var rowCount = 0
        val sections = mutableListOf<CliCardSection>()
        semesters.forEachIndexed { index, semester ->
            val gpa = SysuFormat.decimal(semester.gpa, 3)
            val credit = SysuFormat.decimal(semester.credit, 1)
            val count = semester.count.scalarText() ?: semester.courses.size.toString()
            if (index > 0) lines += ""
            lines += buildString {
                append(strings.text("成绩", "Grades")).append(" · ").append(semester.semester.orDash())
                append(" · ").append(count).append(strings.text(" 门", " courses"))
                if (credit != null) append(" · ").append(credit).append(strings.text(" 学分", " credits"))
                if (gpa != null) append(" · ").append(strings.text("加权绩点 ", "GPA ")).append(gpa)
                append(strings.text("（自算口径）", " (self-calculated)"))
            }
            val rows = mutableListOf<CliCardItem>()
            for (course in semester.courses) {
                val name = course.scoCourseName.orDash()
                val code = course.scoCourseNumber?.takeIf { it.isNotBlank() }
                val courseCredit = SysuFormat.decimal(course.scoCredit, 1)
                val score = course.scoFinalScore.scalarText()
                val point = SysuFormat.decimal(course.scoPoint, 1)
                lines += "  - $name${if (code != null) "（$code）" else ""}" +
                    (if (courseCredit != null) " $courseCredit${strings.text(" 学分", " credits")}" else "") +
                    (if (score != null) " $score" else "") +
                    " " + (if (point != null) strings.text("绩点 $point", "grade point $point") else strings.text("绩点 —", "grade point —"))
                rows += CliCardItem(
                    title = name,
                    subtitle = code,
                    meta = listOfNotNull(
                        score,
                        courseCredit?.let { strings.text("$it 学分", "$it credits") },
                        point?.let { strings.text("绩点 $it", "GP $it") },
                    ).joinToString(" · ").ifEmpty { null },
                )
                rowCount += 1
            }
            sections += CliCardSection(
                label = listOfNotNull(
                    semester.semester,
                    credit?.let { strings.text("$it 学分", "$it credits") },
                    gpa?.let { strings.text("加权绩点 $it", "GPA $it") },
                ).joinToString(" · "),
                items = rows,
            )
        }
        val first = semesters.first()
        val count = first.count.scalarText() ?: first.courses.size.toString()
        val trailing = strings.text("$count 门", "$count courses")
        val footer = listOfNotNull(
            SysuFormat.decimal(first.credit, 1)?.let { strings.text("$it 学分", "$it credits") },
            SysuFormat.decimal(first.gpa, 3)?.let { strings.text("加权绩点 $it（自算）", "GPA $it (self-calculated)") },
        ).joinToString(" · ").ifEmpty { null }
        return SysuRendered(
            summary = cardSummary(title, trailing, first.semester),
            text = lines.joinToString("\n").trimEnd(),
            card = readyCard(
                kind = SysuCardKind.Grades,
                title = title,
                trailing = trailing,
                context = first.semester,
                total = rowCount,
                sections = sections,
                note = footer,
            ),
        )
    }

    private fun exams(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.exams(payload) ?: return null
        val term = data.term.orDash()
        val title = strings.text("考试安排", "Exams")
        val count = data.exams.size
        val header = "${strings.text("考试安排", "Exams")} · $term · ${strings.text("共 $count 场", "$count exams")}"
        if (data.exams.isEmpty()) {
            val body = strings.text("$term 还没有已排期的考试。", "No exams scheduled for $term yet.")
            return SysuRendered("$header\n$body", "$header\n$body", emptyCard(SysuCardKind.Exams, title, term, body))
        }
        val lines = mutableListOf(header)
        val byDate = linkedMapOf<String, MutableList<SysuExam>>()
        for (exam in data.exams) {
            val date = SysuFormat.date(exam.date) ?: strings.text("未排期", "Unscheduled")
            byDate.getOrPut(date) { mutableListOf() } += exam
        }
        val sections = mutableListOf<CliCardSection>()
        var rowCount = 0
        for ((date, exams) in byDate) {
            val weekday = SysuFormat.weekday(exams.first().weekday)
            val label = listOfNotNull(date, weekday).joinToString(" ")
            lines += ""
            lines += label
            val rows = mutableListOf<CliCardItem>()
            for (exam in exams) {
                val time = exam.time?.takeIf { it.isNotBlank() }
                val place = SysuFormat.place(exam.place)
                val sectionsLabel = SysuFormat.sections(exam.sections)
                val teacher = exam.teacher?.takeIf { it.isNotBlank() }
                val week = exam.week.scalarText()
                val weekLabel = week?.let { strings.text("第 $it 周", "week $it") }
                val typeLabel = exam.type?.takeIf { it.isNotBlank() }?.let { raw ->
                    if (raw == "exam") strings.text("考试", "exam") else raw
                }
                lines += "  - " + listOfNotNull(
                    time,
                    exam.name.orDash(),
                    place,
                    sectionsLabel,
                    teacher?.let { strings.text("教师 $it", "teacher $it") },
                    weekLabel,
                    typeLabel,
                ).joinToString("｜")
                rows += CliCardItem(
                    title = exam.name.orDash(),
                    subtitle = listOfNotNull(time, place).joinToString(" · ").ifEmpty { null },
                    meta = sectionsLabel,
                    body = bodyLines(
                        strings.text("教师", "Teacher") to teacher,
                        strings.text("周次", "Week") to weekLabel,
                        strings.text("类型", "Type") to typeLabel,
                    ),
                )
                rowCount += 1
            }
            sections += CliCardSection(label = label, items = rows)
        }
        val trailing = strings.text("$count 场", "$count")
        return SysuRendered(
            summary = cardSummary(title, trailing, term),
            text = lines.joinToString("\n").trimEnd(),
            card = readyCard(
                kind = SysuCardKind.Exams,
                title = title,
                trailing = trailing,
                context = term,
                total = rowCount,
                sections = sections,
            ),
        )
    }

    private fun credits(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val plans = SysuJson.credits(payload) ?: return null
        val lines = mutableListOf<String>()
        lines += strings.text("毕业学分 · 共 ${plans.size} 个培养方案", "Credits · ${plans.size} plans")
        if (plans.size > 1) {
            lines += strings.text(
                "（多个培养方案并列，源数据没有标注主辅关系，按「专业代码 + 培养类别」分别核对）",
                "(Plans are listed side by side; the source does not mark a primary plan. Check each plan by programme code and category.)",
            )
        }
        for ((index, plan) in plans.withIndex()) {
            lines += ""
            lines += buildString {
                append(strings.text("培养方案 ${index + 1}", "Plan ${index + 1}")).append("：")
                append(plan.grade?.takeIf { it.isNotBlank() }?.let { "$it ${strings.text("级", "grade")}" } ?: strings.text("年级未知", "unknown grade"))
                append(" · ").append(strings.text("专业代码 ", "programme code ")).append(plan.professionNumber.orDash())
                plan.gradeProfessionCode?.takeIf { it.isNotBlank() }?.let {
                    append(" · ").append(strings.text("年级专业代码 ", "grade programme code ")).append(it)
                }
                plan.trainCateCode?.takeIf { it.isNotBlank() }?.let {
                    append(" · ").append(strings.text("培养类别 ", "category ")).append(it)
                }
                plan.teachPlanNumber?.takeIf { it.isNotBlank() }?.let {
                    append(" · ").append(strings.text("教学计划 ", "teaching plan ")).append(it)
                }
                SysuFormat.decimal(plan.publicMinCredit, 0)?.let {
                    append(" · ").append(strings.text("公共课下限 ", "public minimum ")).append(it)
                }
            }
            val finish = SysuFormat.decimal(plan.finishCredit, 1) ?: "0.0"
            val total = SysuFormat.decimal(plan.minGraduationCredit, 0).orDash()
            val rate = SysuFormat.decimal(plan.finishCreditRate, 1).orDash()
            lines += "  " + strings.text(
                "毕业学分 $finish / $total（$rate%）",
                "Graduation credits $finish / $total ($rate%)",
            )
            for (category in creditsCategories(plan, strings)) lines += "  - $category"
        }
        val text = lines.joinToString("\n").trimEnd()
        return SysuRendered(text, text, card = null)
    }

    private fun creditsCategories(plan: SysuCreditPlan, strings: SysuStrings): List<String> {
        val categories = listOf(
            Triple(strings.text("专业必修", "Major required"), plan.majorMinCredit, plan.majorFinishCredit),
            Triple(strings.text("专业选修", "Major elective"), plan.majorSelectMinCredit, plan.majorSelectFinishCredit),
            Triple(strings.text("公共必修", "Public required"), plan.publicMinGraduationCredit, plan.publicFinishCredit),
            Triple(strings.text("公共选修", "Public elective"), plan.publicSelectMinCredit, plan.publicSelectFinishCredit),
            Triple(strings.text("荣誉课程", "Honours"), plan.honorCourseMinCredit, plan.honorCourseFinishCredit),
        )
        return categories.mapNotNull { (label, minimum, finished) ->
            val total = SysuFormat.decimal(minimum, 0)?.toDoubleOrNull() ?: 0.0
            if (total <= 0.0) return@mapNotNull null
            val done = SysuFormat.decimal(finished, 1) ?: "0.0"
            "$label $done / ${SysuFormat.decimal(minimum, 0)}"
        }
    }

    private fun lmsCourses(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val courses = SysuJson.lmsCourses(payload) ?: return null
        val title = strings.text("在线教学", "LMS")
        if (courses.isEmpty()) {
            val body = strings.text("没有选课记录（或者课程都隐藏了）。", "No enrolled courses (or all are hidden).")
            return SysuRendered(body, body, emptyCard(SysuCardKind.Lms, title, null, body))
        }
        val lines = mutableListOf("${strings.text("在线教学 · 共", "LMS ·")} ${courses.size} ${strings.text("门课程", "courses")}")
        val rows = mutableListOf<CliCardItem>()
        for (course in courses) {
            val id = course.id.scalarText()
            val name = course.name.orDash()
            val progress = course.progress.scalarText()?.let { "$it%" } ?: "—"
            val start = SysuFormat.date(course.start)
            val end = SysuFormat.date(course.end)
            val short = course.short?.takeIf { it.isNotBlank() }
            val hidden = course.hidden == true
            lines += "  - ${id?.let { "$it " } ?: ""}$name｜${strings.text("进度", "progress")} $progress｜" +
                "${strings.text("结课", "ends")} ${end.orDash()}" +
                (start?.let { "｜${strings.text("开课", "starts")} $it" } ?: "") +
                (short?.let { "｜${strings.text("课程代码", "code")} $it" } ?: "") +
                (if (hidden) "｜${strings.text("已隐藏", "hidden")}" else "")
            rows += CliCardItem(
                title = name,
                subtitle = id?.let { strings.text("课程 ID $it", "Course ID $it") },
                meta = listOfNotNull(progress, end?.let { strings.text("结课 $it", "ends $it") }).joinToString(" · "),
                body = listOfNotNull(
                    short?.let { "${strings.text("课程代码", "Code")} $it" },
                    start?.let { "${strings.text("开课", "Starts")} $it" },
                    if (hidden) strings.text("已隐藏", "Hidden") else null,
                ).joinToString("\n").ifEmpty { null },
            )
        }
        val trailing = strings.text("${courses.size} 门", "${courses.size}")
        return SysuRendered(
            summary = cardSummary(title, trailing, null),
            text = lines.joinToString("\n").trimEnd(),
            card = readyCard(
                kind = SysuCardKind.Lms,
                title = title,
                trailing = trailing,
                total = rows.size,
                items = rows,
            ),
        )
    }

    private fun lmsTodo(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val items = SysuJson.lmsTodo(payload) ?: return null
        val title = strings.text("作业与待办", "Assignments")
        if (items.isEmpty()) {
            val body = strings.text("没有待办事项。", "No assignments or to-dos.")
            return SysuRendered(body, body, emptyCard(SysuCardKind.Todo, title, null, body))
        }
        val lines = mutableListOf("${strings.text("待办 · 共", "To-do ·")} ${items.size} ${strings.text("项", "items")}")
        val rows = mutableListOf<CliCardItem>()
        for (item in items) {
            val whenText = SysuFormat.dateTime(item.at)
            val name = item.name.orDash()
            val course = item.course?.takeIf { it.isNotBlank() }
            val action = item.action?.takeIf { it.isNotBlank() }
            val url = item.url?.takeIf { it.isNotBlank() }
            lines += "  - " + listOfNotNull(whenText, name, course, action).joinToString("｜")
            if (url != null) lines += "    $url"
            rows += CliCardItem(
                title = name,
                subtitle = course,
                meta = whenText,
                url = url,
                body = action?.let { listOf(strings.text("动作", "Action"), it).joinToString(" ") },
            )
        }
        val trailing = strings.text("${items.size} 项", "${items.size}")
        return SysuRendered(
            summary = cardSummary(title, trailing, null),
            text = lines.joinToString("\n").trimEnd(),
            card = readyCard(
                kind = SysuCardKind.Todo,
                title = title,
                trailing = trailing,
                total = rows.size,
                items = rows,
            ),
        )
    }

    private fun gymSlots(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.gymSlots(payload) ?: return null
        val title = strings.text("体育场馆空位", "Venue availability")
        val failures = data.errors.mapNotNull { error ->
            error.error?.takeIf { it.isNotBlank() }?.let { message ->
                listOfNotNull(error.name, error.id).joinToString(" · ") + "：" + message
            }
        }
        val failure = data.error?.message
        if (failure != null) {
            val body = failureMessage(failure, SysuSite.Gym, strings)
            return SysuRendered(body, body, errorCard(SysuCardKind.Gym, title, body), isError = true)
        }
        if (data.types.isEmpty()) {
            val body = when {
                failures.isNotEmpty() -> failureMessage(failures.joinToString("\n"), SysuSite.Gym, strings)
                else -> strings.text("没有可预约的场馆时段。", "No bookable venue slots.")
            }
            val card = if (failures.isNotEmpty()) {
                errorCard(SysuCardKind.Gym, title, body)
            } else {
                emptyCard(SysuCardKind.Gym, title, null, body)
            }
            return SysuRendered(body, body, card, isError = failures.isNotEmpty())
        }
        val lines = mutableListOf(title)
        if (data.ok == false) {
            lines += strings.text("本次查询有失败项。", "Some queries failed.")
        }
        val sections = mutableListOf<CliCardSection>()
        var venueCount = 0
        for (type in data.types) {
            val window = type.window
            val windowLabel = listOfNotNull(
                SysuFormat.date(window?.start),
                SysuFormat.date(window?.end),
            ).distinct().joinToString(" ~ ").ifEmpty { null }
            val feeLabel = SysuFormat.fee(type.fee, strings)
            lines += ""
            lines += buildString {
                append("· ").append(type.name.orDash())
                type.id?.takeIf { it.isNotBlank() }?.let { append("（").append(strings.text("类型 $it", "type $it")).append("）") }
                if (windowLabel != null) append("（").append(windowLabel).append("）")
                if (feeLabel != null) append(" · ").append(feeLabel)
            }
            if (type.error != null) {
                lines += "  " + (
                    SysuFailures.actionable(type.error, SysuSite.Gym, strings)
                        ?: (strings.text("查询失败：", "Query failed: ") + SysuText.cleanInline(type.error))
                    )
                continue
            }
            if (type.venues.isEmpty()) {
                val notice = type.notice?.takeIf { it.isNotBlank() }
                lines += "  " + strings.text("没有可约时段", "No available slots") +
                    (if (notice != null) strings.text("（公告：${SysuText.cleanInline(notice)}）", " (notice: ${SysuText.cleanInline(notice)})") else "")
                continue
            }
            val rows = mutableListOf<CliCardItem>()
            for (venue in type.venues) {
                val venueName = SysuFormat.venueName(type.name, venue)
                val display = SysuGymDisplay.slots(venue, strings)
                lines += "  ${venueName.orDash()}" +
                    venue.id?.takeIf { it.isNotBlank() }?.let { "（${strings.text("场地 $it", "venue $it")}）" }.orEmpty()
                for (line in display.observationLines) lines += "    $line"
                rows += CliCardItem(
                    title = venueName.orDash(),
                    subtitle = display.cardText,
                    meta = display.countLabel,
                )
                venueCount += 1
            }
            if (rows.isNotEmpty()) {
                sections += CliCardSection(
                    label = listOfNotNull(type.name, feeLabel).joinToString("｜"),
                    items = rows,
                )
            }
            if (type.notice?.isNotBlank() == true) {
                lines += "  " + strings.text("公告：${SysuText.cleanInline(type.notice)}", "Notice: ${SysuText.cleanInline(type.notice)}")
            }
        }
        if (failures.isNotEmpty()) {
            lines += ""
            for (failed in failures) {
                lines += "  " + (
                    SysuFailures.actionable(failed, SysuSite.Gym, strings)
                        ?: (strings.text("错误：", "Error: ") + SysuText.cleanInline(failed))
                    )
            }
        }
        val windows = data.types.mapNotNull {
            listOfNotNull(SysuFormat.date(it.window?.start), SysuFormat.date(it.window?.end))
                .distinct()
                .joinToString(" ~ ")
                .ifEmpty { null }
        }.distinct()
        val footer = windows.singleOrNull()?.let { strings.text("预约窗口 $it", "Booking window $it") }
        val trailing = strings.text("$venueCount 个场地", "$venueCount venues")
        val text = lines.joinToString("\n").trimEnd()
        val hasErrors = failures.isNotEmpty() || data.types.any { it.error != null }
        if (sections.isEmpty()) {
            val card = if (hasErrors) errorCard(SysuCardKind.Gym, title, text) else emptyCard(SysuCardKind.Gym, title, null, text)
            return SysuRendered(text, text, card, isError = hasErrors)
        }
        return SysuRendered(
            summary = cardSummary(title, trailing, null),
            text = text,
            card = readyCard(
                kind = SysuCardKind.Gym,
                title = title,
                trailing = trailing,
                total = venueCount,
                sections = sections,
                note = footer,
            ),
        )
    }

    private fun netpay(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.netpay(payload) ?: return null
        val title = strings.text("校园网服务", "Campus network")
        val info = data.info
        val success = info?.get("success").scalarText()?.toBooleanStrictOrNull()
        val message = info?.let { it["msg"].scalarText() ?: it["message"].scalarText() }
        val identity = SysuFormat.netpayIdentity(info)
        if (success == false) {
            val original = message?.takeIf { it.isNotBlank() }
                ?: strings.text("网费服务返回未登录。", "The network service returned a signed-out state.")
            val body = SysuFailures.actionable(original, SysuSite.Netpay, strings)
                ?: strings.text(
                    "网费服务登录已失效：$original",
                    "The network service sign-in expired: $original",
                )
            return SysuRendered(body, body, errorCard(SysuCardKind.Netpay, title, body), isError = true)
        }
        val identityLine = identity.label
        if (data.services.isEmpty()) {
            val body = listOfNotNull(
                identityLine,
                strings.text("名下没有网络服务。", "No network services under this account."),
            ).joinToString("\n")
            val notice = strings.text("名下没有网络服务。", "No network services under this account.")
            return SysuRendered(body, body, emptyCard(SysuCardKind.Netpay, title, identityLine, notice))
        }
        val lines = mutableListOf<String>()
        lines += listOfNotNull(strings.text("校园网服务", "Campus network"), identityLine).joinToString(" · ")
        val rows = mutableListOf<CliCardItem>()
        for (service in data.services) {
            val name = service.service.orDash()
            val status = service.status?.takeIf { it.isNotBlank() }
            val expire = SysuFormat.date(service.expire)
            val daysLeft = service.daysLeft.intOrNull()
            val address = service.address?.takeIf { it.isNotBlank() }
            val details = listOfNotNull(
                service.no?.takeIf { it.isNotBlank() }?.let { strings.text("编号 $it", "No. $it") },
                service.mac?.takeIf { it.isNotBlank() }?.let { strings.text("MAC $it", "MAC $it") },
                service.dept?.takeIf { it.isNotBlank() }?.let { strings.text("部门 $it", "Dept. $it") },
                service.user?.takeIf { it.isNotBlank() }?.let { strings.text("用户 $it", "User $it") },
                service.id?.takeIf { it.isNotBlank() }?.let { strings.text("服务 ID $it", "Service ID $it") },
                service.paused?.takeIf { it.isNotBlank() }?.let { strings.text("暂停信息 $it", "Paused $it") },
            )
            lines += "  - " + listOfNotNull(
                name,
                status,
                expire?.let { strings.text("到期 $it", "expires $it") },
                daysLeft?.let { strings.text("剩余 $it 天", "$it days left") },
                address,
            ).joinToString("｜")
            if (details.isNotEmpty()) lines += "    " + details.joinToString("｜")
            rows += CliCardItem(
                title = name,
                subtitle = listOfNotNull(status, address).joinToString(" · ").ifEmpty { null },
                meta = listOfNotNull(
                    expire?.let { strings.text("到期 $it", "expires $it") },
                    daysLeft?.let { strings.text("剩余 $it 天", "$it days left") },
                ).joinToString(" · ").ifEmpty { null },
                body = details.joinToString("\n").ifEmpty { null },
            )
        }
        val trailing = strings.text("${data.services.size} 个服务", "${data.services.size} services")
        return SysuRendered(
            summary = cardSummary(title, trailing, identityLine),
            text = lines.joinToString("\n").trimEnd(),
            card = readyCard(
                kind = SysuCardKind.Netpay,
                title = title,
                trailing = trailing,
                context = identityLine,
                total = rows.size,
                items = rows,
            ),
        )
    }

    private fun bodyLines(vararg pairs: Pair<String, String?>): String? = pairs.mapNotNull { (label, value) ->
        val text = value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        "$label $text"
    }.joinToString("\n").ifEmpty { null }

    private fun failureMessage(raw: String, site: SysuSite, strings: SysuStrings): String =
        SysuFailures.actionable(raw, site, strings)
            ?: listOf(raw.trim(), strings.text("请稍后重试；仍失败时到「中山大学 CLI」插件页重新登录。", "Try again later; if it still fails, sign in again from the SYSU CLI plugin page."))
                .filter { it.isNotBlank() }
                .joinToString("\n")

    private fun cardSummary(title: String, trailing: String?, context: String?): String =
        listOfNotNull(title, trailing, context).filter { it.isNotBlank() }.joinToString(" · ")

    private fun readyCard(
        kind: SysuCardKind,
        title: String,
        trailing: String?,
        context: String? = null,
        total: Int,
        items: List<CliCardItem> = emptyList(),
        sections: List<CliCardSection> = emptyList(),
        note: String? = null,
    ): CliCardModel = CliCardModel(
        kind = kind.wire,
        state = CliCardState.READY,
        title = title,
        trailing = trailing,
        context = context,
        total = total,
        items = items,
        sections = sections,
        summary = cardSummary(title, trailing, context),
        note = note,
    )

    private fun emptyCard(
        kind: SysuCardKind,
        title: String,
        context: String?,
        body: String,
    ): CliCardModel = CliCardModel(
        kind = kind.wire,
        state = CliCardState.EMPTY,
        title = title,
        context = context,
        summary = cardSummary(title, null, context),
        note = body,
    )

    private fun errorCard(
        kind: SysuCardKind,
        title: String,
        body: String,
    ): CliCardModel = CliCardModel(
        kind = kind.wire,
        state = CliCardState.ERROR,
        title = title,
        summary = title,
        note = body,
    )
}

internal object SysuFormat {
    private val FULL_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val DATE_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2})""")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DEPARTMENT = Regex("""^([\u4e00-\u9fff]{2,4}学院)""")

    fun date(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (FULL_DATE.matches(text)) return text
        DATE_PREFIX.find(text)?.let { return it.groupValues[1] }
        return text
    }

    fun dateTime(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Instant.parse(text) }.getOrNull()?.let {
            return LocalDateTime.ofInstant(it, zone).format(DATE_TIME)
        }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let {
            return it.atZoneSameInstant(zone).toLocalDateTime().format(DATE_TIME)
        }
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let {
            return it.format(DATE_TIME)
        }
        return text
    }

    fun weekday(value: String?): String? = value?.trim()?.replace("星期", "周")?.takeIf { it.isNotEmpty() }

    fun sections(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { "第 $it 节" }

    fun place(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val department = DEPARTMENT.find(text)?.groupValues?.get(1) ?: return text
        val abbreviation = department.first() + "院"
        val rest = text.removePrefix(department)
        val index = rest.lastIndexOf(abbreviation)
        if (index <= 0) return text
        val before = rest.substring(0, index)
        val after = rest.substring(index + abbreviation.length).trimStart(' ', '-', '·')
        val common = commonPrefixLength(before.trimEnd(), after)
        if (common < 2) return text
        return department + before + after.substring(common)
    }

    fun decimal(value: JsonElement?, digits: Int): String? {
        val number = value.doubleOrNull() ?: value.scalarText()?.toDoubleOrNull() ?: return null
        return formatDecimal(number, digits)
    }

    fun formatDecimal(number: Double, digits: Int): String {
        val rounded = kotlin.math.round(number * pow(digits)) / pow(digits)
        val text = if (digits <= 0) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.${digits}f", rounded)
        }
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    fun fee(fee: SysuGymFee?, strings: SysuStrings): String? {
        if (fee == null) return null
        val price = listOfNotNull(
            decimal(fee.creditFee, 1)?.let { strings.text("$it 积分/时", "$it points/hour") },
            decimal(fee.cashFee, 1)?.let { strings.text("现金 $it 元/时", "¥$it/hour") },
        ).joinToString(" · ")
        val timeslot = fee.timeslot?.let { slot ->
            val range = listOfNotNull(
                slot.startTime?.takeIf { it.isNotBlank() },
                slot.endTime?.takeIf { it.isNotBlank() },
            ).joinToString("-")
            listOfNotNull(slot.dayType?.takeIf { it.isNotBlank() }, range.ifEmpty { null })
                .joinToString(" ")
                .ifEmpty { null }
        }
        val meta = listOfNotNull(
            fee.name?.takeIf { it.isNotBlank() },
            fee.userRole?.takeIf { it.isNotBlank() }?.let { strings.text("身份 $it", "role $it") },
            timeslot,
            fee.identity?.takeIf { it.isNotBlank() }?.let { strings.text("价格 $it", "price $it") },
        ).joinToString(" · ")
        return when {
            price.isNotEmpty() && meta.isNotEmpty() -> "$price（$meta）"
            price.isNotEmpty() -> price
            meta.isNotEmpty() -> meta
            else -> null
        }
    }

    fun venueName(typeName: String?, venue: SysuGymVenue): String? {
        val full = venue.name?.takeIf { it.isNotBlank() }
        val short = venue.shortName?.takeIf { it.isNotBlank() }
        if (short != null && short != full && (short.length <= 12 || full == null)) return short
        if (full != null && typeName != null && full.length > typeName.length) {
            val trimmed = full.removePrefix(typeName).trimStart(' ', '-', '—', '·')
            if (trimmed.isNotEmpty() && trimmed != full) return trimmed
        }
        return full ?: short
    }

    fun netpayIdentity(info: JsonObject?): SysuNetpayIdentity {
        if (info == null) return SysuNetpayIdentity(null, null, null)
        return SysuNetpayIdentity(
            name = info.firstText("xm", "name", "userName", "nickName", "netid"),
            studentNo = info.firstText("sn", "studentNo", "studentNumber", "xh"),
            department = info.firstText("bmmc", "department", "dept", "unit"),
        )
    }

    private fun JsonObject.firstText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key].scalarText() }

    private fun commonPrefixLength(left: String, right: String): Int {
        var index = 0
        while (index < left.length && index < right.length && left[index] == right[index]) index += 1
        return index
    }

    private fun pow(digits: Int): Double = when (digits) {
        0 -> 1.0
        1 -> 10.0
        2 -> 100.0
        3 -> 1000.0
        else -> Math.pow(10.0, digits.toDouble())
    }
}

internal data class SysuNetpayIdentity(
    val name: String?,
    val studentNo: String?,
    val department: String?,
) {
    val label: String?
        get() = listOfNotNull(
            name,
            studentNo?.let { "学号 $it" },
            department,
        ).joinToString(" · ").ifEmpty { null }
}

internal object SysuText {
    fun cleanInline(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
}
