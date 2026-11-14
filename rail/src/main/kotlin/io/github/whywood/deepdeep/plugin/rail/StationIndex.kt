package io.github.whywood.deepdeep.plugin.rail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File

internal data class Station(val code: String, val name: String, val city: String, val pinyin: String = "")

internal class StationIndex private constructor(private val stations: List<Station>) {
    private val byName = HashMap<String, Station>()
    private val byAlias = HashMap<String, Station>()

    init {
        stations.forEach { station ->
            byName.putIfAbsent(station.name, station)
            byName.putIfAbsent(station.name.removeSuffix("站"), station)
            station.city.takeIf { it.isNotBlank() }?.let { byName.putIfAbsent(it, station) }
            byAlias.putIfAbsent(station.name.lowercase(), station)
            if (station.pinyin.isNotBlank()) byAlias.putIfAbsent(station.pinyin.lowercase(), station)
        }
    }

    fun resolve(query: String): Station? {
        val cleaned = query.trim().removeSuffix("站")
        if (cleaned.isEmpty()) return null
        byName[cleaned]?.let { return it }
        byName[query.trim()]?.let { return it }
        byAlias[cleaned.lowercase()]?.let { return it }
        return stations.firstOrNull { it.name.startsWith(cleaned) || it.city.startsWith(cleaned) }
    }

    fun nameOf(code: String): String = stations.firstOrNull { it.code == code }?.name ?: code

    companion object {
        private var cached: StationIndex? = null

        fun load(host: PluginHost): StationIndex {
            cached?.let { return it }
            val raw = runCatching { File(host.assetsDir, "station_name.js").readText() }.getOrDefault("")
            val parsed = raw.split('@').drop(1).mapNotNull { entry ->
                val fields = entry.split('|')
                if (fields.size < 3) return@mapNotNull null
                val code = fields[2].trim()
                val name = fields[1].trim()
                if (code.isBlank() || name.isBlank()) return@mapNotNull null
                val city = fields.getOrNull(7)?.trim().orEmpty()
                val pinyin = fields.getOrNull(3)?.trim().orEmpty()
                Station(code = code, name = name, city = city, pinyin = pinyin)
            }
            val index = StationIndex(parsed)
            cached = index
            return index
        }
    }
}
