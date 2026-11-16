package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState

internal object WecomCardType {
    val eyebrow: TextStyle = DdType.chip.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
    )
    val title: TextStyle = DdType.label.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val display: TextStyle = DdType.label.copy(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val name: TextStyle = DdType.label.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val sender: TextStyle = DdType.label.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val body: TextStyle = DdType.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp)
    val paper: TextStyle = DdType.bodySmall.copy(fontSize = 14.sp, lineHeight = 23.sp)
    val secondary: TextStyle = DdType.chip.copy(fontSize = 12.sp, lineHeight = 17.sp)
    val micro: TextStyle = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val tiny: TextStyle = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
    val number: TextStyle = DdType.label.copy(
        fontSize = 15.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val action: TextStyle = DdType.label.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

internal enum class WecomCardPhase { READY, LOADING, EMPTY, ERROR }

internal fun CliCardModel.wecomPhase(): WecomCardPhase = when {
    state == CliCardState.ERROR -> WecomCardPhase.ERROR
    state == CliCardState.LOADING -> WecomCardPhase.LOADING
    state == CliCardState.EMPTY -> WecomCardPhase.EMPTY
    dataRowCount == 0 && kind != WecomCommandKind.DOC_CONTENT.customType -> WecomCardPhase.EMPTY
    else -> WecomCardPhase.READY
}

@Composable
internal fun WecomCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = deepDeepColors()
    val motion = LocalDdMotionEnabled.current
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) {
        if (!shown) shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = DdMotion.FAST, easing = DdMotion.EaseOut),
        label = "wecom-card-alpha",
    )
    val rise by animateFloatAsState(
        targetValue = if (shown) 0f else 8f,
        animationSpec = tween(durationMillis = DdMotion.FAST, easing = DdMotion.EaseOut),
        label = "wecom-card-rise",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = rise.dp.toPx()
            }
            .clip(DdShapes.Card)
            .background(colors.surface),
        content = content,
    )
}

@Composable
internal fun WecomHead(
    title: String,
    detail: String? = null,
    trailing: String? = null,
    leading: DdIcon? = null,
    keyword: String? = null,
) {
    val colors = deepDeepColors()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    DdIcon(icon = leading, tint = colors.accent, size = 15.dp)
                    Spacer(Modifier.width(DdSpacing.Sm))
                }
                DdText(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = WecomCardType.title,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                keyword?.takeIf { it.isNotBlank() }?.let { value ->
                    Spacer(Modifier.width(DdSpacing.Sm))
                    WecomKeywordChip(value)
                }
            }
            detail?.takeIf { it.isNotBlank() }?.let { line ->
                Spacer(Modifier.height(3.dp))
                DdText(
                    text = line,
                    style = WecomCardType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.takeIf { it.isNotBlank() }?.let { line ->
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = line,
                style = WecomCardType.secondary,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun WecomKeywordChip(text: String) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(wecomAccentFill(colors))
            .padding(horizontal = DdSpacing.Sm, vertical = 2.dp),
    ) {
        DdText(
            text = text,
            modifier = Modifier.widthIn(max = 148.dp),
            style = WecomCardType.tiny.copy(fontWeight = FontWeight.Medium),
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun WecomTypePill(text: String) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(DdShapes.Small)
            .background(wecomFill(colors))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        DdText(
            text = text,
            style = WecomCardType.tiny.copy(letterSpacing = 0.3.sp),
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun WecomTag(text: String) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(wecomFill(colors))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        DdText(
            text = text,
            style = WecomCardType.micro.copy(fontWeight = FontWeight.SemiBold),
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun WecomTodayChip(host: PluginHost) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(wecomAccentFill(colors))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        DdText(
            text = host.t("今天", "Today"),
            style = WecomCardType.tiny.copy(fontWeight = FontWeight.Medium),
            color = colors.accent,
            maxLines = 1,
        )
    }
}

@Composable
internal fun WecomAvatar(initial: String, size: Dp, style: TextStyle) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surface)
            .background(wecomFillStrong(colors)),
        contentAlignment = Alignment.Center,
    ) {
        DdText(
            text = initial,
            style = style,
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun WecomExpandToggle(
    host: PluginHost,
    kind: WecomCommandKind,
    expanded: Boolean,
    hidden: Int,
    onToggle: () -> Unit,
) {
    if (hidden <= 0) return
    DdExpandToggle(
        expanded = expanded,
        collapsedLabel = if (host.chinese) {
            "展开其余 $hidden ${kind.moreUnitZh()}"
        } else {
            "$hidden more ${kind.moreUnitEn()}"
        },
        expandedLabel = host.t("收起", "Show less"),
        onToggle = onToggle,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun WecomDetailText(text: String?, maxLines: Int, modifier: Modifier = Modifier) {
    val body = text?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
    val colors = deepDeepColors()
    DdText(
        text = body,
        modifier = modifier,
        style = WecomCardType.secondary,
        color = colors.inkSecondary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun WecomMoreLine(
    host: PluginHost,
    kind: WecomCommandKind,
    hasMore: Boolean,
) {
    if (!hasMore) return
    val text = wecomMoreText(host, kind) ?: return
    val colors = deepDeepColors()
    DdText(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DdSpacing.Md),
        style = WecomCardType.secondary,
        color = colors.inkTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun WecomLinkAction(label: String, onClick: () -> Unit) {
    val colors = deepDeepColors()
    DdPressable(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        shape = DdShapes.Small,
        contentDescription = label,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DdSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(icon = DdIcons.External, tint = colors.accent, size = 14.dp)
            Spacer(Modifier.width(5.dp))
            DdText(
                text = label,
                style = WecomCardType.action,
                color = colors.accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun WecomTextLink(text: String, onClick: () -> Unit) {
    val colors = deepDeepColors()
    DdPressable(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        shape = DdShapes.Small,
        contentDescription = text,
    ) {
        Row(
            modifier = Modifier.padding(end = DdSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = text,
                style = WecomCardType.action,
                color = colors.accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun WecomStateCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val colors = deepDeepColors()
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
        ) {
            DdText(
                text = host.t(kind.listLabelZh, kind.listLabelEn),
                style = WecomCardType.eyebrow,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            when (model.state) {
                CliCardState.LOADING -> {
                    DdText(
                        text = wecomLoadingText(host, kind),
                        style = WecomCardType.title,
                        color = colors.ink,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(DdSpacing.Md))
                    WecomSkeleton()
                }
                CliCardState.ERROR -> {
                    DdText(
                        text = wecomErrorText(host, kind),
                        style = WecomCardType.title,
                        color = colors.ink,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdText(
                        text = model.summary?.takeIf { it.isNotBlank() }
                            ?: host.t(
                                "检查企业微信是否已连接后重试。",
                                "Check that WeCom is connected, then try again.",
                            ),
                        style = WecomCardType.secondary,
                        color = colors.inkSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(DdSpacing.Xs))
                    WecomTextLink(
                        text = host.t("打开插件页", "Open plugin page"),
                        onClick = { wecomOpenPluginPage(host) },
                    )
                }
                else -> {
                    DdText(
                        text = wecomEmptyText(host, kind),
                        style = WecomCardType.title,
                        color = colors.ink,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdText(
                        text = wecomEmptyHint(host, kind),
                        style = WecomCardType.secondary,
                        color = colors.inkSecondary,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

@Composable
private fun WecomSkeleton() {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
        listOf(0.7f, 0.55f, 0.62f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(11.dp)
                    .clip(DdShapes.Small)
                    .background(wecomFillStrong(colors)),
            )
        }
    }
}

internal fun wecomCountLabel(host: PluginHost, kind: WecomCommandKind, count: Int): String? {
    if (count <= 0) return null
    return if (host.chinese) "$count ${kind.shortCountUnitZh()}" else "$count ${kind.countUnitEn}"
}

private fun WecomCommandKind.shortCountUnitZh(): String = when (this) {
    WecomCommandKind.CHAT_GROUPS -> "个"
    WecomCommandKind.CHAT_MESSAGES -> "条"
    WecomCommandKind.DOC_SEARCH, WecomCommandKind.DOC_CONTENT -> "篇"
    WecomCommandKind.CALENDAR_SCHEDULES -> "个"
    WecomCommandKind.TODO_LIST -> "个"
    WecomCommandKind.CONTACT_USERS -> "位"
}

private fun wecomMoreText(host: PluginHost, kind: WecomCommandKind): String? =
    if (host.chinese) "还有更多${kind.moreNounZh()}" else "More ${kind.moreNounEn()}"

private fun WecomCommandKind.moreUnitZh(): String = when (this) {
    WecomCommandKind.CHAT_GROUPS -> "个群聊"
    WecomCommandKind.CHAT_MESSAGES -> "条消息"
    WecomCommandKind.DOC_SEARCH, WecomCommandKind.DOC_CONTENT -> "篇文档"
    WecomCommandKind.CALENDAR_SCHEDULES -> "个日程"
    WecomCommandKind.TODO_LIST -> "个待办"
    WecomCommandKind.CONTACT_USERS -> "位成员"
}

private fun WecomCommandKind.moreUnitEn(): String = when (this) {
    WecomCommandKind.CHAT_GROUPS -> "group chats"
    WecomCommandKind.CHAT_MESSAGES -> "messages"
    WecomCommandKind.DOC_SEARCH, WecomCommandKind.DOC_CONTENT -> "docs"
    WecomCommandKind.CALENDAR_SCHEDULES -> "schedules"
    WecomCommandKind.TODO_LIST -> "to-dos"
    WecomCommandKind.CONTACT_USERS -> "people"
}

private fun WecomCommandKind.moreNounZh(): String = when (this) {
    WecomCommandKind.CHAT_GROUPS -> "群聊"
    WecomCommandKind.CHAT_MESSAGES -> "消息"
    WecomCommandKind.DOC_SEARCH, WecomCommandKind.DOC_CONTENT -> "文档"
    WecomCommandKind.CALENDAR_SCHEDULES -> "日程"
    WecomCommandKind.TODO_LIST -> "待办"
    WecomCommandKind.CONTACT_USERS -> "成员"
}

private fun WecomCommandKind.moreNounEn(): String = when (this) {
    WecomCommandKind.CHAT_GROUPS -> "group chats"
    WecomCommandKind.CHAT_MESSAGES -> "messages"
    WecomCommandKind.DOC_SEARCH, WecomCommandKind.DOC_CONTENT -> "docs"
    WecomCommandKind.CALENDAR_SCHEDULES -> "schedules"
    WecomCommandKind.TODO_LIST -> "to-dos"
    WecomCommandKind.CONTACT_USERS -> "people"
}

private fun wecomLoadingText(host: PluginHost, kind: WecomCommandKind): String = when (kind) {
    WecomCommandKind.CHAT_GROUPS -> host.t("正在读取群聊会话…", "Loading group chats…")
    WecomCommandKind.CHAT_MESSAGES -> host.t("正在读取会话消息…", "Loading messages…")
    WecomCommandKind.DOC_SEARCH -> host.t("正在搜索文档…", "Searching docs…")
    WecomCommandKind.DOC_CONTENT -> host.t("正在读取文档…", "Loading doc…")
    WecomCommandKind.CALENDAR_SCHEDULES -> host.t("正在读取日程…", "Loading schedules…")
    WecomCommandKind.TODO_LIST -> host.t("正在读取待办…", "Loading to-dos…")
    WecomCommandKind.CONTACT_USERS -> host.t("正在读取通讯录…", "Loading contacts…")
}

private fun wecomEmptyText(host: PluginHost, kind: WecomCommandKind): String = when (kind) {
    WecomCommandKind.CHAT_GROUPS -> host.t("没有符合条件的群聊会话", "No matching group chats")
    WecomCommandKind.CHAT_MESSAGES -> host.t("这段时间没有消息", "No messages in this period")
    WecomCommandKind.DOC_SEARCH -> host.t("没有找到符合条件的文档", "No matching docs")
    WecomCommandKind.DOC_CONTENT -> host.t("文档内容为空", "This doc is empty")
    WecomCommandKind.CALENDAR_SCHEDULES -> host.t("这段时间没有日程", "No schedules in this period")
    WecomCommandKind.TODO_LIST -> host.t("没有待处理的事项", "Nothing to do")
    WecomCommandKind.CONTACT_USERS -> host.t("没有找到符合条件的成员", "No matching people")
}

private fun wecomEmptyHint(host: PluginHost, kind: WecomCommandKind): String = when (kind) {
    WecomCommandKind.CHAT_GROUPS -> host.t("换一个时间范围再试。", "Try a different time range.")
    WecomCommandKind.CHAT_MESSAGES -> host.t("换一个时间范围或会话再试。", "Try another chat or time range.")
    WecomCommandKind.DOC_SEARCH -> host.t("换个关键词再试。", "Try a different keyword.")
    WecomCommandKind.DOC_CONTENT -> host.t("可以在企业微信里打开查看完整内容。", "Open it in WeCom to see the full content.")
    WecomCommandKind.CALENDAR_SCHEDULES -> host.t(
        "换一个时间范围再试，或直接在对话里让我查别的日期。",
        "Try another range, or ask me for different dates in chat.",
    )
    WecomCommandKind.TODO_LIST -> host.t("换个筛选条件再试。", "Try a different filter.")
    WecomCommandKind.CONTACT_USERS -> host.t("换个关键词再试。", "Try a different keyword.")
}

private fun wecomErrorText(host: PluginHost, kind: WecomCommandKind): String = when (kind) {
    WecomCommandKind.CHAT_GROUPS -> host.t("没能读取群聊会话", "Couldn't load group chats")
    WecomCommandKind.CHAT_MESSAGES -> host.t("没能读取会话消息", "Couldn't load messages")
    WecomCommandKind.DOC_SEARCH -> host.t("没能搜索到文档", "Couldn't search docs")
    WecomCommandKind.DOC_CONTENT -> host.t("没能读取文档内容", "Couldn't load the doc")
    WecomCommandKind.CALENDAR_SCHEDULES -> host.t("没能读取日程", "Couldn't load schedules")
    WecomCommandKind.TODO_LIST -> host.t("没能读取待办", "Couldn't load to-dos")
    WecomCommandKind.CONTACT_USERS -> host.t("没能读取通讯录", "Couldn't load contacts")
}

internal fun wecomFill(colors: DeepDeepColors): Color =
    colors.ink.copy(alpha = DdAlpha.Press)

internal fun wecomFillStrong(colors: DeepDeepColors): Color =
    colors.ink.copy(alpha = DdAlpha.TrackLight)

internal fun wecomAccentFill(colors: DeepDeepColors): Color =
    colors.accent.copy(alpha = if (colors.dark) DdAlpha.IndicatorDark else DdAlpha.TrackLight)

internal fun wecomOpenUrl(host: PluginHost, url: String) {
    if (!url.startsWith("http")) return
    runCatching { host.ui().openUrl(url) }
}

internal fun wecomOpenPluginPage(host: PluginHost) {
    runCatching { host.ui().openPage("main") }
}
