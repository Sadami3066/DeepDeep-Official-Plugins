package io.github.whywood.deepdeep.plugin.sysu

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

internal data class SysuRendered(
    val observation: String,
    val card: SysuCardPayload?,
    val isError: Boolean = false,
)

internal object SysuObservation {
    fun render(kind: SysuCommandKind, output: String, strings: SysuStrings): SysuRendered? = when (kind) {
        SysuCommandKind.Schedule -> schedule(output, strings)
        SysuCommandKind.Scores -> scores(output, strings)
        SysuCommandKind.Exams -> exams(output, strings)
        SysuCommandKind.Credits -> credits(output, strings)
        SysuCommandKind.LmsCourses -> lmsCourses(output, strings)
        SysuCommandKind.LmsTodo -> lmsTodo(output, strings)
        SysuCommandKind.GymSlots -> gymSlots(output, strings)
        SysuCommandKind.Netpay -> netpay(output, strings)
    }

    private fun schedule(output: String, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.schedule(output) ?: return null
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
                observation = "$header\n$body",
                card = SysuCards.empty(SysuCardKind.Schedule, title, subtitle, body),
            )
        }
        val lines = mutableListOf(header)
        val groups = mutableListOf<SysuCardGroup>()
        for (day in data.days) {
            val date = SysuFormat.date(day.date)
            val weekday = SysuFormat.weekday(day.weekday)
            val dayLabel = listOfNotNull(weekday, date).joinToString(" ")
            lines += ""
            lines += "$dayLabel（${day.items.size} ${strings.text("门", "classes")}）"
            val rows = mutableListOf<SysuCardRow>()
            for (item in day.items) {
                val exam = item.type == "exam"
                val name = item.name.orDash()
                val detail = listOfNotNull(
                    item.teacher?.takeIf { it.isNotBlank() },
                    SysuFormat.place(item.place),
                ).joinToString(" · ")
                val time = item.time?.takeIf { it.isNotBlank() }
                lines += "  - ${if (exam) strings.text("考试 ", "Exam ") else ""}" +
                    "${SysuFormat.sections(item.sections) ?: strings.text("节次未知", "No period")} " +
                    "$name${if (detail.isNotEmpty()) "｜$detail" else ""}" +
                    (if (time != null) "｜$time" else "")
                rows += SysuCardRow(
                    title = name,
                    subtitle = detail.ifEmpty { null },
                    value = SysuFormat.sections(item.sections) ?: time,
                )
            }
            groups += SysuCardGroup(label = dayLabel, rows = rows)
        }
        val observation = SysuFormat.bounded(lines)
        val (limited, dropped) = SysuCards.capRows(groups)
        val card = SysuCards.ready(
            kind = SysuCardKind.Schedule,
            title = title,
            subtitle = subtitle.ifEmpty { range },
            count = strings.text("${data.days.sumOf { it.items.size }} 门", "${data.days.sumOf { it.items.size }} classes"),
            groups = limited,
            footer = SysuCards.footerWithOverflow(range, dropped, strings),
        )
        return SysuRendered(observation, card)
    }

    private fun scores(output: String, strings: SysuStrings): SysuRendered? {
        val semesters = SysuJson.scores(output) ?: return null
        val title = strings.text("成绩", "Grades")
        if (semesters.isEmpty()) {
            val body = strings.text("还没有任何成绩记录。", "No grades recorded yet.")
            return SysuRendered(body, SysuCards.empty(SysuCardKind.Grades, title, null, body))
        }
        val lines = mutableListOf<String>()
        val groups = mutableListOf<SysuCardGroup>()
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
            val rows = mutableListOf<SysuCardRow>()
            for (course in semester.courses) {
                val name = course.scoCourseName.orDash()
                val code = course.scoCourseNumber?.takeIf { it.isNotBlank() }
                val courseCredit = SysuFormat.decimal(course.scoCredit, 1)
                val score = course.scoFinalScore.scalarText()
                val point = SysuFormat.decimal(course.scoPoint, 1)
                lines += "  - $name${if (code != null) "（$code）" else ""}" +
                    (if (courseCredit != null) " ${courseCredit}${strings.text(" 学分", " credits")}" else "") +
                    (if (score != null) " $score" else "") +
                    " " + (if (point != null) strings.text("绩点 $point", "grade point $point") else strings.text("绩点 —", "grade point —"))
                rows += SysuCardRow(
                    title = name,
                    subtitle = code,
                    value = listOfNotNull(
                        score,
                        point?.let { strings.text("绩点 $it", "GP $it") },
                    ).joinToString(" · ").ifEmpty { null },
                )
            }
            groups += SysuCardGroup(
                label = listOfNotNull(semester.semester, gpa?.let { strings.text("加权绩点 $it", "GPA $it") }).joinToString(" · "),
                rows = rows,
            )
        }
        val observation = SysuFormat.bounded(lines)
        val (limited, dropped) = SysuCards.capRows(groups)
        val first = semesters.first()
        val count = first.count.scalarText() ?: first.courses.size.toString()
        val footer = listOfNotNull(
            SysuFormat.decimal(first.credit, 1)?.let { strings.text("$it 学分", "$it credits") },
            SysuFormat.decimal(first.gpa, 3)?.let { strings.text("加权绩点 $it（自算）", "GPA $it (self-calculated)") },
            if (dropped > 0) strings.text("还有 $dropped 门未显示", "$dropped more not shown") else null,
        ).joinToString(" · ")
        return SysuRendered(
            observation,
            SysuCards.ready(
                kind = SysuCardKind.Grades,
                title = title,
                subtitle = first.semester,
                count = strings.text("$count 门", "$count courses"),
                groups = limited,
                footer = footer.ifEmpty { null },
            ),
        )
    }

    private fun exams(output: String, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.exams(output) ?: return null
        val term = data.term.orDash()
        val title = strings.text("考试安排", "Exams")
        val count = data.exams.size
        val header = "${strings.text("考试安排", "Exams")} · $term · ${strings.text("共 $count 场", "$count exams")}"
        if (data.exams.isEmpty()) {
            val body = strings.text("$term 还没有已排期的考试。", "No exams scheduled for $term yet.")
            return SysuRendered("$header\n$body", SysuCards.empty(SysuCardKind.Exams, title, term, body))
        }
        val lines = mutableListOf(header)
        val byDate = linkedMapOf<String, MutableList<SysuExam>>()
        for (exam in data.exams) {
            val date = SysuFormat.date(exam.date) ?: continue
            byDate.getOrPut(date) { mutableListOf() } += exam
        }
        val groups = mutableListOf<SysuCardGroup>()
        for ((date, exams) in byDate) {
            val weekday = SysuFormat.weekday(exams.first().weekday)
            val label = listOfNotNull(date, weekday).joinToString(" ")
            lines += ""
            lines += label
            val rows = mutableListOf<SysuCardRow>()
            for (exam in exams) {
                val time = exam.time?.takeIf { it.isNotBlank() }
                val place = SysuFormat.place(exam.place)
                val sections = SysuFormat.sections(exam.sections)
                lines += "  - " + listOfNotNull(
                    time,
                    exam.name.orDash(),
                    place,
                    sections,
                ).joinToString("｜")
                rows += SysuCardRow(
                    title = exam.name.orDash(),
                    subtitle = listOfNotNull(time, place).joinToString(" · ").ifEmpty { null },
                    value = sections,
                )
            }
            groups += SysuCardGroup(label = label, rows = rows)
        }
        val (limited, dropped) = SysuCards.capRows(groups)
        return SysuRendered(
            SysuFormat.bounded(lines),
            SysuCards.ready(
                kind = SysuCardKind.Exams,
                title = title,
                subtitle = term,
                count = strings.text("$count 场", "$count"),
                groups = limited,
                footer = SysuCards.footerWithOverflow(null, dropped, strings),
            ),
        )
    }

    private fun credits(output: String, strings: SysuStrings): SysuRendered? {
        val plans = SysuJson.credits(output) ?: return null
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
                plan.trainCateCode?.takeIf { it.isNotBlank() }?.let {
                    append(" · ").append(strings.text("培养类别 ", "category ")).append(it)
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
        return SysuRendered(SysuFormat.bounded(lines), card = null)
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

    private fun lmsCourses(output: String, strings: SysuStrings): SysuRendered? {
        val courses = SysuJson.lmsCourses(output) ?: return null
        val title = strings.text("在线教学", "LMS")
        if (courses.isEmpty()) {
            val body = strings.text("没有选课记录（或者课程都隐藏了）。", "No enrolled courses (or all are hidden).")
            return SysuRendered(body, SysuCards.empty(SysuCardKind.Lms, title, null, body))
        }
        val lines = mutableListOf("${strings.text("在线教学 · 共", "LMS ·")} ${courses.size} ${strings.text("门课程", "courses")}")
        val rows = mutableListOf<SysuCardRow>()
        for (course in courses) {
            val id = course.id.scalarText()
            val name = course.name.orDash()
            val progress = course.progress.scalarText()?.let { "$it%" } ?: "—"
            val end = SysuFormat.date(course.end)
            lines += "  - ${id?.let { "$it " } ?: ""}$name｜${strings.text("进度", "progress")} $progress｜${strings.text("结课", "ends")} ${end.orDash()}"
            rows += SysuCardRow(
                title = name,
                subtitle = id?.let { strings.text("课程 ID $it", "Course ID $it") },
                value = listOfNotNull(progress, end?.let { strings.text("结课 $it", "ends $it") }).joinToString(" · "),
            )
        }
        val (limited, dropped) = SysuCards.capRows(listOf(SysuCardGroup(rows = rows)))
        return SysuRendered(
            SysuFormat.bounded(lines),
            SysuCards.ready(
                kind = SysuCardKind.Lms,
                title = title,
                subtitle = null,
                count = strings.text("${courses.size} 门", "${courses.size}"),
                groups = limited,
                footer = SysuCards.footerWithOverflow(null, dropped, strings),
            ),
        )
    }

    private fun lmsTodo(output: String, strings: SysuStrings): SysuRendered? {
        val items = SysuJson.lmsTodo(output) ?: return null
        val title = strings.text("作业与待办", "Assignments")
        if (items.isEmpty()) {
            val body = strings.text("没有待办事项。", "No assignments or to-dos.")
            return SysuRendered(body, SysuCards.empty(SysuCardKind.Todo, title, null, body))
        }
        val lines = mutableListOf("${strings.text("待办 · 共", "To-do ·")} ${items.size} ${strings.text("项", "items")}")
        val rows = mutableListOf<SysuCardRow>()
        for (item in items) {
            val whenText = SysuFormat.dateTime(item.at)
            val name = item.name.orDash()
            val course = item.course?.takeIf { it.isNotBlank() }
            val action = item.action?.takeIf { it.isNotBlank() }
            lines += "  - " + listOfNotNull(whenText, name, course, action).joinToString("｜")
            item.url?.takeIf { it.isNotBlank() }?.let { lines += "    $it" }
            rows += SysuCardRow(
                title = name,
                subtitle = course,
                value = whenText,
            )
        }
        val (limited, dropped) = SysuCards.capRows(listOf(SysuCardGroup(rows = rows)))
        return SysuRendered(
            SysuFormat.bounded(lines),
            SysuCards.ready(
                kind = SysuCardKind.Todo,
                title = title,
                subtitle = null,
                count = strings.text("${items.size} 项", "${items.size}"),
                groups = limited,
                footer = SysuCards.footerWithOverflow(null, dropped, strings),
            ),
        )
    }

    private fun gymSlots(output: String, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.gymSlots(output) ?: return null
        val title = strings.text("体育场馆空位", "Venue availability")
        val failure = data.error?.message
        if (failure != null) {
            val body = SysuFailures.actionable(failure, strings) ?: strings.text(
                "场馆查询失败：${SysuText.cleanInline(failure)}",
                "Venue query failed: ${SysuText.cleanInline(failure)}",
            )
            return SysuRendered(body, SysuCards.error(SysuCardKind.Gym, title, body), isError = true)
        }
        val types = data.types.filter { it.error != null || it.venues.isNotEmpty() || it.name != null }
        if (types.isEmpty()) {
            val body = strings.text(
                data.errors.firstOrNull()?.error?.let { "场馆查询失败：${SysuText.cleanInline(it)}" } ?: "没有可预约的场馆时段。",
                data.errors.firstOrNull()?.error?.let { "Venue query failed: ${SysuText.cleanInline(it)}" } ?: "No bookable venue slots.",
            )
            return SysuRendered(body, SysuCards.empty(SysuCardKind.Gym, title, null, body))
        }
        val lines = mutableListOf(title)
        val groups = mutableListOf<SysuCardGroup>()
        for (type in types) {
            val window = type.window
            val windowLabel = listOfNotNull(
                SysuFormat.date(window?.start),
                SysuFormat.date(window?.end),
            ).distinct().joinToString(" ~ ").ifEmpty { null }
            val feeLabel = SysuFormat.fee(type.fee, strings)
            lines += ""
            lines += buildString {
                append("· ").append(type.name.orDash())
                if (windowLabel != null) append("（").append(windowLabel).append("）")
                if (feeLabel != null) append(" · ").append(feeLabel)
            }
            if (type.error != null) {
                lines += "  " + strings.text("查询失败：", "Query failed: ") + SysuText.cleanInline(type.error)
                continue
            }
            if (type.venues.isEmpty()) {
                val notice = type.notice?.takeIf { it.isNotBlank() }
                lines += "  " + strings.text("没有可约时段", "No available slots") +
                    (if (notice != null) strings.text("（公告：${SysuText.cleanInline(notice)}）", " (notice: ${SysuText.cleanInline(notice)})") else "")
                continue
            }
            val rows = mutableListOf<SysuCardRow>()
            for (venue in type.venues) {
                val venueName = SysuFormat.venueName(type.name, venue)
                val display = SysuGymDisplay.slots(venue, strings)
                lines += "  ${venueName.orDash()}"
                for (line in display.observationLines) lines += "    $line"
                rows += SysuCardRow(
                    title = venueName.orDash(),
                    subtitle = display.cardSummary,
                    value = display.cardCount,
                    subtitleMaxLines = 2,
                )
            }
            groups += SysuCardGroup(label = listOfNotNull(type.name, feeLabel).joinToString(" · "), rows = rows)
            if (type.notice?.isNotBlank() == true) {
                lines += "  " + strings.text("公告：${SysuText.cleanInline(type.notice)}", "Notice: ${SysuText.cleanInline(type.notice)}")
            }
        }
        val windows = types.mapNotNull { listOfNotNull(SysuFormat.date(it.window?.start), SysuFormat.date(it.window?.end)).distinct().joinToString(" ~ ").ifEmpty { null } }.distinct()
        val footer = windows.singleOrNull()?.let { strings.text("预约窗口 $it", "Booking window $it") }
        val (limited, dropped) = SysuCards.capRows(groups)
        return SysuRendered(
            SysuFormat.bounded(lines),
            SysuCards.ready(
                kind = SysuCardKind.Gym,
                title = title,
                subtitle = null,
                count = strings.text(
                    "${types.sumOf { it.venues.size }} 个场地",
                    "${types.sumOf { it.venues.size }} venues",
                ),
                groups = limited,
                footer = SysuCards.footerWithOverflow(footer, dropped, strings),
            ),
        )
    }

    private fun netpay(output: String, strings: SysuStrings): SysuRendered? {
        val data = SysuJson.netpay(output) ?: return null
        val title = strings.text("校园网服务", "Campus network")
        val info = data.info
        val success = info?.get("success").scalarText()?.toBooleanStrictOrNull()
        val message = info?.let { it["msg"].scalarText() ?: it["message"].scalarText() }
        val identity = SysuFormat.netpayIdentity(info)
        if (success == false) {
            val body = SysuFailures.actionable(message, strings) ?: strings.text(
                "网费服务登录已失效：请在「中山大学 CLI」插件页重新登录后重试。",
                "The network service sign-in expired. Sign in again from the SYSU CLI plugin page, then retry.",
            )
            return SysuRendered(body, SysuCards.error(SysuCardKind.Netpay, title, body), isError = true)
        }
        val identityLine = identity.label
        if (data.services.isEmpty()) {
            val body = listOfNotNull(
                identityLine,
                strings.text("名下没有网络服务。", "No network services under this account."),
            ).joinToString("\n")
            return SysuRendered(
                body,
                SysuCards.empty(SysuCardKind.Netpay, title, identityLine, strings.text("名下没有网络服务。", "No network services under this account.")),
            )
        }
        val lines = mutableListOf<String>()
        lines += listOfNotNull(strings.text("校园网服务", "Campus network"), identityLine).joinToString(" · ")
        val rows = mutableListOf<SysuCardRow>()
        for (service in data.services) {
            val name = service.service.orDash()
            val status = service.status?.takeIf { it.isNotBlank() }
            val expire = SysuFormat.date(service.expire)
            val daysLeft = service.daysLeft.intOrNull()
            val address = service.address?.takeIf { it.isNotBlank() }
            lines += "  - " + listOfNotNull(
                name,
                status,
                expire?.let { strings.text("到期 $it", "expires $it") },
                daysLeft?.let { strings.text("剩余 $it 天", "$it days left") },
                address,
            ).joinToString("｜")
            service.paused?.takeIf { it.isNotBlank() }?.let {
                lines += "    " + strings.text("暂停信息：$it", "Paused: $it")
            }
            rows += SysuCardRow(
                title = name,
                subtitle = listOfNotNull(status, address).joinToString(" · ").ifEmpty { null },
                value = expire?.let { strings.text("到期 $it", "expires $it") },
            )
        }
        val (limited, dropped) = SysuCards.capRows(listOf(SysuCardGroup(rows = rows)))
        return SysuRendered(
            SysuFormat.bounded(lines),
            SysuCards.ready(
                kind = SysuCardKind.Netpay,
                title = title,
                subtitle = identityLine,
                count = strings.text("${data.services.size} 个服务", "${data.services.size} services"),
                groups = limited,
                footer = SysuCards.footerWithOverflow(null, dropped, strings),
            ),
        )
    }
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
        val credit = decimal(fee.creditFee, 1)
        val cash = decimal(fee.cashFee, 1)
        val role = fee.name?.takeIf { it.isNotBlank() }
        return when {
            credit != null -> strings.text(
                "$credit 积分/时" + (role?.let { "（$it）" } ?: ""),
                "$credit points/hour" + (role?.let { " ($it)" } ?: ""),
            )

            cash != null -> strings.text("现金 $cash 元/时", "¥$cash/hour")
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

    fun bounded(lines: List<String>, maxLines: Int = MAX_OBSERVATION_LINES): String {
        if (lines.size <= maxLines) return lines.joinToString("\n").trimEnd()
        val hidden = lines.size - maxLines
        return (lines.take(maxLines) + "…（结果较长，已省略 $hidden 行；可用 --date / --days / --week 等参数缩小范围）")
            .joinToString("\n")
            .trimEnd()
    }

    const val MAX_OBSERVATION_LINES = 80
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
