package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val MAX_NETPAY_ROWS = 3

@Composable
internal fun SysuNetpayCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.netpayPlan(model) }
    if (plan == null || plan.services.isEmpty()) {
        SysuFallbackListCard(host, model)
        return
    }
    val colors = deepDeepColors()
    val services = plan.services.take(MAX_NETPAY_ROWS)
    val hidden = plan.services.size - services.size
    SysuCardSurface {
        SysuTopLine(
            kicker = host.t("校园网服务", "Campus network"),
            title = plan.identity?.takeIf { it.isNotBlank() }?.let(::sysuNetpayTitle)
                ?: host.t("服务状态", "Service status"),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                DdText(
                    text = host.t("到期日", "Expires"),
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                DdText(
                    text = plan.earliestExpiry?.toString() ?: "—",
                    style = DdType.heading.copy(fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 1,
                )
            }
            SysuNetpayCountdown(host = host, days = plan.earliestDaysLeft)
        }
        Spacer(Modifier.height(DdSpacing.Md))
        services.forEachIndexed { index, service ->
            if (index > 0) Spacer(Modifier.height(DdSpacing.Sm))
            SysuNetpayServiceRow(
                host = host,
                service = service,
                showExpiry = plan.services.size > 1,
            )
        }
        if (hidden > 0) {
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = host.t("还有 $hidden 个服务", "$hidden more services"),
                style = SysuCardType.caption,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(DdSpacing.Md))
        DdText(
            text = host.t("需要续费时在对话里说「续费」", "Ask in the chat to renew"),
            style = SysuCardType.caption,
            color = colors.inkTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SysuNetpayServiceRow(host: PluginHost, service: SysuNetpayServicePlan, showExpiry: Boolean) {
    val colors = deepDeepColors()
    val dot = sysuStatusColor(colors, service.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(horizontal = DdSpacing.Md, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            DdText(
                text = service.name,
                style = SysuCardType.bodyStrong,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(service.status, service.address).joinToString(" · ")
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = detail,
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showExpiry) {
            val right = service.expiry?.toString()
                ?: service.daysLeft?.let { host.t("剩余 $it 天", "$it days left") }
            if (right != null) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = right,
                    style = SysuCardType.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SysuNetpayCountdown(host: PluginHost, days: Long?) {
    if (days == null) return
    val colors = deepDeepColors()
    val tone = when {
        days < 0 -> colors.danger
        days <= 14 -> colors.warning
        else -> colors.accent
    }
    val label = when {
        days < 0 -> host.t("已过期", "Expired")
        days == 0L -> host.t("今天到期", "Expires today")
        else -> host.t("剩余 $days 天", "$days days left")
    }
    SysuPill(
        text = label,
        foreground = tone,
        background = tone.copy(alpha = if (colors.dark) 0.22f else 0.12f),
    )
}

private fun sysuNetpayTitle(identity: String): String =
    identity.split(" · ")
        .filterNot { token -> token.startsWith("学号 ") }
        .joinToString(" · ")
        .ifBlank { identity }
