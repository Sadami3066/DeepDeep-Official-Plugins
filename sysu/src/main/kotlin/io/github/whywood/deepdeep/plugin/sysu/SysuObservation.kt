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
        if (data.days.isEmpty()) {
            val body = strings.text("本周没有课程。", "No classes this week.")
            return SysuRendered(
                summary = cardSummary(title, null, subtitle),
                text = strings.text(
                    "本周没有课程。可以换一个周次再查（--week N）。",
                    "No classes this week. Try another week with --week N.",
                ),
                card = emptyCard(SysuCardKind.Schedule, title, subtitle, body),
            )
        }
        val sections = data.days.map { day ->
            val date = SysuFormat.date(day.date)
            val weekday = SysuFormat.weekday(day.weekday)
            val dayLabel = listOfNotNull(weekday, date).joinToString(" ")
            val rows = day.items.map { item ->
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
                CliCardItem(
                    title = name,
                    subtitle = cardDetail.ifEmpty { null },
                    meta = listOfNotNull(sectionsLabel, time).joinToString(" · ").ifEmpty { null },
                )
            }
            CliCardSection(label = dayLabel, items = rows)
        }
        val rowCount = sections.sumOf { it.items.size }
        val trailing = strings.text("$rowCount 门", "$rowCount classes")
        return SysuRendered(
            summary = cardSummary(title, trailing, subtitle),
            text = cardGuidance(strings, "课表", "The timetable"),
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
        val published = semesters.filter { it.courses.isNotEmpty() }
        if (published.isEmpty()) {
            val body = strings.text("成绩尚未公布。", "Grades haven't been published yet.")
            return SysuRendered(
                summary = title,
                text = strings.text(
                    "成绩尚未公布。出成绩后重新查询即可。",
                    "Grades haven't been published yet. Check again after they are released.",
                ),
                card = emptyCard(SysuCardKind.Grades, title, null, body),
            )
        }
        val sections = published.map { semester ->
            val gpa = SysuFormat.decimal(semester.gpa, 3)
            val credit = SysuFormat.decimal(semester.credit, 1)
            val count = semester.count.scalarText() ?: semester.courses.size.toString()
            CliCardSection(
                label = listOfNotNull(
                    semester.semester,
                    strings.text("$count 门", "$count courses"),
                    credit?.let { strings.text("$it 学分", "$it credits") },
                    gpa?.let { strings.text("加权绩点 $it", "GPA $it") },
                ).joinToString(" · "),
                items = semester.courses.map { course ->
                    val name = course.scoCourseName.orDash()
                    val code = course.scoCourseNumber?.takeIf { it.isNotBlank() }
                    val courseCredit = SysuFormat.decimal(course.scoCredit, 1)
                    val score = course.scoFinalScore.scalarText()
                    val point = SysuFormat.decimal(course.scoPoint, 1)
                    CliCardItem(
                        title = name,
                        subtitle = code,
                        meta = listOfNotNull(
                            score,
                            courseCredit?.let { strings.text("$it 学分", "$it credits") },
                            point?.let { strings.text("绩点 $it", "GP $it") },
                        ).joinToString(" · ").ifEmpty { null },
                    )
                },
            )
        }
        val rowCount = sections.sumOf { it.items.size }
        val first = published.first()
        val count = first.count.scalarText() ?: first.courses.size.toString()
        val trailing = strings.text("$count 门", "$count courses")
        val footer = listOfNotNull(
            SysuFormat.decimal(first.credit, 1)?.let { strings.text("$it 学分", "$it credits") },
            SysuFormat.decimal(first.gpa, 3)?.let { strings.text("加权绩点 $it（自算）", "GPA $it (self-calculated)") },
        ).joinToString(" · ").ifEmpty { null }
        return SysuRendered(
            summary = cardSummary(title, trailing, first.semester),
            text = cardGuidance(strings, "成绩", "The grades"),
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
        if (data.exams.isEmpty()) {
            val body = strings.text("$term 还没有已排期的考试。", "No exams scheduled for $term yet.")
            return SysuRendered(
                summary = cardSummary(title, null, term),
                text = strings.text(
                    "$term 还没有已排期的考试。等排期后再查。",
                    "No exams scheduled for $term yet. Check again after the schedule is published.",
                ),
                card = emptyCard(SysuCardKind.Exams, title, term, body),
            )
        }
        val byDate = linkedMapOf<String, MutableList<SysuExam>>()
        for (exam in data.exams) {
            val date = SysuFormat.date(exam.date) ?: strings.text("未排期", "Unscheduled")
            byDate.getOrPut(date) { mutableListOf() } += exam
        }
        val sections = byDate.map { (date, exams) ->
            val weekday = SysuFormat.weekday(exams.first().weekday)
            val label = listOfNotNull(date, weekday).joinToString(" ")
            CliCardSection(
                label = label,
                items = exams.map { exam ->
                    val time = exam.time?.takeIf { it.isNotBlank() }
                    val place = SysuFormat.place(exam.place)
                    val sectionsLabel = SysuFormat.sections(exam.sections)
                    val teacher = exam.teacher?.takeIf { it.isNotBlank() }
                    val week = exam.week.scalarText()
                    val weekLabel = week?.let { strings.text("第 $it 周", "week $it") }
                    val typeLabel = exam.type?.takeIf { it.isNotBlank() }?.let { raw ->
                        if (raw == "exam") strings.text("考试", "exam") else raw
                    }
                    CliCardItem(
                        title = exam.name.orDash(),
                        subtitle = listOfNotNull(time, place).joinToString(" · ").ifEmpty { null },
                        meta = sectionsLabel,
                        body = bodyLines(
                            strings.text("教师", "Teacher") to teacher,
                            strings.text("周次", "Week") to weekLabel,
                            strings.text("类型", "Type") to typeLabel,
                        ),
                    )
                },
            )
        }
        val trailing = strings.text("$count 场", "$count")
        return SysuRendered(
            summary = cardSummary(title, trailing, term),
            text = cardGuidance(strings, "考试安排", "The exam schedule"),
            card = readyCard(
                kind = SysuCardKind.Exams,
                title = title,
                trailing = trailing,
                context = term,
                total = count,
                sections = sections,
            ),
        )
    }

    private fun credits(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val plans = SysuJson.credits(payload) ?: return null
        val title = strings.text("毕业学分", "Credits")
        val text = if (plans.size > 1) {
            strings.text(
                "培养方案并列返回，源数据没有标注主辅关系；回答时不要臆造主修/辅修。需要核对某一类学分时再查该培养方案。",
                "The plans are returned side by side and the source does not mark a primary plan, so do not infer major/minor. Query a plan again to check its categories.",
            )
        } else {
            strings.text(
                "培养方案数据已返回；需要核对某一类学分时再查该培养方案。",
                "The plan data is returned; query it again to check a specific credit category.",
            )
        }
        return SysuRendered(title, text, card = null)
    }

    private fun lmsCourses(payload: JsonElement, strings: SysuStrings): SysuRendered? {
        val courses = SysuJson.lmsCourses(payload) ?: return null
        val title = strings.text("在线教学", "LMS")
        if (courses.isEmpty()) {
            val body = strings.text("没有选课记录（或者课程都隐藏了）。", "No enrolled courses (or all are hidden).")
            return SysuRendered(
                summary = title,
                text = strings.text("没有选课记录。选课后重新查询即可。", "No enrolled courses. Check again after enrolling."),
                card = emptyCard(SysuCardKind.Lms, title, null, body),
            )
        }
        val rows = courses.map { course ->
            val id = course.id.scalarText()
            val name = course.name.orDash()
            val progress = course.progress.scalarText()?.let { "$it%" } ?: "—"
            val start = SysuFormat.date(course.start)
            val end = SysuFormat.date(course.end)
            val short = course.short?.takeIf { it.isNotBlank() }
            val hidden = course.hidden == true
            CliCardItem(
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
            text = cardGuidance(strings, "在线教学课程", "The course list"),
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
            return SysuRendered(
                summary = title,
                text = strings.text("没有待办事项。", "No assignments or to-dos."),
                card = emptyCard(SysuCardKind.Todo, title, null, body),
            )
        }
        val rows = items.map { item ->
            val whenText = SysuFormat.dateTime(item.at)
            val name = item.name.orDash()
            val course = item.course?.takeIf { it.isNotBlank() }
            val action = item.action?.takeIf { it.isNotBlank() }
            val url = item.url?.takeIf { it.isNotBlank() }
            CliCardItem(
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
            text = strings.text(
                "待办已整理好，截止时间按本地时间展示。",
                "The assignments are ready with due times in local time.",
            ),
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
            return SysuRendered(title, body, errorCard(SysuCardKind.Gym, title, body), isError = true)
        }
        val typeErrors = data.types.mapNotNull { type ->
            type.error?.takeIf { it.isNotBlank() }?.let { message ->
                listOfNotNull(type.name, type.id).joinToString(" · ") + "：" + message
            }
        }
        val problems = failures + typeErrors
        val failureMessages = data.errors.mapNotNull { it.error?.takeIf { message -> message.isNotBlank() } } +
            data.types.mapNotNull { it.error?.takeIf { message -> message.isNotBlank() } }
        val notices = data.types.mapNotNull { it.notice?.takeIf { notice -> notice.isNotBlank() } }
        if (data.types.isEmpty()) {
            if (problems.isNotEmpty()) {
                val body = failureMessage(problems.joinToString("\n"), SysuSite.Gym, strings)
                return SysuRendered(
                    summary = title,
                    text = failureGuidance(failureMessages, SysuSite.Gym, strings),
                    card = errorCard(SysuCardKind.Gym, title, body),
                    isError = true,
                )
            }
            val body = strings.text("没有可预约的场馆时段。", "No bookable venue slots.")
            return SysuRendered(
                summary = title,
                text = strings.text("没有可预约的场馆时段。换个日期或场地再查。", "No bookable slots. Try another date or venue."),
                card = emptyCard(SysuCardKind.Gym, title, null, body),
            )
        }
        val sections = mutableListOf<CliCardSection>()
        var venueCount = 0
        for (type in data.types) {
            if (type.error != null) continue
            val feeLabel = SysuFormat.fee(type.fee, strings)
            val noticeLabel = type.notice?.takeIf { it.isNotBlank() }?.let {
                strings.text("公告 ${SysuText.cleanInline(it)}", "Notice ${SysuText.cleanInline(it)}")
            }
            val rows = type.venues.map { venue ->
                val venueName = SysuFormat.venueName(type.name, venue)
                val display = SysuGymDisplay.slots(venue, strings)
                venueCount += 1
                CliCardItem(
                    title = venueName.orDash(),
                    subtitle = display.cardText,
                    meta = display.countLabel,
                )
            }
            if (rows.isNotEmpty()) {
                sections += CliCardSection(
                    label = listOfNotNull(type.name, feeLabel, noticeLabel).joinToString("｜"),
                    items = rows,
                )
            }
        }
        if (sections.isEmpty()) {
            val hasErrors = problems.isNotEmpty()
            val emptyBody = listOfNotNull(
                strings.text("没有可预约的场馆时段。", "No bookable venue slots."),
                notices.takeIf { it.isNotEmpty() }?.let {
                    strings.text("公告：${it.joinToString("；")}", "Notice: ${it.joinToString("; ")}")
                },
            ).joinToString("\n")
            val body = if (hasErrors) {
                failureMessage(problems.joinToString("\n"), SysuSite.Gym, strings)
            } else {
                emptyBody
            }
            val card = if (hasErrors) errorCard(SysuCardKind.Gym, title, body) else emptyCard(SysuCardKind.Gym, title, null, body)
            return SysuRendered(
                summary = title,
                text = if (hasErrors) {
                    failureGuidance(failureMessages, SysuSite.Gym, strings)
                } else {
                    strings.text("没有可预约的场馆时段。换个日期或场地再查。", "No bookable slots. Try another date or venue.")
                },
                card = card,
                isError = hasErrors,
            )
        }
        val windows = data.types.mapNotNull {
            listOfNotNull(SysuFormat.date(it.window?.start), SysuFormat.date(it.window?.end))
                .distinct()
                .joinToString(" ~ ")
                .ifEmpty { null }
        }.distinct()
        val footer = windows.singleOrNull()?.let { strings.text("预约窗口 $it", "Booking window $it") }
        val trailing = strings.text("$venueCount 个场地", "$venueCount venues")
        val guidance = if (failureMessages.isNotEmpty()) {
            strings.text(
                "场馆空位已整理好；部分类型查询失败（${failureMessages.joinToString("；")}），可重试。",
                "The venue availability is ready; some venue types failed (${failureMessages.joinToString("; ")}). Retry if needed.",
            )
        } else {
            cardGuidance(strings, "场馆空位", "The venue availability")
        }
        return SysuRendered(
            summary = cardSummary(title, trailing, null),
            text = guidance,
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
            return SysuRendered(title, body, errorCard(SysuCardKind.Netpay, title, body), isError = true)
        }
        val identityLine = identity.label
        if (data.services.isEmpty()) {
            val notice = strings.text("名下没有网络服务。", "No network services under this account.")
            return SysuRendered(
                summary = cardSummary(title, null, identityLine),
                text = strings.text("名下没有网络服务。开通后重新查询即可。", "No network services. Check again after activating one."),
                card = emptyCard(SysuCardKind.Netpay, title, identityLine, notice),
            )
        }
        val rows = data.services.map { service ->
            val name = service.service.orDash()
            val status = service.status?.takeIf { it.isNotBlank() }
            val expire = SysuFormat.date(service.expire) ?: service.expireText?.takeIf { it.isNotBlank() }
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
            CliCardItem(
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
            text = cardGuidance(strings, "网费服务", "The campus network status"),
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

    private fun cardGuidance(strings: SysuStrings, zh: String, en: String): String = strings.text(
        "${zh}已整理好。",
        "$en is ready.",
    )

    private fun bodyLines(vararg pairs: Pair<String, String?>): String? = pairs.mapNotNull { (label, value) ->
        val text = value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        "$label $text"
    }.joinToString("\n").ifEmpty { null }

    private fun failureMessage(raw: String, site: SysuSite, strings: SysuStrings): String =
        SysuFailures.actionable(raw, site, strings)
            ?: listOf(raw.trim(), strings.text("请稍后重试；仍失败时到「中山大学 CLI」插件页重新登录。", "Try again later; if it still fails, sign in again from the SYSU CLI plugin page."))
                .filter { it.isNotBlank() }
                .joinToString("\n")

    private fun failureGuidance(raw: List<String>, site: SysuSite, strings: SysuStrings): String {
        val messages = raw.map { SysuText.cleanInline(it) }.filter { it.isNotBlank() }
        if (messages.isEmpty()) {
            return strings.text("查询失败，可稍后重试。", "The query failed. Try again later.")
        }
        return SysuFailures.actionable(messages.joinToString("\n"), site, strings)
            ?: strings.text(
                "查询失败：${messages.joinToString("；")}。可稍后重试。",
                "The query failed: ${messages.joinToString("; ")}. Try again later.",
            )
    }

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
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val DEPARTMENT = Regex("""^([\u4e00-\u9fff]{2,4}学院)""")

    fun date(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (FULL_DATE.matches(text)) return text
        DATE_PREFIX.find(text)?.let { return it.groupValues[1] }
        return text
    }

    fun dateTime(value: String?, zone: ZoneId = ZONE): String? {
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
