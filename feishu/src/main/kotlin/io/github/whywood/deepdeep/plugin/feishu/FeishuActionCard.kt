package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuActionResultCard(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    FeishuCardShell {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
        ) {
            FeishuCardHead(
                host = host,
                title = model.title?.takeIf { it.isNotBlank() } ?: host.t("已完成", "Done"),
                detail = null,
                trailing = model.trailing?.takeIf { it.isNotBlank() },
            )
            model.items.forEach { item ->
                Spacer(Modifier.height(DdSpacing.Md))
                ActionRow(item = item)
            }
            FeishuCardFoot(
                host = host,
                note = model.note?.takeIf { it.isNotBlank() },
                actionLabel = model.context?.takeIf { it.isNotBlank() },
                openUrl = model.openUrl,
            )
        }
    }
}

@Composable
private fun ActionRow(item: FeishuCardItem) {
    val colors = deepDeepColors()
    val label = item.title.takeIf { it.isNotBlank() }
    val body = item.body
    if (body != null) {
        label?.let {
            DdText(
                text = it,
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
        }
        DdText(
            text = body,
            style = DdType.bodySmall,
            color = colors.ink,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (label == null) return
    Row(verticalAlignment = Alignment.Top) {
        DdText(
            text = label,
            modifier = Modifier.widthIn(min = 56.dp),
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(
            text = item.subtitle.orEmpty(),
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
