package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.slotBinding
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState

internal fun sysuCardBinding(host: PluginHost): SlotBinding = slotBinding(
    slotId = SlotIds.chatMessage(SYSU_CARD_TYPE),
    content = composeSlot { state ->
        val message = state as? CustomMessageSlotState ?: return@composeSlot
        SysuChatCard(host = host, message = message)
    },
)

@Composable
private fun SysuChatCard(host: PluginHost, message: CustomMessageSlotState) {
    val model = remember(message.payload) {
        CliCardCodec.decode(message.payload, SYSU_CARD_KINDS)
    }
    val motionEnabled = LocalDdMotionEnabled.current
    val enter = remember(model) { Animatable(if (motionEnabled) 0f else 1f) }
    LaunchedEffect(model) {
        if (motionEnabled) enter.animateTo(1f, tween(durationMillis = DdMotion.FAST, easing = DdMotion.EaseOut))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 6.dp.toPx()
            },
    ) {
        if (model == null) {
            SysuErrorCard(
                host = host,
                model = CliCardModel(
                    kind = "",
                    state = CliCardState.ERROR,
                    note = host.t("这张结果暂时无法显示，重新查询一次即可。", "This result can't be shown right now. Run the query again."),
                ),
            )
        } else {
            SysuCardContent(host, model)
        }
    }
}

@Composable
private fun SysuCardContent(host: PluginHost, model: CliCardModel) {
    when (model.state) {
        CliCardState.LOADING -> SysuLoadingCard(host, model)
        CliCardState.EMPTY -> SysuEmptyCard(host, model)
        CliCardState.ERROR -> SysuErrorCard(host, model)
        else -> when (SysuCardKind.fromWire(model.kind)) {
            SysuCardKind.Schedule -> SysuScheduleCard(host, model)
            SysuCardKind.Grades -> SysuGradesCard(host, model)
            SysuCardKind.Exams -> SysuExamsCard(host, model)
            SysuCardKind.Lms -> SysuLmsCard(host, model)
            SysuCardKind.Todo -> SysuTodoCard(host, model)
            SysuCardKind.Gym -> SysuGymCard(host, model)
            SysuCardKind.Netpay -> SysuNetpayCard(host, model)
            null -> SysuFallbackListCard(host, model)
        }
    }
}
