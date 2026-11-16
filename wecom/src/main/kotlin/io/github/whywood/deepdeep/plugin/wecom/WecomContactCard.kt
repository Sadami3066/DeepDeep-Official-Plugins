package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val WECOM_CONTACT_ROWS = 4

@Composable
internal fun WecomContactsCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) model.items else model.items.take(WECOM_CONTACT_ROWS)
    val total = maxOf(model.total, model.items.size)
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Lg, bottom = DdSpacing.Md),
        ) {
            WecomHead(
                title = host.t(kind.listLabelZh, kind.listLabelEn),
                trailing = wecomCountLabel(host, kind, total),
                leading = DdIcons.Book,
                keyword = model.context,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            rows.forEachIndexed { index, item ->
                if (index > 0) DdRowDivider()
                ContactRow(item = item, expanded = expanded)
            }
            WecomExpandToggle(
                host = host,
                kind = kind,
                expanded = expanded,
                hidden = model.items.size - WECOM_CONTACT_ROWS,
                onToggle = { expanded = !expanded },
            )
            WecomMoreLine(
                host = host,
                kind = kind,
                hasMore = !model.note.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun ContactRow(item: CliCardItem, expanded: Boolean) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WecomAvatar(
            initial = wecomInitial(item.title),
            size = 32.dp,
            style = WecomCardType.sender.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.width(DdSpacing.Md))
        Column(Modifier.weight(1f)) {
            DdText(
                text = item.title,
                style = WecomCardType.name,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = subtitle,
                    style = WecomCardType.secondary,
                    color = colors.inkSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.body?.takeIf { it.isNotBlank() }?.let { details ->
                Spacer(Modifier.height(3.dp))
                WecomDetailText(
                    text = details,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                )
            }
        }
        item.meta?.takeIf { it.isNotBlank() }?.let { email ->
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = email,
                modifier = Modifier.widthIn(max = if (expanded) 160.dp else 132.dp),
                style = WecomCardType.micro,
                color = colors.inkTertiary,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
