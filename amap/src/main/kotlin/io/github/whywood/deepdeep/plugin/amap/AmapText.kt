package io.github.whywood.superpi.plugin.amap

import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.localizedText

internal fun interface AmapLocalizer {
    fun localize(zh: String, en: String, args: List<Any>): String
}

internal fun PluginHost.localizer(): AmapLocalizer = AmapLocalizer { zh, en, args ->
    text(localizedText(mapOf("default" to zh, "zh" to zh, "en" to en)), *args.toTypedArray())
}

internal fun AmapLocalizer.t(zh: String, en: String): String = localize(zh, en, emptyList())

internal fun AmapLocalizer.tf(zh: String, en: String, vararg args: Any): String = localize(zh, en, args.toList())

internal fun PluginHost.t(zh: String, en: String): String = localizer().t(zh, en)

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String = localizer().tf(zh, en, *args)

internal fun AmapLocalizer.distanceText(meters: Int?): String? {
    val value = meters ?: return null
    return if (value < 1000) {
        tf("%d 米", "%d m", value)
    } else {
        tf("%.1f 公里", "%.1f km", value / 1000.0)
    }
}

internal fun AmapLocalizer.durationText(seconds: Int?): String? {
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

internal fun AmapLocalizer.routeTitle(mode: AmapRouteMode): String = when (mode) {
    AmapRouteMode.DRIVING -> t("驾车路线", "Driving route")
    AmapRouteMode.TRANSIT -> t("公交路线", "Transit route")
    AmapRouteMode.WALKING -> t("步行路线", "Walking route")
    AmapRouteMode.BICYCLING -> t("骑行路线", "Cycling route")
}

internal fun PluginHost.distanceText(meters: Int?): String? = localizer().distanceText(meters)

internal fun PluginHost.durationText(seconds: Int?): String? = localizer().durationText(seconds)

internal fun PluginHost.routeTitle(mode: AmapRouteMode): String = localizer().routeTitle(mode)
