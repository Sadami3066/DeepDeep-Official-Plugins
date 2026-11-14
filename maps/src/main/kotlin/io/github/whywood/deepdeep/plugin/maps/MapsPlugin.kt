package io.github.whywood.deepdeep.plugin.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdWebContent
import io.github.whywood.deepdeep.design.DdWebView
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import java.io.File

class MapsPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings = emptyList<io.github.whywood.deepdeep.plugin.api.SlotBinding>()

        override val pages = listOf(
            pluginPage(
                id = "main",
                title = localizedText(default = "地图", zh = "地图", en = "Maps"),
            ) {
                MapPage(host)
            },
        )
    }
}

@Composable
private fun MapPage(host: PluginHost) {
    val html = remember(host.assetsDir) {
        runCatching { File(host.assetsDir, "map.html").readText() }.getOrDefault("")
    }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
        DdWebView(
            content = DdWebContent.Html(
                html = html,
                baseUrl = "file://" + host.assetsDir.absolutePath.trimEnd('/') + "/",
            ),
            modifier = Modifier.fillMaxSize(),
            allowFileAccess = true,
            javaScriptEnabled = true,
            domStorageEnabled = true,
            darkMode = false,
            onError = {
                error = host.t("地图加载失败，请检查网络后重试。", "The map didn't load. Check your network and try again.")
            },
        )
        error?.let { message ->
            DdNoticeBar(
                text = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(DdSpacing.Xl),
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = {
                    error = null
                },
            )
        }
        }
    }
}

private fun PluginHost.t(zh: String, en: String): String =
    text(io.github.whywood.deepdeep.plugin.api.localizedText(default = zh, zh = zh, en = en))
