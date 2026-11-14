package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.coroutines.launch

@Composable
internal fun RidePage(host: PluginHost) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf("") }
    var environment by rememberSaveable { mutableStateOf("sandbox") }
    var saved by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        key = host.settings().get(DidiClient.KEY_SETTING).orEmpty()
        environment = host.settings().get(DidiClient.ENV_SETTING).orEmpty().ifBlank { "sandbox" }
        saved = key.isNotBlank()
    }

    fun save() {
        scope.launch {
            host.settings().put(DidiClient.KEY_SETTING, key.trim())
            host.settings().put(DidiClient.ENV_SETTING, environment)
            saved = key.isNotBlank()
            host.ui().snackbar(host.t("滴滴配置已保存", "Didi settings saved"))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.Xl, vertical = DdSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DdText("滴滴出行", modifier = Modifier.weight(1f), style = DdType.heading, color = colors.ink)
                    DdText(if (saved) "已配置" else "未配置", style = DdType.bodySmall, color = colors.inkTertiary)
                }
                DdTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = "MCP Key",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg)) {
                    DdTextAction(
                        text = "调试环境",
                        onClick = { environment = "sandbox" },
                        color = if (environment == "sandbox") colors.accent else colors.inkTertiary,
                    )
                    DdTextAction(
                        text = "正式环境",
                        onClick = { environment = "production" },
                        color = if (environment == "production") colors.accent else colors.inkTertiary,
                    )
                }
                DdText(
                    "调试环境返回模拟数据，不产生真实订单。",
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
        }
        DdPrimaryButton(
            text = "保存配置",
            onClick = ::save,
            enabled = key.isNotBlank(),
        )
        DdText(
            "打车查询在对话里完成；真实发单前会让你确认车型和价格。",
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
    }
}
