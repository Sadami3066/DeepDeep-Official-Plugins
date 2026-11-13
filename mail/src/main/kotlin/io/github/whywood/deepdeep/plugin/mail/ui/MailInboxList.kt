package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdAvatar
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdPullToRefresh
import io.github.whywood.deepdeep.design.DdRadius
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSwipeAction
import io.github.whywood.deepdeep.design.DdSwipeRow
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailAccount

private sealed interface MailListEntry {
    val key: String

    data class Header(val group: MailDateGroup, val label: String) : MailListEntry {
        override val key: String = "header-${group.name}"
    }

    data class Row(val row: MailThreadRow, val dividerAfter: Boolean) : MailListEntry {
        override val key: String = "thread-${row.accountId}-${row.threadId}"
    }
}

@Composable
internal fun MailThreadList(
    host: PluginHost,
    rows: List<MailThreadRow>,
    now: Long,
    archiveAccountIds: Set<String>,
    refreshing: Boolean,
    swipeEnabled: Boolean,
    onOpen: (MailThreadRow) -> Unit,
    onArchive: (MailThreadRow) -> Unit,
    onDelete: (MailThreadRow) -> Unit,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val chinese = host.chinese
    val todayLabel = host.t("今天", "Today")
    val yesterdayLabel = host.t("昨天", "Yesterday")
    val earlierLabel = host.t("更早", "Earlier")
    val entries = remember(rows, now, todayLabel, yesterdayLabel, earlierLabel) {
        buildEntries(rows, now, todayLabel, yesterdayLabel, earlierLabel)
    }
    var openSwipeKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(rows) {
        val keys = rows.mapTo(HashSet()) { row -> "thread-${row.accountId}-${row.threadId}" }
        if (openSwipeKey != null && openSwipeKey !in keys) openSwipeKey = null
    }
    val listContent: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            items(entries, key = { entry -> entry.key }) { entry ->
                when (entry) {
                    is MailListEntry.Header -> DdSectionLabel(
                        text = entry.label,
                        modifier = Modifier.animateItem().padding(top = 18.dp, bottom = 4.dp),
                    )

                    is MailListEntry.Row -> {
                        val row = entry.row
                        val rowKey = entry.key
                        val canArchive = archiveAccountIds.contains(row.accountId)
                        Column(Modifier.fillMaxWidth().animateItem()) {
                            DdSwipeRow(
                                key = rowKey,
                                actions = if (!swipeEnabled) {
                                    emptyList()
                                } else {
                                    buildList {
                                        if (canArchive) {
                                            add(
                                                DdSwipeAction(
                                                    icon = DdIcons.Archive,
                                                    label = host.t("归档", "Archive"),
                                                    tint = colors.accent,
                                                    background = colors.accentSoft,
                                                    onClick = { onArchive(row) },
                                                ),
                                            )
                                        }
                                        add(
                                            DdSwipeAction(
                                                icon = DdIcons.Trash,
                                                label = host.t("删除", "Delete"),
                                                tint = colors.danger,
                                                background = colors.danger.copy(alpha = 0.12f),
                                                onClick = { onDelete(row) },
                                            ),
                                        )
                                    }
                                },
                                openKey = openSwipeKey,
                                onOpenChange = { openSwipeKey = it },
                                enabled = swipeEnabled,
                            ) {
                                MailThreadItem(
                                    host = host,
                                    row = row,
                                    now = now,
                                    chinese = chinese,
                                    onClick = {
                                        val rowWasOpen = openSwipeKey == rowKey
                                        openSwipeKey = null
                                        if (!rowWasOpen) onOpen(row)
                                    },
                                )
                            }
                            if (entry.dividerAfter) DdRowDivider(leadingInset = 52.dp)
                        }
                    }
                }
            }
        }
    }
    if (onRefresh != null) {
        DdPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            listContent()
        }
    } else {
        Box(modifier.fillMaxSize()) { listContent() }
    }
}

@Composable
private fun MailThreadItem(
    host: PluginHost,
    row: MailThreadRow,
    now: Long,
    chinese: Boolean,
    onClick: () -> Unit,
) {
    val colors = deepDeepColors()
    val latest = row.latest
    val sender = latest.fromName.ifBlank { latest.fromAddress }
    val subject = displaySubject(latest.subject, host.t("（无主题）", "(No subject)"))
    val preview = displayPreview(latest.preview, limit = 120)
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DdRadius.Medium),
        contentDescription = "$sender $subject",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DdAvatar(
                initials = mailInitial(latest.fromName, latest.fromAddress),
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.unread) {
                        MailUnreadDot(size = 6.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = sender,
                        modifier = Modifier.weight(1f),
                        style = if (row.unread) DdType.label else DdType.bodySmall,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = mailListTime(latest.dateMillis, now, chinese),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subject,
                        modifier = Modifier.weight(1f),
                        style = DdType.bodySmall.copy(
                            fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.messages.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = row.messages.size.toString(),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                        )
                    }
                    if (row.hasAttachments) {
                        Spacer(Modifier.width(6.dp))
                        DdIcon(icon = DdIcons.Paperclip, tint = colors.inkTertiary, size = 13.dp)
                    }
                }
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MailAccountSwitcher(
    accounts: List<MailAccount>,
    selectedAccountId: String?,
    allLabel: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MailAccountSwitchItem(selected = selectedAccountId == null, contentDescription = allLabel) {
            DdPressable(
                onClick = { onSelect(null) },
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                background = if (selectedAccountId == null) colors.accentSoft else colors.wash,
                contentDescription = allLabel,
            ) {
                DdIcon(
                    icon = DdIcons.Message,
                    tint = if (selectedAccountId == null) colors.accent else colors.inkSecondary,
                    size = 17.dp,
                )
            }
        }
        accounts.forEach { account ->
            MailAccountSwitchItem(
                selected = selectedAccountId == account.id,
                contentDescription = account.email,
            ) {
                DdAvatar(
                    initials = mailInitial(account.displayName, account.email),
                    size = 34.dp,
                    contentDescription = account.email,
                    onClick = { onSelect(account.id) },
                )
            }
        }
    }
}

@Composable
private fun MailAccountSwitchItem(
    selected: Boolean,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier.size(42.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .border(2.dp, colors.accent, CircleShape),
            )
        }
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun buildEntries(
    rows: List<MailThreadRow>,
    now: Long,
    todayLabel: String,
    yesterdayLabel: String,
    earlierLabel: String,
): List<MailListEntry> = buildList {
    rows.forEachIndexed { index, row ->
        val group = mailDateGroup(row.latest.dateMillis, now)
        val previous = rows.getOrNull(index - 1)
        val startGroup = previous == null || mailDateGroup(previous.latest.dateMillis, now) != group
        if (startGroup) {
            val label = when (group) {
                MailDateGroup.TODAY -> todayLabel
                MailDateGroup.YESTERDAY -> yesterdayLabel
                MailDateGroup.EARLIER -> earlierLabel
            }
            add(MailListEntry.Header(group, label))
        }
        val next = rows.getOrNull(index + 1)
        val dividerAfter = next != null && mailDateGroup(next.latest.dateMillis, now) == group
        add(MailListEntry.Row(row, dividerAfter))
    }
}
