package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.localizedText

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String =
    text(localizedText(default = zh, zh = zh, en = en), *args)

internal fun PluginHost.featureName(feature: MiCloudFeature): String =
    t(featureNameZh(feature), featureNameEn(feature))

internal fun PluginHost.miCloudText(): MiCloudText = { zh, en -> t(zh, en) }
