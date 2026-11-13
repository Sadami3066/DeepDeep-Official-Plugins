package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String =
    text(localizedText(default = zh, zh = zh, en = en), *args)
