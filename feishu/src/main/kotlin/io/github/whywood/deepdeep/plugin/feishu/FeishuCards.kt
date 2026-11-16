package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject

internal object FeishuCardTypes {
    const val CHAT_CARD = "feishu.card"
    const val KIND_CHAT_LIST = "chat_list"
    const val KIND_MESSAGES = "messages"
    const val KIND_MESSAGES_SEARCH = "messages_search"
    const val KIND_AGENDA = "agenda"
    const val KIND_DOCS_SEARCH = "docs_search"
    const val KIND_DOCS_READ = "docs_read"
    const val KIND_TASKS = "tasks"
    const val KIND_MAIL_LIST = "mail_list"
    const val KIND_MAIL_READ = "mail_read"
    const val KIND_ACTION = "action"
}

internal typealias FeishuCardItem = CliCardItem

internal typealias FeishuCardModel = CliCardModel

internal fun encodeFeishuCardPayload(model: FeishuCardModel): JsonObject = CliCardCodec.encode(model)

internal fun decodeFeishuCardPayload(payload: JsonObject): FeishuCardModel? =
    CliCardCodec.decode(payload, SUPPORTED_CARD_KINDS)

internal val SUPPORTED_CARD_KINDS = setOf(
    FeishuCardTypes.KIND_CHAT_LIST,
    FeishuCardTypes.KIND_MESSAGES,
    FeishuCardTypes.KIND_MESSAGES_SEARCH,
    FeishuCardTypes.KIND_AGENDA,
    FeishuCardTypes.KIND_DOCS_SEARCH,
    FeishuCardTypes.KIND_DOCS_READ,
    FeishuCardTypes.KIND_TASKS,
    FeishuCardTypes.KIND_MAIL_LIST,
    FeishuCardTypes.KIND_MAIL_READ,
    FeishuCardTypes.KIND_ACTION,
)

internal const val FEISHU_CHAT_PREVIEW_ROWS = 5
internal const val FEISHU_AGENDA_PREVIEW_ROWS = 5
internal const val FEISHU_DOC_PREVIEW_ROWS = 4
internal const val FEISHU_TASK_PREVIEW_ROWS = 5
internal const val FEISHU_MAIL_PREVIEW_ROWS = 5
internal const val FEISHU_SEARCH_PREVIEW_ROWS = 5
internal const val FEISHU_MESSAGE_PREVIEW_LINES = 3

private val QUERY_PREFIXES = listOf("搜索：", "搜索:", "search: ", "search:")
private val RANGE_PREFIXES = listOf(
    "时间范围：", "时间范围:", "起始：", "起始:", "截止：", "截止:",
    "range: ", "range:", "from: ", "from:", "until: ", "until:",
)
private val TIME_PATTERN = Regex("""\d{1,2}:\d{2}""")

internal fun CliCardItem.badgeTokens(): List<String> =
    badge?.split(" · ")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

internal fun CliCardItem.timePart(): String? {
    val value = meta?.trim().orEmpty()
    if (value.isEmpty()) return null
    return if (value.contains(' ')) value.substringAfterLast(' ') else value
}

internal fun CliCardItem.datePart(): String? {
    val value = meta?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (!value.contains(' ')) return value.takeIf { it.contains('-') }
    return value.substringBeforeLast(' ')
}

internal fun CliCardItem.messageSender(): String? =
    subtitle?.split(" · ")?.firstOrNull()?.takeIf { it.isNotBlank() }

internal fun CliCardItem.messageKindLabel(): String? {
    val parts = subtitle?.split(" · ").orEmpty()
    return parts.getOrNull(1)?.takeIf { it.isNotBlank() }
}

internal data class AgendaTimeParts(
    val start: String?,
    val end: String?,
    val date: String?,
    val allDay: Boolean,
)

internal fun CliCardItem.agendaTimeParts(): AgendaTimeParts {
    val segment = subtitle?.substringBefore(" · ")?.trim().orEmpty()
    val times = TIME_PATTERN.findAll(segment).map { it.value }.toList()
    val allDay = segment.contains("全天") || segment.contains("All day", ignoreCase = true)
    val start = if (allDay) null else timePart()?.takeIf { it.contains(':') } ?: times.firstOrNull()
    val end = if (allDay) null else if (times.size >= 2) times[1] else null
    return AgendaTimeParts(
        start = start,
        end = end,
        date = datePart()?.takeIf { it.contains('-') },
        allDay = allDay,
    )
}

internal fun CliCardItem.agendaLocation(): String? {
    val parts = subtitle?.split(" · ").orEmpty()
    return parts.drop(1).joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun cardQueryText(context: String?): String? {
    val value = context?.trim().orEmpty()
    if (value.isEmpty()) return null
    val prefix = QUERY_PREFIXES.firstOrNull { value.startsWith(it) } ?: return null
    return value.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
}

internal fun cardRangeText(context: String?): String? {
    val value = context?.trim().orEmpty()
    if (value.isEmpty()) return null
    val segments = value.split('；', ';').map { it.trim() }.filter { it.isNotBlank() }
    val range = segments.firstOrNull { segment ->
        RANGE_PREFIXES.any { segment.startsWith(it) }
    } ?: segments.firstOrNull() ?: return null
    val prefix = RANGE_PREFIXES.firstOrNull { range.startsWith(it) } ?: return range.ifBlank { null }
    return range.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
}

internal fun todayLabel(datePrefix: String?, host: PluginHost): String? {
    val value = datePrefix?.trim().orEmpty()
    if (value.isEmpty()) return null
    val day = value.takeLast(5)
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MM-dd"))
    return if (day == today) host.t("今天", "Today") else null
}

internal fun initialOf(text: String): String =
    text.trim().firstOrNull()?.toString()?.uppercase().orEmpty()

@Composable
internal fun feishuItemTitleStyle(): TextStyle =
    DdType.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)

@Composable
internal fun FeishuCardShell(content: @Composable () -> Unit) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Card)
            .background(colors.raised),
    ) {
        content()
    }
}

@Composable
private fun FeishuCardAppear(content: @Composable () -> Unit) {
    val motionEnabled = LocalDdMotionEnabled.current
    var visible by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled) visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(DdMotion.FAST, easing = DdMotion.EaseOut),
        label = "feishu-card-alpha",
    )
    Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) { content() }
}

@Composable
internal fun FeishuChip(
    text: String,
    tint: Color,
    background: Color,
    shape: RoundedCornerShape = RoundedCornerShape(DdSpacing.Sm),
) {
    Box(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .padding(horizontal = DdSpacing.Sm, vertical = 3.dp),
    ) {
        DdText(text = text, style = DdType.chip, color = tint, maxLines = 1)
    }
}

@Composable
internal fun FeishuCardHead(host: PluginHost, title: String, detail: String?, trailing: String?) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(3.dp)
                        .heightIn(min = 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(text = title, style = feishuItemTitleStyle(), color = colors.ink, maxLines = 1)
            }
            detail?.takeIf { it.isNotBlank() }?.let { line ->
                DdText(
                    text = line,
                    modifier = Modifier.padding(start = DdSpacing.Md, top = DdSpacing.Xs),
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.takeIf { it.isNotBlank() }?.let { label ->
            Spacer(Modifier.width(DdSpacing.Sm))
            FeishuChip(
                text = label,
                tint = colors.inkSecondary,
                background = colors.wash,
                shape = RoundedCornerShape(percent = 50),
            )
        }
    }
}

@Composable
internal fun FeishuCardFoot(host: PluginHost, note: String?, actionLabel: String?, openUrl: String?) {
    if (note.isNullOrBlank() && (actionLabel.isNullOrBlank() || openUrl.isNullOrBlank())) return
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DdSpacing.Sm)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = note.orEmpty(),
            modifier = Modifier.weight(1f),
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!actionLabel.isNullOrBlank() && !openUrl.isNullOrBlank()) {
            DdTextAction(
                text = actionLabel,
                onClick = { host.openFeishuLink(openUrl) },
            )
        }
    }
}

@Composable
internal fun FeishuEmptyBody(title: String, message: String, visual: @Composable RowScope.() -> Unit) {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, content = visual)
        Spacer(Modifier.height(DdSpacing.Md))
        DdText(text = title, style = feishuItemTitleStyle(), color = colors.ink, maxLines = 2)
        Spacer(Modifier.height(DdSpacing.Xxs))
        DdText(
            text = message,
            style = DdType.chip,
            color = colors.inkSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FeishuCardErrorBody(host: PluginHost, openUrl: String?) {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        DdIcon(
            icon = DdIcons.Info,
            tint = colors.danger,
            size = 22.dp,
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        DdText(
            text = host.t("这项内容暂时无法显示", "This content can't be shown right now"),
            style = feishuItemTitleStyle(),
            color = colors.ink,
            maxLines = 2,
        )
        Spacer(Modifier.height(DdSpacing.Xxs))
        DdText(
            text = host.t("可以在飞书里查看完整内容", "Open Feishu to view it"),
            style = DdType.chip,
            color = colors.inkSecondary,
            maxLines = 2,
        )
        if (!openUrl.isNullOrBlank()) {
            Spacer(Modifier.height(DdSpacing.Xs))
            FeishuCardFoot(
                host = host,
                note = null,
                actionLabel = host.t("在飞书打开", "Open in Feishu"),
                openUrl = openUrl,
            )
        }
    }
}

@Composable
private fun FeishuCardFallback(host: PluginHost) {
    val colors = deepDeepColors()
    FeishuCardShell {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DdSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(
                icon = DdIcons.Message,
                tint = colors.inkTertiary,
                size = 20.dp,
            )
            Spacer(Modifier.width(DdSpacing.Md))
            Column {
                DdText(
                    text = host.t("内容暂时无法显示", "This content can't be shown right now"),
                    style = DdType.bodySmall,
                    color = colors.ink,
                    maxLines = 2,
                )
                DdText(
                    text = host.t("可以直接在对话里查看结果", "Check the result in the conversation"),
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
internal fun FeishuChatCard(host: PluginHost, state: CustomMessageSlotState) {
    val model = remember(state.payload) { decodeFeishuCardPayload(state.payload) }
    FeishuCardAppear {
        if (model == null) {
            FeishuCardFallback(host)
        } else {
            FeishuKindCard(host, model)
        }
    }
}

@Composable
private fun FeishuKindCard(host: PluginHost, model: CliCardModel) {
    when (model.kind) {
        FeishuCardTypes.KIND_CHAT_LIST -> FeishuChatListCard(host, model)
        FeishuCardTypes.KIND_MESSAGES -> FeishuMessagesCard(host, model)
        FeishuCardTypes.KIND_MESSAGES_SEARCH -> FeishuMessagesSearchCard(host, model)
        FeishuCardTypes.KIND_AGENDA -> FeishuAgendaCard(host, model)
        FeishuCardTypes.KIND_DOCS_SEARCH -> FeishuDocsSearchCard(host, model)
        FeishuCardTypes.KIND_DOCS_READ -> FeishuDocsReadCard(host, model)
        FeishuCardTypes.KIND_TASKS -> FeishuTasksCard(host, model)
        FeishuCardTypes.KIND_MAIL_LIST -> FeishuMailListCard(host, model)
        FeishuCardTypes.KIND_MAIL_READ -> FeishuMailReadCard(host, model)
        FeishuCardTypes.KIND_ACTION -> FeishuActionResultCard(host, model)
        else -> FeishuCardFallback(host)
    }
}

@Composable
internal fun FeishuKindState(host: PluginHost, model: CliCardModel): FeishuKindStateKind = when {
    model.state == CliCardState.ERROR -> FeishuKindStateKind.ERROR
    model.state == CliCardState.LOADING -> FeishuKindStateKind.LOADING
    model.state == CliCardState.EMPTY || model.isEmpty -> FeishuKindStateKind.EMPTY
    else -> FeishuKindStateKind.READY
}

internal enum class FeishuKindStateKind {
    READY,
    LOADING,
    EMPTY,
    ERROR,
}

internal fun PluginHost.openFeishuLink(url: String) {
    runCatching { ui().openUrl(url) }
}
