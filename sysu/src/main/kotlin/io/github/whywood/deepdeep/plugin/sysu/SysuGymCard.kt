package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val MAX_GYM_TYPES = 2
private const val MAX_GYM_VENUES = 2

@Composable
internal fun SysuGymCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.gymPlan(model) }
    if (plan == null || plan.types.isEmpty()) {
        SysuFallbackListCard(host, model)
        return
    }
    val colors = deepDeepColors()
    val types = plan.types.take(MAX_GYM_TYPES)
    val hiddenTypes = plan.types.size - types.size
    SysuCardSurface {
        SysuTopLine(
            kicker = host.t("体育场馆", "Sports venues"),
            title = host.t("可预约时段", "Bookable slots"),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        types.forEachIndexed { typeIndex, type ->
            if (typeIndex > 0) Spacer(Modifier.height(DdSpacing.Md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdText(
                    text = type.name,
                    modifier = Modifier.weight(1f),
                    style = SysuCardType.bodyStrong,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                type.fee?.let { fee ->
                    Spacer(Modifier.width(DdSpacing.Sm))
                    SysuPill(text = fee, foreground = colors.accent, background = colors.accentSoft)
                }
            }
            val venues = type.venues.take(MAX_GYM_VENUES)
            venues.forEach { venue ->
                Spacer(Modifier.height(DdSpacing.Sm))
                SysuGymVenueRow(host = host, typeName = type.name, venue = venue)
            }
            val hiddenVenues = type.venues.size - venues.size
            if (hiddenVenues > 0) {
                Spacer(Modifier.height(DdSpacing.Xs))
                DdText(
                    text = host.t("还有 $hiddenVenues 个场地", "$hiddenVenues more venues"),
                    style = SysuCardType.caption,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        SysuFootLine(
            left = plan.window?.let { host.t("预约窗口 $it", "Booking window $it") }
                ?: model.note?.takeIf { it.isNotBlank() },
            right = if (hiddenTypes > 0) host.t("还有 $hiddenTypes 类", "$hiddenTypes more") else null,
        )
    }
}

@Composable
private fun SysuGymVenueRow(host: PluginHost, typeName: String, venue: SysuGymVenuePlan) {
    val colors = deepDeepColors()
    val dates = venue.slots.mapNotNull { it.date }.distinct()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = sysuGymVenueLabel(typeName, venue.name),
                modifier = Modifier.weight(1f),
                style = SysuCardType.caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            dates.singleOrNull()?.let { date ->
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = sysuGymShortDate(date),
                    style = SysuCardType.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SysuWrapRow(horizontalGap = 6, verticalGap = 6) {
            venue.slots.forEach { slot ->
                SysuGymSlotChip(
                    host = host,
                    slot = slot,
                    showDate = dates.size > 1,
                )
            }
            if (venue.moreSlots > 0) {
                SysuGymMoreChip(text = "+${venue.moreSlots}")
            }
        }
    }
}

@Composable
private fun SysuGymSlotChip(host: PluginHost, slot: SysuGymSlotPlan, showDate: Boolean) {
    val colors = deepDeepColors()
    val full = slot.remaining != null && slot.remaining <= 0
    val tone = when {
        full -> colors.danger
        slot.longWindow -> colors.warning
        else -> colors.accent
    }
    val time = if (showDate) {
        listOfNotNull(slot.date?.let { sysuGymShortDate(it) }, slot.time).joinToString(" ")
    } else {
        slot.time
    }
    val label = when {
        full -> host.t("$time · 已满", "$time · full")
        slot.remaining != null -> host.t("$time · 余${slot.remaining}", "$time · ${slot.remaining} left")
        else -> time
    }
    Box(
        modifier = Modifier
            .clip(DdShapes.Medium)
            .background(tone.copy(alpha = if (colors.dark) 0.22f else 0.12f))
            .padding(horizontal = DdSpacing.Sm, vertical = 5.dp),
    ) {
        DdText(
            text = label,
            style = SysuCardType.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = tone,
            maxLines = 1,
        )
    }
}

@Composable
private fun SysuGymMoreChip(text: String) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(horizontal = DdSpacing.Sm, vertical = 5.dp),
    ) {
        DdText(
            text = text,
            style = SysuCardType.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}

private fun sysuGymVenueLabel(typeName: String, name: String): String {
    val trimmed = name.removePrefix(typeName).trimStart(' ', '-', '—', '·')
    return trimmed.ifEmpty { name }
}

private fun sysuGymShortDate(value: String): String =
    if (value.length >= 10) value.takeLast(5) else value
