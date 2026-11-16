package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val GYM_TYPE_PREVIEW = 2
private val GYM_VENUE_LABEL_WIDTH = 88.dp
private val GYM_COLUMN_WIDTH = 76.dp
private val GYM_HEADER_HEIGHT = 36.dp
private val GYM_ROW_HEIGHT = 46.dp

@Composable
internal fun SysuGymCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.gymPlan(model) }
    if (plan == null || plan.types.isEmpty()) {
        SysuFallbackListCard(host, model)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val colors = deepDeepColors()
    val types = if (expanded) plan.types else plan.types.take(GYM_TYPE_PREVIEW)
    val hiddenTypes = (plan.types.size - GYM_TYPE_PREVIEW).coerceAtLeast(0)
    SysuCardSurface {
        SysuTopLine(
            kicker = host.t("体育场馆", "Sports venues"),
            title = host.t("可预约时段", "Bookable slots"),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        types.forEachIndexed { typeIndex, type ->
            if (typeIndex > 0) Spacer(Modifier.height(DdSpacing.Lg))
            SysuGymTypeHeader(type = type)
            Spacer(Modifier.height(DdSpacing.Sm))
            if (type.venues.isEmpty()) {
                DdText(
                    text = host.t("没有可约时段", "No available slots"),
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                )
            } else {
                SysuGymMatrix(host = host, type = type)
            }
        }
        if (hiddenTypes > 0) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.t("展开其余 $hiddenTypes 类场馆", "$hiddenTypes more venue types"),
                expandedLabel = host.t("收起", "Show less"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SysuFootLine(
            left = plan.window?.let { host.t("预约窗口 $it", "Booking window $it") }
                ?: model.note?.takeIf { it.isNotBlank() },
            right = host.t("${plan.venueCount} 个场地", "${plan.venueCount} venues"),
        )
    }
}

@Composable
private fun SysuGymTypeHeader(type: SysuGymTypePlan) {
    val colors = deepDeepColors()
    Column(Modifier.fillMaxWidth()) {
        DdText(
            text = type.name,
            style = SysuCardType.bodyStrong,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        type.fee?.takeIf { it.isNotBlank() }?.let { fee ->
            Spacer(Modifier.height(1.dp))
            DdText(
                text = fee,
                style = SysuCardType.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                color = colors.inkSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SysuGymMatrix(host: PluginHost, type: SysuGymTypePlan) {
    val colors = deepDeepColors()
    val columns = remember(type.venues) {
        type.venues
            .flatMap { venue -> venue.slots }
            .map { slot -> SysuGymColumn(slot.date, slot.time) }
            .distinct()
            .sortedWith(compareBy({ it.date.orEmpty() }, { it.time.substringBefore('-') }))
    }
    if (columns.isEmpty()) {
        DdText(
            text = host.t("没有可约时段", "No available slots"),
            style = SysuCardType.caption,
            color = colors.inkSecondary,
        )
        return
    }
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(GYM_VENUE_LABEL_WIDTH)) {
            Spacer(Modifier.height(GYM_HEADER_HEIGHT))
            type.venues.forEach { venue ->
                Box(
                    modifier = Modifier.height(GYM_ROW_HEIGHT).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    DdText(
                        text = sysuGymVenueLabel(type.name, venue.name),
                        style = SysuCardType.caption.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(DdSpacing.Xs))
        Column(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scroll),
        ) {
            Row {
                columns.forEach { column ->
                    SysuGymColumnHeader(column = column)
                }
            }
            type.venues.forEach { venue ->
                Row {
                    columns.forEach { column ->
                        val slot = venue.slots.firstOrNull { it.date == column.date && it.time == column.time }
                        SysuGymCell(host = host, slot = slot)
                    }
                }
            }
        }
    }
}

private data class SysuGymColumn(
    val date: String?,
    val time: String,
)

@Composable
private fun SysuGymColumnHeader(column: SysuGymColumn) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .width(GYM_COLUMN_WIDTH)
            .height(GYM_HEADER_HEIGHT)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DdText(
            text = column.date?.takeLast(5) ?: "—",
            style = SysuCardType.micro.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = colors.inkTertiary,
            maxLines = 1,
        )
        DdText(
            text = column.time,
            style = SysuCardType.micro.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SysuGymCell(host: PluginHost, slot: SysuGymSlotPlan?) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .width(GYM_COLUMN_WIDTH)
            .height(GYM_ROW_HEIGHT)
            .padding(2.dp),
    ) {
        if (slot == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(DdShapes.Small)
                    .background(colors.wash.copy(alpha = 0.35f)),
            )
            return@Box
        }
        val full = slot.remaining != null && slot.remaining <= 0
        val tone = when {
            full -> colors.danger
            slot.longWindow -> colors.warning
            else -> colors.accent
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(DdShapes.Small)
                .background(tone.copy(alpha = if (colors.dark) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DdText(
                    text = when {
                        full -> host.t("已满", "Full")
                        slot.remaining != null -> host.t("余 ${slot.remaining}", "${slot.remaining} left")
                        else -> "—"
                    },
                    style = SysuCardType.caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = tone,
                    maxLines = 1,
                )
                if (slot.longWindow) {
                    DdText(
                        text = host.t("长时段", "long"),
                        style = SysuCardType.micro.copy(fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 0.sp),
                        color = tone,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun sysuGymVenueLabel(typeName: String, name: String): String {
    val trimmed = name.removePrefix(typeName).trimStart(' ', '-', '—', '·')
    return trimmed.ifEmpty { name }
}
