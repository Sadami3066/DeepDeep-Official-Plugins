package io.github.whywood.superpi.plugin.rail

import io.github.whywood.superpi.plugin.api.PluginHost
import java.io.File
import java.util.Locale

internal data class Station(
    val code: String,
    val name: String,
    val city: String,
    val pinyin: String = "",
    val shortPinyin: String = "",
)

internal sealed interface StationResolution {
    data class Found(val station: Station) : StationResolution

    data class Candidates(val query: String, val stations: List<Station>) : StationResolution

    data object NotFound : StationResolution
}

internal class StationIndex internal constructor(private val stations: List<Station>) {
    private val byName = LinkedHashMap<String, Station>()
    private val byPinyin = LinkedHashMap<String, MutableList<Station>>()
    private val byShortPinyin = LinkedHashMap<String, Station>()
    private val byCity = LinkedHashMap<String, List<Station>>()

    init {
        stations.forEach { station ->
            byName.putIfAbsent(station.name, station)
            station.pinyin.trim().takeIf { it.isNotEmpty() }?.let { pinyin ->
                byPinyin.getOrPut(pinyin.lowercase(Locale.ROOT)) { mutableListOf() }.add(station)
            }
            if (station.city.isNotBlank()) {
                byCity[station.city] = byCity[station.city].orEmpty() + station
            }
        }
        stations.groupBy { it.shortPinyin.trim().lowercase(Locale.ROOT) }
            .forEach { (key, group) ->
                if (key.isNotEmpty() && group.size == 1) byShortPinyin.putIfAbsent(key, group.single())
            }
    }

    fun resolve(query: String): StationResolution {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return StationResolution.NotFound
        val withoutSuffix = trimmed.removeSuffix("站")
        byName[trimmed]?.let { return StationResolution.Found(it) }
        byName[withoutSuffix]?.let { return StationResolution.Found(it) }
        val pinyinKey = withoutSuffix.lowercase(Locale.ROOT)
        val pinyinStations = byPinyin[pinyinKey]
        if (!pinyinStations.isNullOrEmpty()) {
            return if (pinyinStations.size == 1) {
                StationResolution.Found(pinyinStations.single())
            } else {
                StationResolution.Candidates(withoutSuffix, pinyinStations)
            }
        }
        byShortPinyin[pinyinKey]?.let { return StationResolution.Found(it) }
        val cityKey = withoutSuffix.removeSuffix("市")
        byName[cityKey]?.let { return StationResolution.Found(it) }
        val cityStations = byCity[cityKey]
        if (!cityStations.isNullOrEmpty()) {
            return if (cityStations.size == 1) {
                StationResolution.Found(cityStations.single())
            } else {
                StationResolution.Candidates(cityKey, cityStations)
            }
        }
        return StationResolution.NotFound
    }

    fun nameOf(code: String): String = stations.firstOrNull { it.code == code }?.name ?: code

    fun stationsOfCity(city: String): List<Station> = byCity[city].orEmpty()

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
                Station(
                    code = code,
                    name = name,
                    city = fields.getOrNull(7)?.trim().orEmpty(),
                    pinyin = fields.getOrNull(3)?.trim().orEmpty(),
                    shortPinyin = fields.getOrNull(4)?.trim().orEmpty(),
                )
            }
            val index = StationIndex(parsed)
            cached = index
            return index
        }
    }
}
