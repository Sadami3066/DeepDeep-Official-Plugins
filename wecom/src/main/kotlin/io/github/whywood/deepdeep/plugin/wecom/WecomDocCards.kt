package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val WECOM_DOC_ROWS = 4

@Composable
internal fun WecomDocsSearchCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) model.items else model.items.take(WECOM_DOC_ROWS)
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
                leading = DdIcons.Search,
                keyword = model.context,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            rows.forEachIndexed { index, item ->
                if (index > 0) DdRowDivider()
                DocSearchRow(host = host, item = item, expanded = expanded)
            }
            WecomExpandToggle(
                host = host,
                kind = kind,
                expanded = expanded,
                hidden = model.items.size - WECOM_DOC_ROWS,
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
private fun DocSearchRow(host: PluginHost, item: CliCardItem, expanded: Boolean) {
    val colors = deepDeepColors()
    val url = item.url?.takeIf { it.startsWith("http") }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.meta?.takeIf { it.isNotBlank() }?.let { type ->
                        WecomTypePill(type)
                        Spacer(Modifier.width(DdSpacing.Sm))
                    }
                    DdText(
                        text = item.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = WecomCardType.name,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(3.dp))
                    DdText(
                        text = wecomShortText(subtitle) ?: subtitle,
                        style = WecomCardType.secondary,
                        color = colors.inkSecondary,
                        maxLines = if (expanded) 3 else 1,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
                if (expanded) {
                    item.body?.takeIf { it.isNotBlank() }?.let { body ->
                        Spacer(Modifier.height(3.dp))
                        DdText(
                            text = body,
                            style = WecomCardType.micro,
                            color = colors.inkTertiary,
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
            }
            if (url != null) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdIcon(icon = DdIcons.External, tint = colors.inkTertiary, size = 15.dp)
            }
        }
    }
    if (url != null) {
        DdPressable(
            onClick = { wecomOpenUrl(host, url) },
            modifier = Modifier.fillMaxWidth(),
            shape = DdShapes.Small,
            contentDescription = item.title,
            content = { content() },
        )
    } else {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun WecomDocContentCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    val colors = deepDeepColors()
    var expanded by remember(model) { mutableStateOf(false) }
    val title = model.title?.takeIf { it.isNotBlank() } ?: host.t("文档内容", "Doc content")
    val summary = model.summary?.takeIf { it.isNotBlank() }
    val collapsible = summary != null && (summary.length > 320 || summary.count { it == '\n' } > 7)
    val openUrl = model.openUrl?.takeIf { it.startsWith("http") }
    val docId = model.context?.takeIf { it.isNotBlank() }
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                DdText(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = WecomCardType.display,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                model.trailing?.takeIf { it.isNotBlank() }?.let { version ->
                    Spacer(Modifier.width(DdSpacing.Md))
                    Box(Modifier.padding(top = 2.dp)) { WecomTag(version) }
                }
            }
            Spacer(Modifier.height(DdSpacing.Md))
            DocPaper(
                text = summary ?: host.t("文档内容为空", "This doc is empty"),
                muted = summary == null,
                expanded = expanded,
            )
            if (collapsible) {
                DdExpandToggle(
                    expanded = expanded,
                    collapsedLabel = host.t("展开全文", "Read full text"),
                    expandedLabel = host.t("收起", "Show less"),
                    onToggle = { expanded = !expanded },
                )
            }
            if (docId != null || openUrl != null) {
                Spacer(Modifier.height(DdSpacing.Xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdText(
                        text = docId?.let { host.tf("文档 ID %s", "Doc ID %s", it) }.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = WecomCardType.micro.copy(
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            letterSpacing = 0.4.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    openUrl?.let { url ->
                        Spacer(Modifier.width(DdSpacing.Sm))
                        WecomLinkAction(
                            label = host.t("打开文档", "Open doc"),
                            onClick = { wecomOpenUrl(host, url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocPaper(text: String, muted: Boolean, expanded: Boolean) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(wecomFill(colors)),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val inset = DdSpacing.Md.toPx()
                    val width = 3.dp.toPx()
                    val height = size.height - inset * 2
                    if (height > 0f) {
                        drawRoundRect(
                            color = colors.accent,
                            topLeft = Offset(0f, inset),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(width / 2f),
                        )
                    }
                },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 17.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
        ) {
            DdText(
                text = text,
                style = WecomCardType.paper,
                color = if (muted) colors.inkTertiary else colors.inkSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
