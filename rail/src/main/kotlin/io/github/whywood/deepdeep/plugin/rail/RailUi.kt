package io.github.whywood.deepdeep.plugin.rail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdDatePicker
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

private val RailCardShape = RoundedCornerShape(20.dp)

private const val CARD_TRAIN_PREVIEW = 3

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
        TrainTicketError(host, result.text ?: host.t("车次查询失败", "Train lookup failed"))
        return
    }
    val details = result.details?.let(::parseDetails)
    if (details == null) {
        TrainTicketFallback(host, result.text)
        return
    }
    if (details.trains.isEmpty()) {
        TrainTicketEmpty(host, details)
        return
    }
    val appear = rememberTrainAppear()
    val bookingUrl = bookingLink(details)
    RailSurface(Modifier.graphicsLayer { alpha = appear }) {
        TrainTicketHeader(host, details)
        DdHairline(Modifier.padding(horizontal = 16.dp))
        details.trains.take(CARD_TRAIN_PREVIEW).forEach { train ->
            TrainTicketRow(host, details, train)
            DdHairline(Modifier.padding(horizontal = 16.dp))
        }
        if (details.trains.size > CARD_TRAIN_PREVIEW) {
            DdText(
                text = host.t(
                    "还有 ${details.trains.size - CARD_TRAIN_PREVIEW} 趟车次",
                    "${details.trains.size - CARD_TRAIN_PREVIEW} more trains",
                ),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 3.dp),
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = deepDeepColors().inkTertiary,
            )
        }
        TrainTicketFooter(host, details, bookingUrl)
    }
}

@Composable
private fun TrainTicketHeader(host: PluginHost, details: TrainSearchDetails) {
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
            val meta = listOfNotNull(
                if (details.fromCode.isNotBlank() && details.toCode.isNotBlank()) "${details.fromCode} → ${details.toCode}" else null,
                host.t("共 ${details.trains.size} 趟", "${details.trains.size} trains"),
                host.t("数据时间 ${dataClock(details.fetchedAt)}", "data ${dataClock(details.fetchedAt)}"),
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
private fun TrainTicketRow(host: PluginHost, details: TrainSearchDetails, train: TrainOption) {
    val colors = deepDeepColors()
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    val nextDay = trainArrivesNextDay(train)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TicketTime(
                time = train.depart,
                station = train.fromStation,
                horizontalAlignment = Alignment.Start,
            )
            Column(
                Modifier.weight(1f).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DdText(
                    text = train.duration,
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DdHairline(Modifier.weight(1f))
                    DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 12.dp)
                }
            }
            TicketTime(
                time = train.arrive,
                station = train.toStation,
                horizontalAlignment = Alignment.End,
                nextDay = nextDay,
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RailTag(train.code, RailTagTone.ACCENT)
            val fromChanged = train.fromStation != details.from
            val toChanged = train.toStation != details.to
            if (fromChanged) {
                Spacer(Modifier.width(5.dp))
                RailTag(host.t("${train.fromStation}出发", "from ${train.fromStation}"), RailTagTone.WARNING)
            }
            if (toChanged) {
                Spacer(Modifier.width(5.dp))
                RailTag(host.t("到${train.toStation}", "to ${train.toStation}"), RailTagTone.WARNING)
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            train.seats.take(CARD_SEAT_LIMIT).forEach { seat ->
                Column {
                    DdText(
                        text = seatLabel(translate, seat.name),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 13.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                    DdText(
                        text = seat.count,
                        style = DdType.label.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                }
            }
            if (train.seats.isEmpty()) {
                DdText(
                    text = host.t("余票数据暂缺", "No seat data"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TicketTime(time: String, station: String, horizontalAlignment: Alignment.Horizontal, nextDay: Boolean = false) {
    val colors = deepDeepColors()
    Column(Modifier.width(88.dp), horizontalAlignment = horizontalAlignment) {
        DdText(
            text = time,
            style = DdType.heading.copy(fontSize = 24.sp, lineHeight = 27.sp),
            color = colors.ink,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (nextDay) {
                DdText(
                    text = hostNextDayLabel(),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = colors.warning,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            DdText(
                text = station,
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun hostNextDayLabel(): String = nextDayLabelText

private val nextDayLabelText: String = "次日"

@Composable
private fun TrainTicketFooter(host: PluginHost, details: TrainSearchDetails, bookingUrl: String) {
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
                    Column {
                        RailSkeleton(width = 78.dp, height = 24.dp)
                        Spacer(Modifier.height(5.dp))
                        RailSkeleton(width = 46.dp, height = 10.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        RailSkeleton(width = 78.dp, height = 24.dp)
                        Spacer(Modifier.height(5.dp))
                        RailSkeleton(width = 46.dp, height = 10.dp)
                    }
                }
                Spacer(Modifier.height(9.dp))
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
private fun TrainTicketError(host: PluginHost, message: String) {
    val colors = deepDeepColors()
    RailSurface {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(colors.danger))
            Spacer(Modifier.width(DdSpacing.Md))
            DdText(
                text = message,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
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

private fun trainArrivesNextDay(train: TrainOption): Boolean {
    val depart = clockMinutes(train.depart) ?: return train.arrive < train.depart
    val duration = clockMinutes(train.duration) ?: return train.arrive < train.depart
    return (depart + duration) / (24 * 60) > 0
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
