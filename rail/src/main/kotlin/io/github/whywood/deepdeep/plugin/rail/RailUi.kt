package io.github.whywood.deepdeep.plugin.rail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdDatePicker
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.LocalPluginPageContext
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal const val RAIL_PAGE_ID = "main"

private const val RECENT_ROUTES_KEY = "recent_routes"

private const val CARD_TRAIN_LIMIT = 5

private const val CARD_SEAT_LIMIT = 4

private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

private sealed interface PageState {
    data object Idle : PageState
    data object Loading : PageState
    data class Failed(val message: String) : PageState
    data class Loaded(val details: TrainSearchDetails) : PageState
}

@Composable
internal fun TrainPage(host: PluginHost) {
    val colors = deepDeepColors()
    val pageContext = LocalPluginPageContext.current
    val arguments = remember(pageContext) { pageContext?.arguments }
    var from by rememberSaveable { mutableStateOf(arguments.stringArgument("from")) }
    var to by rememberSaveable { mutableStateOf(arguments.stringArgument("to")) }
    var date by rememberSaveable {
        mutableStateOf(
            arguments.stringArgument("date").takeIf { TravelDate.parse(it) != null } ?: TravelDate.today(),
        )
    }
    var state by remember { mutableStateOf<PageState>(PageState.Idle) }
    var showDatePicker by remember { mutableStateOf(false) }
    var recentRoutes by remember { mutableStateOf<List<RecentRoute>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (state == PageState.Loading) return
        val fromText = from.trim()
        val toText = to.trim()
        if (fromText.isEmpty() || toText.isEmpty()) {
            state = PageState.Failed(host.t("请填写出发和到达城市。", "Enter departure and arrival."))
            return
        }
        when (TrainServiceDateGuard.check(date)) {
            null -> Unit
            else -> {
                state = PageState.Failed(TrainServiceDateGuard.message(host, date))
                return
            }
        }
        state = PageState.Loading
        scope.launch {
            state = try {
                val details = TrainService(host).search(fromText, toText, date)
                rememberRoute(host, details.from, details.to)
                recentRoutes = loadRecentRoutes(host)
                PageState.Loaded(details)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                PageState.Failed(trainErrorMessage(host, error))
            }
        }
    }

    LaunchedEffect(Unit) {
        recentRoutes = loadRecentRoutes(host)
        if (from.isNotBlank() && to.isNotBlank()) runSearch()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = DdSpacing.Section,
                end = DdSpacing.Section,
                top = DdSpacing.Md,
                bottom = DdSpacing.Xxl,
            ),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        DdGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdTextField(
                        value = from,
                        onValueChange = { from = it },
                        placeholder = host.t("出发城市或车站", "From city or station"),
                        modifier = Modifier.weight(1f),
                        testTag = "rail_from",
                    )
                    DdIconButton(
                        icon = DdIcons.Sync,
                        onClick = {
                            val previous = from
                            from = to
                            to = previous
                        },
                        contentDescription = host.t("交换出发和到达", "Swap departure and arrival"),
                        tint = colors.inkSecondary,
                        iconSize = 18.dp,
                    )
                }
                DdTextField(
                    value = to,
                    onValueChange = { to = it },
                    placeholder = host.t("到达城市或车站", "To city or station"),
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "rail_to",
                )
                DdPressable(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    contentDescription = host.t("选择出发日期", "Choose travel date"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdIcon(icon = DdIcons.Clock, tint = colors.inkSecondary, size = 18.dp)
                        Spacer(Modifier.width(DdSpacing.Sm))
                        DdText(
                            text = host.t("出发日期", "Travel date"),
                            modifier = Modifier.weight(1f),
                            style = DdType.body,
                            color = colors.ink,
                        )
                        DdText(text = displayDate(date), style = DdType.body, color = colors.inkSecondary)
                        DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 16.dp)
                    }
                }
            }
        }

        DdPrimaryButton(
            text = if (state == PageState.Loading) {
                host.t("正在查询…", "Searching…")
            } else {
                host.t("查询车次", "Search trains")
            },
            onClick = ::runSearch,
            enabled = from.isNotBlank() && to.isNotBlank() && state != PageState.Loading,
            modifier = Modifier.fillMaxWidth(),
        )

        when (val current = state) {
            PageState.Idle -> {
                DdText(
                    text = host.t(
                        "输入出发和到达城市，查看 12306 车次与余票；购票在 12306 完成。",
                        "Enter departure and arrival to see 12306 trains and seats; booking happens on 12306.",
                    ),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
                if (recentRoutes.isNotEmpty()) {
                    DdSectionLabel(text = host.t("最近查询", "Recent routes"))
                    RecentRoutes(recentRoutes) { route ->
                        from = route.from
                        to = route.to
                        runSearch()
                    }
                }
            }

            PageState.Loading -> DdLoadingBlock(text = host.t("正在查询 12306…", "Searching 12306…"))

            is PageState.Failed -> DdNoticeBar(
                text = current.message,
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = ::runSearch,
            )

            is PageState.Loaded -> ResultsSection(
                host = host,
                details = current.details,
                onPickDate = { showDatePicker = true },
                onEditStations = { state = PageState.Idle },
            )
        }
    }

    if (showDatePicker) {
        DdDatePicker(
            selected = TravelDate.parse(date) ?: LocalDate.now(),
            onSelect = { picked ->
                date = picked.toString()
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
            title = host.t("选择出发日期", "Choose travel date"),
            previousMonthLabel = host.t("上个月", "Previous month"),
            nextMonthLabel = host.t("下个月", "Next month"),
        )
    }
}

@Composable
private fun RecentRoutes(routes: List<RecentRoute>, onSelect: (RecentRoute) -> Unit) {
    DdGroupCard {
        routes.forEachIndexed { index, route ->
            if (index > 0) DdHairline()
            DdListRow(
                title = "${route.from} → ${route.to}",
                icon = DdIcons.Clock,
                showChevron = true,
                onClick = { onSelect(route) },
            )
        }
    }
}

@Composable
private fun ResultsSection(
    host: PluginHost,
    details: TrainSearchDetails,
    onPickDate: () -> Unit,
    onEditStations: () -> Unit,
) {
    val colors = deepDeepColors()
    val bookingUrl = bookingLink(details)
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = "${details.from} → ${details.to}",
                    modifier = Modifier.weight(1f),
                    style = DdType.heading,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdText(
                    text = host.t("${details.trains.size} 趟", "${details.trains.size} trains"),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
            DdText(text = routeSubtitle(host, details), style = DdType.bodySmall, color = colors.inkTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DdHairline()
            if (details.trains.isEmpty()) {
                DdText(
                    text = host.t(
                        "这一天没有查到车次。可以换一天，或换个车站再查。",
                        "No trains found for this date. Try another day or nearby station.",
                    ),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
                    DdTextAction(text = host.t("换日期", "Change date"), onClick = onPickDate)
                    DdTextAction(text = host.t("换车站", "Change stations"), onClick = onEditStations)
                }
            } else {
                details.trains.forEachIndexed { index, train ->
                    if (index > 0) DdHairline()
                    TrainResultRow(host, train)
                }
            }
            DdHairline()
            DdText(
                text = host.t("余票以 12306 实时数据为准。", "Availability follows live 12306 data."),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
            if (bookingUrl.isNotBlank()) {
                DdTextAction(
                    text = host.t("去 12306 预订 →", "Book on 12306 →"),
                    onClick = { host.openBooking(bookingUrl) },
                )
            }
        }
    }
}

@Composable
internal fun TrainToolCard(host: PluginHost, state: ToolCardSlotState) {
    val colors = deepDeepColors()
    val result = state.result
    if (result == null) {
        DdGroupCard {
            DdLoadingBlock(
                modifier = Modifier.padding(vertical = DdSpacing.Lg),
                text = host.t("正在查询 12306…", "Searching 12306…"),
            )
        }
        return
    }
    if (result.isError) {
        DdNoticeBar(
            text = result.text ?: host.t("车次查询失败", "Train lookup failed"),
            tone = DdNoticeTone.DANGER,
        )
        return
    }
    val details = result.details?.let(::parseDetails)
    if (details == null) {
        DdGroupCard {
            DdText(
                text = result.text ?: host.t("没有车次数据", "No train data"),
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
        return
    }

    val bookingUrl = bookingLink(details)
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = "${details.from} → ${details.to}",
                    modifier = Modifier.weight(1f),
                    style = DdType.heading,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdText(
                    text = host.t("${details.trains.size} 趟", "${details.trains.size} trains"),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
            DdText(text = routeSubtitle(host, details), style = DdType.bodySmall, color = colors.inkTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (details.trains.isEmpty()) {
                DdText(
                    text = host.t(
                        "这一天没有查到车次，可以换个日期或车站。",
                        "No trains for this date. Try another date or station.",
                    ),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = host.t("修改查询条件 →", "Change search →"),
                    onClick = { host.openTrainPage(details) },
                )
            } else {
                DdHairline()
                details.trains.take(CARD_TRAIN_LIMIT).forEachIndexed { index, train ->
                    if (index > 0) DdHairline()
                    TrainResultRow(host, train)
                }
                if (details.trains.size > CARD_TRAIN_LIMIT) {
                    DdHairline()
                    DdTextAction(
                        text = host.t(
                            "查看全部 ${details.trains.size} 趟 →",
                            "View all ${details.trains.size} trains →",
                        ),
                        onClick = { host.openTrainPage(details) },
                    )
                }
            }
            DdHairline()
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = host.t("余票以 12306 为准", "Availability from 12306"),
                    modifier = Modifier.weight(1f),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
                if (bookingUrl.isNotBlank()) {
                    DdTextAction(
                        text = host.t("去 12306 预订", "Book on 12306"),
                        onClick = { host.openBooking(bookingUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrainResultRow(host: PluginHost, train: TrainOption) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdText(text = train.code, style = DdType.body, color = colors.ink)
            Spacer(Modifier.weight(1f))
            DdText(text = "${train.depart} → ${arrivalLabel(train.depart, train.arrive, train.duration)}", style = DdType.body, color = colors.ink)
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(text = train.duration, style = DdType.bodySmall, color = colors.inkTertiary)
        }
        DdText(
            text = "${train.fromStation} → ${train.toStation}",
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DdText(
            text = seatLine(host, train.seats),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun seatLine(host: PluginHost, seats: List<SeatAvailability>): String =
    seats.take(CARD_SEAT_LIMIT)
        .joinToString(" · ") { "${seatLabel({ zh, en -> host.t(zh, en) }, it.name)} ${it.count}" }
        .ifEmpty { "—" }

@Composable
private fun displayDate(raw: String): String {
    val parsed = TravelDate.parse(raw) ?: return raw
    return parsed.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

private fun parseDetails(details: JsonObject): TrainSearchDetails? = runCatching {
    JsonFormat.decodeFromJsonElement(TrainSearchDetails.serializer(), details)
}.getOrNull()

@Composable
private fun routeSubtitle(host: PluginHost, details: TrainSearchDetails): String = buildString {
    append(displayDate(details.date))
    if (details.fromCode.isNotBlank() && details.toCode.isNotBlank()) {
        append(" · ").append(details.fromCode).append(" → ").append(details.toCode)
    }
    append(" · ").append(host.t("数据时间 ${dataClock(details.fetchedAt)}", "data ${dataClock(details.fetchedAt)}"))
}

private fun bookingLink(details: TrainSearchDetails): String = details.bookingUrl.takeIf { it.isNotBlank() }
    ?: if (details.fromCode.isNotBlank() && details.toCode.isNotBlank()) {
        RailEndpoints.bookingUrl(details.from, details.fromCode, details.to, details.toCode, details.date)
    } else {
        ""
    }

private fun PluginHost.openBooking(url: String) {
    if (url.isBlank()) return
    runCatching { ui().openUrl(url) }
        .onFailure { runCatching { ui().toast(t("无法打开 12306", "Couldn't open 12306")) } }
}

private fun PluginHost.openTrainPage(details: TrainSearchDetails) {
    val arguments = buildJsonObject {
        put("from", details.from)
        put("to", details.to)
        put("date", details.date)
    }
    runCatching { ui().openPageWithArguments(RAIL_PAGE_ID, arguments) }
        .onFailure { runCatching { ui().toast(t("无法打开火车票页面", "Couldn't open the Trains page")) } }
}

private fun JsonObject?.stringArgument(name: String): String =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

private suspend fun loadRecentRoutes(host: PluginHost): List<RecentRoute> = runCatching {
    host.settings().get(RECENT_ROUTES_KEY)?.let { raw ->
        JsonFormat.decodeFromJsonElement(
            ListSerializer(RecentRoute.serializer()),
            JsonFormat.parseToJsonElement(raw),
        )
    }
}.getOrNull().orEmpty()

private suspend fun rememberRoute(host: PluginHost, from: String, to: String) {
    val next = mergeRecentRoutes(loadRecentRoutes(host), from, to)
    runCatching {
        host.settings().put(
            RECENT_ROUTES_KEY,
            JsonFormat.encodeToJsonElement(ListSerializer(RecentRoute.serializer()), next).toString(),
        )
    }
}

internal fun mergeRecentRoutes(
    current: List<RecentRoute>,
    from: String,
    to: String,
    limit: Int = 3,
): List<RecentRoute> {
    if (from.isBlank() || to.isBlank()) return current.take(limit)
    return (listOf(RecentRoute(from, to)) + current.filterNot { it.from == from && it.to == to })
        .take(limit)
}

internal object TrainServiceDateGuard {
    fun check(date: String): TravelDateIssue? = TravelDate.validate(date).takeUnless { it == TravelDateIssue.VALID }

    fun message(host: PluginHost, date: String): String = when (TravelDate.validate(date)) {
        TravelDateIssue.MALFORMED -> host.t(
            "日期格式应为 YYYY-MM-DD，例如 ${TravelDate.today()}。",
            "Date must be YYYY-MM-DD, for example ${TravelDate.today()}.",
        )

        TravelDateIssue.BEFORE_TODAY -> host.t(
            "只能查今天及以后的日期。",
            "Only today or later can be searched.",
        )

        TravelDateIssue.BEYOND_WINDOW -> host.t(
            "只能查今天起 $RAIL_DATE_WINDOW_DAYS 天内的日期。",
            "Dates must be within $RAIL_DATE_WINDOW_DAYS days from today.",
        )

        TravelDateIssue.VALID -> ""
    }
}
