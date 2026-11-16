package io.github.whywood.deepdeep.plugin.rail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdDatePicker
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
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
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
                val result = TrainService(host).search(fromText, toText, date)
                rememberRoute(host, result.details.from, result.details.to)
                recentRoutes = loadRecentRoutes(host)
                PageState.Loaded(result.details)
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
    val groups = remember(details) { groupTrains(details.trains) }
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
                    text = if (groups.size == 1) host.t("1 趟", "1 train") else host.t("${groups.size} 趟", "${groups.size} trains"),
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

private val RailCardShape = RoundedCornerShape(20.dp)

private const val CARD_GROUP_PREVIEW = 4

private enum class RailTagTone { NEUTRAL, ACCENT, WARNING }

@Composable
private fun RailSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = deepDeepColors()
    Column(modifier.fillMaxWidth().clip(RailCardShape).background(colors.surface)) {
        content()
    }
}

@Composable
private fun rememberTrainAppear(): Float {
    val motion = LocalDdMotionEnabled.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible || !motion) 1f else 0f,
        animationSpec = tween(durationMillis = if (motion) 200 else 0),
        label = "rail-card-appear",
    )
    return alpha
}

@Composable
private fun RailTag(text: String, tone: RailTagTone = RailTagTone.NEUTRAL) {
    val colors = deepDeepColors()
    val color = when (tone) {
        RailTagTone.NEUTRAL -> colors.inkSecondary
        RailTagTone.ACCENT -> colors.accent
        RailTagTone.WARNING -> colors.warning
    }
    val background = when (tone) {
        RailTagTone.NEUTRAL -> colors.wash
        RailTagTone.ACCENT -> colors.accentSoft
        RailTagTone.WARNING -> colors.warning.copy(alpha = 0.12f)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        DdText(
            text = text,
            style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RailSkeleton(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val colors = deepDeepColors()
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(6.dp)).background(colors.wash))
}

@Composable
internal fun TrainToolCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        TrainTicketLoading(host)
        return
    }
    if (result.isError) {
        TrainTicketError(host, errorMessage(result))
        return
    }
    val details = parseSearchDetails(result.details)
    if (details == null) {
        TrainTicketFallback(host, result.text)
        return
    }
    if (details.trains.isEmpty()) {
        TrainTicketEmpty(host, details)
        return
    }
    TrainSearchCard(host, details)
}

@Composable
private fun TrainSearchCard(host: PluginHost, details: TrainSearchDetails) {
    val appear = rememberTrainAppear()
    val bookingUrl = bookingLink(details)
    val groups = remember(details) { groupTrains(details.trains) }
    var expanded by rememberSaveable(details.date, details.from, details.to) { mutableStateOf(false) }
    val visibleGroups = if (expanded) groups else groups.take(CARD_GROUP_PREVIEW)
    RailSurface(Modifier.graphicsLayer { alpha = appear }) {
        TrainTicketHeader(host, details, groups.size)
        DdHairline(Modifier.padding(horizontal = 16.dp))
        visibleGroups.forEachIndexed { index, group ->
            if (index > 0) DdHairline(Modifier.padding(horizontal = 16.dp))
            TrainGroupBlock(host, details, group)
        }
        if (groups.size > CARD_GROUP_PREVIEW) {
            DdHairline(Modifier.padding(horizontal = 16.dp))
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.t(
                    "展开全部 ${groups.size} 趟",
                    "Show all ${groups.size} trains",
                ),
                expandedLabel = host.t("收起车次", "Collapse trains"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        TrainTicketFooter(host, bookingUrl)
    }
}

@Composable
private fun TrainTicketHeader(host: PluginHost, details: TrainSearchDetails, groupCount: Int) {
    val colors = deepDeepColors()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                DdText(
                    text = details.from,
                    style = DdType.heading.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdText(
                    text = " → ",
                    style = DdType.chip.copy(fontSize = 15.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
                DdText(
                    text = details.to,
                    style = DdType.heading.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            val trainLabel = if (groupCount == 1) host.t("1 趟", "1 train") else host.t("$groupCount 趟", "$groupCount trains")
            val meta = listOfNotNull(
                trainLabel,
                host.t("${dataClock(details.fetchedAt)} 更新", "updated ${dataClock(details.fetchedAt)}"),
            ).joinToString(" · ")
            DdText(
                text = meta,
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DdSpacing.Sm))
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accentSoft)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            DdText(
                text = railDateLabel(host, details.date),
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrainGroupBlock(host: PluginHost, details: TrainSearchDetails, group: TrainGroup) {
    val colors = deepDeepColors()
    val trainTimeStyle = DdType.heading.copy(fontSize = 20.sp, lineHeight = 24.sp)
    val trainStationStyle = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val trainCodeStyle = DdType.numeric.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val trainHintStyle = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp)
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    val primary = group.primary(details.to)
    val variants = group.trains.filter { it !== primary }
    val offDate = primary.takeIf { it.date.isNotBlank() && !railSameTicketDate(it.date, details.date) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                DdText(
                    text = primary.depart,
                    style = trainTimeStyle,
                    color = colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = primary.fromStation.takeIf { it.isNotBlank() } ?: details.from,
                    style = trainStationStyle,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.weight(0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
                DdText(
                    text = primary.code,
                    style = trainCodeStyle,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = trainCenterLabel(host, primary, translate),
                    style = trainHintStyle,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (railNextDayArrival(primary)) {
                        DdText(
                            text = host.t("次日", "next day"),
                            style = trainHintStyle,
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    DdText(
                        text = primary.arrive,
                        style = trainTimeStyle,
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = primary.toStation.takeIf { it.isNotBlank() } ?: details.to,
                    style = trainStationStyle,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        SeatLine(seats = primary.seats, translate = translate)
        if (variants.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            DdText(
                text = host.t(
                    "另有 " + variants.joinToString("、") { extraArrivalLabel(it, primary) },
                    "also " + variants.joinToString("; ") { extraArrivalLabel(it, primary) },
                ),
                style = trainHintStyle,
                color = colors.inkTertiary,
                maxLines = 2,
            )
        }
        if (offDate != null) {
            Spacer(Modifier.height(5.dp))
            DdText(
                text = host.t(
                    "发车日期 ${railCompactTicketDate(offDate.date)}",
                    "departs ${railCompactTicketDate(offDate.date)}",
                ),
                style = trainHintStyle,
                color = colors.warning,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SeatLine(seats: List<SeatAvailability>, translate: RailTranslate) {
    val colors = deepDeepColors()
    val trainHintStyle = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        seats.filter { it.status != SeatStatus.NOT_OFFERED }.forEach { seat ->
            val statusColor = when (seat.status) {
                SeatStatus.AVAILABLE -> colors.accent
                SeatStatus.SOLD_OUT, SeatStatus.NOT_RETURNED -> colors.inkTertiary
                SeatStatus.NOT_ON_SALE -> colors.warning
                SeatStatus.NOT_OFFERED -> return@forEach
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = seatLabel(translate, seat),
                    style = trainHintStyle.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(3.dp))
                DdText(
                    text = shortSeatStatus(translate, seat),
                    style = trainHintStyle.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun shortSeatStatus(translate: RailTranslate, seat: SeatAvailability): String = when {
    seat.status == SeatStatus.AVAILABLE && seat.count.toIntOrNull() == null -> translate("有", "available")
    seat.status == SeatStatus.SOLD_OUT -> translate("无", "sold out")
    else -> seatStatusText(translate, seat)
}

private fun trainCenterLabel(host: PluginHost, train: TrainOption, translate: RailTranslate): String {
    val type = trainTypeLabel(host, train.code)
    val duration = railDurationLabel(translate, train.duration)
    return listOfNotNull(type, duration).joinToString(" · ")
}

internal fun railDurationLabel(translate: RailTranslate, raw: String): String? {
    val minutes = durationMinutes(raw) ?: return raw.takeIf { it.isNotBlank() }
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 && rest > 0 -> translate("${hours}小时${rest}分", "${hours}h ${rest}m")
        hours > 0 -> translate("${hours}小时", "${hours}h")
        else -> translate("${rest}分", "${rest}m")
    }
}

private fun trainTypeLabel(host: PluginHost, code: String): String? = when (code.firstOrNull()?.uppercaseChar()) {
    'G' -> host.t("高铁", "High-speed")
    'D' -> host.t("动车", "EMU")
    'C' -> host.t("城际", "Intercity")
    'K' -> host.t("快速", "Express")
    'T' -> host.t("特快", "Special express")
    'Z' -> host.t("直达", "Direct")
    else -> null
}

internal fun railNextDayArrival(train: TrainOption): Boolean {
    val depart = clockMinutes(train.depart) ?: return false
    val duration = durationMinutes(train.duration)
    if (duration != null) return (depart + duration) / MINUTES_PER_DAY > 0
    val arrive = clockMinutes(train.arrive) ?: return false
    return arrive < depart
}

private fun durationMinutes(raw: String): Int? {
    val parts = raw.trim().split(':')
    if (parts.size < 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours < 0 || hours > 99 || minutes !in 0..59) return null
    return hours * 60 + minutes
}

private fun extraArrivalLabel(train: TrainOption, primary: TrainOption): String {
    val arrival = if (train.toStation.isNotBlank() && train.toStation != primary.toStation) {
        " 到 ${train.toStation}"
    } else {
        ""
    }
    return "${train.fromStation} ${train.depart}$arrival"
}

internal fun railSameTicketDate(raw: String, query: String): Boolean {
    if (raw.isBlank()) return true
    val left = raw.filter { it.isDigit() }
    val right = query.filter { it.isDigit() }
    return left.isNotEmpty() && left == right
}

internal fun railCompactTicketDate(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return if (digits.length >= 8) "${digits.substring(4, 6)}-${digits.substring(6, 8)}" else raw
}

private const val MINUTES_PER_DAY = 24 * 60

@Composable
private fun TrainTicketFooter(host: PluginHost, bookingUrl: String) {
    val colors = deepDeepColors()
    DdHairline()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = host.t("余票以 12306 实时数据为准", "Availability follows live 12306 data"),
            modifier = Modifier.weight(1f),
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (bookingUrl.isNotBlank()) {
            DdTextAction(
                text = host.t("去 12306 预订 →", "Book on 12306 →"),
                onClick = { host.openBooking(bookingUrl) },
            )
        }
    }
}

@Composable
internal fun RailPriceCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        RailPriceLoading(host)
        return
    }
    if (result.isError) {
        TrainTicketError(host, errorMessage(result))
        return
    }
    val details = parsePriceDetails(result.details)
    if (details == null) {
        TrainTicketFallback(host, result.text)
        return
    }
    RailPriceSurface(host, details)
}

@Composable
private fun RailPriceSurface(host: PluginHost, details: TrainPriceDetails) {
    val colors = deepDeepColors()
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    val appear = rememberTrainAppear()
    RailSurface(Modifier.graphicsLayer { alpha = appear }) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailTag(details.code, RailTagTone.ACCENT)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = "${details.fromPlace} → ${details.toPlace}",
                    style = DdType.heading.copy(fontSize = 18.sp, lineHeight = 23.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = host.t(
                        "${dataClock(details.fetchedAt)} 更新",
                        "updated ${dataClock(details.fetchedAt)}",
                    ),
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accentSoft)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                DdText(
                    text = railDateLabel(host, details.date),
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.accent,
                    maxLines = 1,
                )
            }
        }
        DdHairline(Modifier.padding(horizontal = 16.dp))
        details.prices.forEachIndexed { index, price ->
            if (index > 0) DdHairline(Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdText(
                    text = seatLabel(translate, price.name),
                    modifier = Modifier.weight(1f),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
                DdText(
                    text = price.priceText,
                    style = DdType.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 1,
                )
            }
        }
        DdHairline()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = host.t("实际金额以 12306 下单页为准", "The booking page is final"),
                modifier = Modifier.weight(1f),
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (details.bookingUrl.isNotBlank()) {
                DdTextAction(
                    text = host.t("去 12306 预订 →", "Book on 12306 →"),
                    onClick = { host.openBooking(details.bookingUrl) },
                )
            }
        }
    }
}

@Composable
private fun RailPriceLoading(host: PluginHost) {
    val colors = deepDeepColors()
    RailSurface {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailSkeleton(width = 54.dp, height = 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                RailSkeleton(width = 158.dp, height = 16.dp)
                Spacer(Modifier.height(6.dp))
                RailSkeleton(width = 96.dp, height = 10.dp)
            }
            RailSkeleton(width = 58.dp, height = 22.dp)
        }
        DdHairline(Modifier.padding(horizontal = 16.dp))
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RailSkeleton(width = 64.dp, height = 12.dp)
                Spacer(Modifier.weight(1f))
                RailSkeleton(width = 52.dp, height = 12.dp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdSpinner(color = colors.inkTertiary, size = 14.dp, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(7.dp))
            DdText(
                text = host.t("正在查询 12306…", "Searching 12306…"),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
private fun TrainTicketLoading(host: PluginHost) {
    val colors = deepDeepColors()
    RailSurface {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                RailSkeleton(width = 148.dp, height = 18.dp)
                Spacer(Modifier.height(7.dp))
                RailSkeleton(width = 206.dp, height = 11.dp)
            }
            RailSkeleton(width = 58.dp, height = 22.dp)
        }
        DdHairline(Modifier.padding(horizontal = 16.dp))
        repeat(2) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RailSkeleton(width = 54.dp, height = 16.dp)
                    Spacer(Modifier.width(10.dp))
                    RailSkeleton(width = 78.dp, height = 12.dp)
                    Spacer(Modifier.weight(1f))
                    RailSkeleton(width = 44.dp, height = 10.dp)
                }
                Spacer(Modifier.height(8.dp))
                RailSkeleton(width = 176.dp, height = 10.dp)
            }
            DdHairline(Modifier.padding(horizontal = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdSpinner(color = colors.inkTertiary, size = 14.dp, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(7.dp))
            DdText(
                text = host.t("正在查询 12306…", "Searching 12306…"),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
private fun TrainTicketError(host: PluginHost, message: String?) {
    val colors = deepDeepColors()
    RailSurface {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(colors.danger))
            Spacer(Modifier.width(DdSpacing.Md))
            DdText(
                text = message ?: host.t("车次查询失败", "Train lookup failed"),
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
            )
            DdTextAction(
                text = host.t("打开火车票页面", "Open Trains"),
                onClick = { host.openRailPage() },
            )
        }
    }
}

@Composable
private fun TrainTicketEmpty(host: PluginHost, details: TrainSearchDetails) {
    val colors = deepDeepColors()
    RailSurface {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(colors.wash), contentAlignment = Alignment.Center) {
                DdIcon(icon = DdIcons.Clock, tint = colors.inkTertiary, size = 20.dp)
            }
            Spacer(Modifier.height(10.dp))
            DdText(
                text = host.t("这天没有查到车次", "No trains for this date"),
                style = DdType.label,
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            DdText(
                text = host.t("可以换一天，或换个车站再查", "Try another date or station"),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            DdText(
                text = "${details.from} → ${details.to} · ${railDateLabel(host, details.date)}",
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DdHairline()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = host.t("余票以 12306 实时数据为准", "Availability follows live 12306 data"),
                modifier = Modifier.weight(1f),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DdTextAction(
                text = host.t("打开火车票页面", "Open Trains"),
                onClick = { host.openTrainPage(details) },
            )
        }
    }
}

@Composable
private fun TrainTicketFallback(host: PluginHost, text: String?) {
    val colors = deepDeepColors()
    RailSurface {
        DdText(
            text = text ?: host.t("没有车次数据", "No train data"),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
    }
}

private fun railDateLabel(host: PluginHost, raw: String): String {
    val parsed = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
        ?: return raw
    val zh = "${parsed.monthValue}月${parsed.dayOfMonth}日"
    val en = parsed.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    return host.t(zh, en)
}

private fun PluginHost.openRailPage() {
    runCatching { ui().openPage(RAIL_PAGE_ID) }
        .onFailure { runCatching { ui().toast(t("无法打开火车票页面", "Couldn't open the Trains page")) } }
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
        )
    }
}

private fun seatLine(host: PluginHost, seats: List<SeatAvailability>): String {
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    return trainSeatLine(seats, translate).ifBlank { "—" }
}

@Composable
private fun displayDate(raw: String): String {
    val parsed = TravelDate.parse(raw) ?: return raw
    return parsed.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

private fun cardPayload(details: JsonObject?): JsonObject? =
    (details?.get(RAIL_CARD_DETAILS_KEY) as? JsonObject)?.get("payload") as? JsonObject

private fun modelPayload(details: JsonObject?): JsonObject? = cardPayload(details) ?: details

private fun parseSearchDetails(details: JsonObject?): TrainSearchDetails? {
    val payload = modelPayload(details) ?: return null
    return runCatching {
        JsonFormat.decodeFromJsonElement(TrainSearchDetails.serializer(), payload)
    }.getOrNull()
}

private fun parsePriceDetails(details: JsonObject?): TrainPriceDetails? {
    val payload = modelPayload(details) ?: return null
    return runCatching {
        JsonFormat.decodeFromJsonElement(TrainPriceDetails.serializer(), payload)
    }.getOrNull()
}

private fun errorMessage(result: PluginToolResult): String? =
    (result.details?.get(RAIL_MESSAGE_DETAILS_KEY) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: result.text?.takeIf { it.isNotBlank() }

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
