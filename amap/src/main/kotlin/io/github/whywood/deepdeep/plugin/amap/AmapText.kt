package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(mapOf("default" to zh, "zh" to zh, "en" to en)))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String =
    text(localizedText(mapOf("default" to zh, "zh" to zh, "en" to en)), *args)

internal fun PluginHost.distanceText(meters: Int?): String? {
    val value = meters ?: return null
    return if (value < 1000) {
        tf("%d 米", "%d m", value)
    } else {
        tf("%.1f 公里", "%.1f km", value / 1000.0)
    }
}

internal fun PluginHost.durationText(seconds: Int?): String? {
    val value = seconds?.takeIf { it > 0 } ?: return null
    val minutes = value / 60
    return when {
        minutes < 1 -> t("不到 1 分钟", "Less than a minute")
        minutes < 60 -> tf("%d 分钟", "%d min", minutes)
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0) tf("%d 小时", "%d h", hours) else tf("%d 小时 %d 分钟", "%d h %d min", hours, rest)
        }
    }
}

internal fun PluginHost.routeTitle(mode: AmapRouteMode): String = when (mode) {
    AmapRouteMode.DRIVING -> t("驾车路线", "Driving route")
    AmapRouteMode.TRANSIT -> t("公交路线", "Transit route")
    AmapRouteMode.WALKING -> t("步行路线", "Walking route")
    AmapRouteMode.BICYCLING -> t("骑行路线", "Cycling route")
}
