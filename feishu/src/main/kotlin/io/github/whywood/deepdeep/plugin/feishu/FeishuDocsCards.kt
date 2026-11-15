package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuDocsSearchCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        when (FeishuKindState(host, model)) {
            FeishuKindStateKind.ERROR -> FeishuCardErrorBody(host, model.openUrl)
            FeishuKindStateKind.LOADING -> DocsSearchLoading()
            FeishuKindStateKind.EMPTY -> DocsSearchEmpty(host, model)
            FeishuKindStateKind.READY -> DocsSearchContent(host, model)
        }
    }
}

@Composable
private fun DocsSearchContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val count = model.total.coerceAtLeast(model.items.size)
    val rows = model.items.take(FEISHU_CARD_DOC_ROWS)
    val query = cardQueryText(model.context)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("云文档", "Docs"),
            detail = null,
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 个", "%d docs", count) else null,
        )
        query?.let { value ->
            Spacer(Modifier.height(DdSpacing.Sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DdShapes.Small)
                    .background(colors.wash)
                    .padding(horizontal = DdSpacing.Sm, vertical = DdSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdIcon(icon = DdIcons.Search, tint = colors.inkTertiary, size = 14.dp)
                Spacer(Modifier.width(DdSpacing.Xs))
                DdText(
                    text = value,
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Xs))
        rows.forEachIndexed { index, item ->
            if (index > 0) DdRowDivider(leadingInset = 31.dp)
            DocsSearchRow(host = host, item = item)
        }
        val hidden = (count - rows.size).coerceAtLeast(0)
        val note = model.note?.takeIf { it.isNotBlank() }
            ?: if (hidden > 0) host.tf("还有 %d 个未显示", "%d more not shown", hidden) else null
        FeishuCardFoot(
            host = host,
            note = note,
            actionLabel = if (model.openUrl.isNullOrBlank()) null else host.t("在飞书打开", "Open in Feishu"),
            openUrl = model.openUrl,
        )
    }
}

@Composable
private fun DocsSearchRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val onOpen = item.url?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(icon = docTypeIcon(item.subtitle), tint = colors.accent, size = 20.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = item.title,
                    style = feishuItemTitleStyle(),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = subtitle,
                        style = DdType.numeric,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onOpen != null) {
                Spacer(Modifier.width(DdSpacing.Xs))
                DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 15.dp)
            }
        }
    }
    if (onOpen != null) {
        DdPressable(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            contentDescription = item.title,
            content = { content() },
        )
    } else {
        content()
    }
}

private fun docTypeIcon(subtitle: String?): DdIcon {
    val type = subtitle?.substringBefore(" · ")?.trim()?.uppercase().orEmpty()
    return when {
        type.startsWith("SHEET") || type.contains("BITABLE") -> DdIcons.Database
        type.contains("WIKI") -> DdIcons.Book
        type.contains("FOLDER") -> DdIcons.Folder
        type.contains("IMAGE") -> DdIcons.Image
        type.contains("SLIDES") -> DdIcons.Compose
        type.contains("MINDNOTE") || type.startsWith("DOC") -> DdIcons.Book
        else -> DdIcons.File
    }
}

@Composable
private fun DocsSearchLoading() {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(colors.wash, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(DdSpacing.Sm))
            Box(Modifier.height(12.dp).width(56.dp).background(colors.wash, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.height(DdSpacing.Md))
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DdSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(DdSpacing.Sm))
                    Box(Modifier.fillMaxWidth(0.3f).height(9.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
private fun DocsSearchEmpty(host: PluginHost, model: FeishuCardModel) {
    FeishuEmptyBody(
        title = host.t("没有找到文档", "No docs found"),
        message = host.t(
            "换个关键词，或缩小搜索范围",
            "Try another keyword or narrow the search",
        ),
    ) {
        DdIcon(icon = DdIcons.Search, tint = deepDeepColors().inkTertiary, size = 24.dp)
        Spacer(Modifier.width(DdSpacing.Sm))
        DdIcon(icon = DdIcons.File, tint = deepDeepColors().inkTertiary, size = 20.dp)
    }
    if (!model.openUrl.isNullOrBlank()) {
        FeishuCardFoot(
            host = host,
            note = null,
            actionLabel = host.t("在飞书打开", "Open in Feishu"),
            openUrl = model.openUrl,
        )
    }
}

@Composable
internal fun FeishuDocsReadCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        when (FeishuKindState(host, model)) {
            FeishuKindStateKind.ERROR -> FeishuCardErrorBody(host, model.openUrl)
            FeishuKindStateKind.LOADING -> DocsReadLoading()
            FeishuKindStateKind.EMPTY -> DocsReadEmpty(host, model)
            FeishuKindStateKind.READY -> DocsReadContent(host, model)
        }
    }
}

@Composable
private fun DocsReadContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val title = model.context?.takeIf { it.isNotBlank() } ?: host.t("文档", "Document")
    val paragraphs = model.items.map { it.title }.filter { it.isNotBlank() }
    val lead = paragraphs.firstOrNull()
    val rest = paragraphs.drop(1).take(2)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(icon = DdIcons.File, tint = colors.accent, size = 15.dp)
            Spacer(Modifier.width(DdSpacing.Xs))
            DdText(
                text = host.t("文档", "Document"),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            model.trailing?.takeIf { it.isNotBlank() }?.let { trailing ->
                Spacer(Modifier.weight(1f))
                FeishuChip(
                    text = trailing,
                    tint = colors.inkSecondary,
                    background = colors.wash,
                    shape = RoundedCornerShape(percent = 50),
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        DdText(
            text = title,
            style = DdType.heading,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val note = model.note?.takeIf { it.isNotBlank() }
        note?.let { meta ->
            Spacer(Modifier.height(DdSpacing.Xs))
            DdText(
                text = meta,
                style = DdType.numeric,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Box(
            Modifier
                .width(30.dp)
                .height(3.dp)
                .background(colors.accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(DdSpacing.Md))
        lead?.let { text ->
            DdText(
                text = text,
                style = DdType.body,
                color = colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        rest.forEach { text ->
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = text,
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FeishuCardFoot(
            host = host,
            note = model.note?.takeIf { false },
            actionLabel = if (model.openUrl.isNullOrBlank()) null else host.t("打开文档", "Open document"),
            openUrl = model.openUrl,
        )
    }
}

@Composable
private fun DocsReadLoading() {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        Box(Modifier.height(10.dp).width(48.dp).background(colors.wash, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(DdSpacing.Md))
        Box(Modifier.fillMaxWidth(0.7f).height(26.dp).background(colors.wash, RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(DdSpacing.Md))
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(10.dp).background(colors.wash, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(DdSpacing.Sm))
        }
    }
}

@Composable
private fun DocsReadEmpty(host: PluginHost, model: FeishuCardModel) {
    FeishuEmptyBody(
        title = host.t("这篇文档暂时读不到", "This document can't be read right now"),
        message = host.t(
            "可能没有访问权限，或链接已经失效",
            "You may not have access, or the link has expired",
        ),
    ) {
        DdIcon(icon = DdIcons.File, tint = deepDeepColors().inkTertiary, size = 26.dp)
    }
    if (!model.openUrl.isNullOrBlank()) {
        FeishuCardFoot(
            host = host,
            note = null,
            actionLabel = host.t("打开文档", "Open document"),
            openUrl = model.openUrl,
        )
    }
}
