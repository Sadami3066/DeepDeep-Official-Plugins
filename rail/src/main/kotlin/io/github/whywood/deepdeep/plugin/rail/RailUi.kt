package io.github.whywood.deepdeep.plugin.rail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

private sealed interface PageState {
    data object Idle : PageState
    data object Loading : PageState
    data class Failed(val message: String) : PageState
    data class Loaded(val details: TrainSearchDetails) : PageState
}

@Composable
internal fun TrainPage(host: PluginHost) {
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var state by remember { mutableStateOf<PageState>(PageState.Idle) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (from.isBlank() || to.isBlank() || state == PageState.Loading) return
        state = PageState.Loading
        scope.launch {
            state = try {
                PageState.Loaded(TrainService(host).search(from, to, date))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                PageState.Failed(error.message ?: host.t("查询失败", "Lookup failed"))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = DdSpacing.Xl, end = DdSpacing.Xl, top = DdSpacing.Md, bottom = DdSpacing.Xxl),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
    ) {
        DdTextField(value = from, onValueChange = { from = it }, placeholder = host.t("出发城市或车站", "From city or station"))
        DdTextField(value = to, onValueChange = { to = it }, placeholder = host.t("到达城市或车站", "To city or station"))
        DdTextField(value = date, onValueChange = { date = it }, placeholder = host.t("日期 YYYY-MM-DD", "Date YYYY-MM-DD"))
        DdPrimaryButton(
            text = host.t("查询车次", "Search trains"),
            onClick = ::search,
            enabled = from.isNotBlank() && to.isNotBlank() && date.isNotBlank() && state != PageState.Loading,
        )
        when (val current = state) {
            PageState.Idle -> DdText(
                text = host.t("输入出发和到达城市，查看 12306 车次与余票。", "Enter departure and arrival to see 12306 trains and seats."),
                style = DdType.bodySmall,
                color = deepDeepColors().inkTertiary,
            )
            PageState.Loading -> DdLoadingBlock(text = host.t("正在查询车次…", "Searching trains…"))
            is PageState.Failed -> DdNoticeBar(
                text = current.message,
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = ::search,
            )
            is PageState.Loaded -> TrainCard(host, current.details)
        }
    }
}

@Composable
internal fun TrainToolCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        DdGroupCard {
            DdLoadingBlock(
                modifier = Modifier.padding(vertical = DdSpacing.Lg),
                text = host.t("正在查询车次…", "Searching trains…"),
            )
        }
        return
    }
    if (result.isError) {
        DdNoticeBar(result.text ?: host.t("车次查询失败", "Train lookup failed"), tone = DdNoticeTone.DANGER)
        return
    }
    val details = result.details?.let { parseDetails(it) }
    if (details == null) {
        DdGroupCard {
            DdText(
                text = result.text ?: host.t("没有车次数据", "No train data"),
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
    } else {
        TrainCard(host, details)
    }
}

private fun parseDetails(details: JsonObject): TrainSearchDetails? = runCatching {
    JsonFormat.decodeFromJsonElement(TrainSearchDetails.serializer(), details)
}.getOrNull()

@Composable
private fun TrainCard(host: PluginHost, details: TrainSearchDetails) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(
                text = "${details.from} → ${details.to}",
                style = DdType.heading,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DdText(details.date, style = DdType.bodySmall, color = colors.inkTertiary)
            if (details.trains.isEmpty()) {
                DdText(host.t("这一天没有查到车次。", "No trains found for this date."), style = DdType.bodySmall, color = colors.inkSecondary)
            } else {
                DdHairline()
                DdText(host.t("余票以 12306 为准", "Availability from 12306"), style = DdType.bodySmall, color = colors.inkTertiary)
                details.trains.take(8).forEachIndexed { index, train ->
                    TrainRow(train)
                    if (index < details.trains.take(8).lastIndex) DdHairline()
                }
            }
        }
    }
}

@Composable
private fun TrainRow(train: TrainOption) {
    val colors = deepDeepColors()
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DdText(train.code, style = DdType.body, color = colors.ink)
            Spacer(Modifier.weight(1f))
            DdText("${train.depart} → ${train.arrive}", style = DdType.body, color = colors.ink)
        }
        DdText(
            "${train.fromStation} → ${train.toStation} · ${train.duration}",
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DdText(
            train.seats.take(4).joinToString(" · ") { "${it.name} ${it.count}" }.ifBlank { "—" },
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
