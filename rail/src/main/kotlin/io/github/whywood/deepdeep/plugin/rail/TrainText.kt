package io.github.whywood.deepdeep.plugin.rail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun trainSummary(host: PluginHost, details: TrainSearchDetails): String {
    if (details.trains.isEmpty()) {
        return host.t("${details.from} → ${details.to} ${details.date} 没有查到车次。", "No trains found for ${details.from} → ${details.to} on ${details.date}.")
    }
    val header = host.t("${details.from} → ${details.to} · ${details.date}", "${details.from} → ${details.to} · ${details.date}")
    return buildString {
        append(header).append("\n")
        details.trains.take(6).forEach { train ->
            append(train.code).append(' ')
            append(train.depart).append("→").append(train.arrive).append(' ')
            append(train.duration)
            if (train.seats.isNotEmpty()) {
                append(" · ")
                append(train.seats.take(4).joinToString(" ") { "${it.name} ${it.count}" })
            }
            append('\n')
        }
    }.trimEnd()
}
