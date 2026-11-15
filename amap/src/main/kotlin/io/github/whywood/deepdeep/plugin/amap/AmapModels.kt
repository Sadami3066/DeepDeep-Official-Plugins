package io.github.whywood.deepdeep.plugin.amap

import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val AmapJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal enum class AmapRouteMode(val wire: String, val uriMode: String) {
    DRIVING("driving", "car"),
    TRANSIT("transit", "bus"),
    WALKING("walking", "walk"),
    BICYCLING("bicycling", "ride"),
    ;

    companion object {
        fun fromWire(value: String?): AmapRouteMode = entries.firstOrNull { it.wire == value } ?: DRIVING
    }
}

internal data class LatLng(val longitude: Double, val latitude: Double) {
    val wire: String
        get() = "${formatCoordinate(longitude)},${formatCoordinate(latitude)}"

    val display: String
        get() = "${formatCoordinate(longitude)}, ${formatCoordinate(latitude)}"

    companion object {
        fun parse(value: String?): LatLng? {
            val parts = value?.trim()?.split(',') ?: return null
            if (parts.size != 2) return null
            val longitude = parts[0].trim().toDoubleOrNull() ?: return null
            val latitude = parts[1].trim().toDoubleOrNull() ?: return null
            if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null
            return LatLng(longitude, latitude)
        }
    }
}

internal fun formatCoordinate(value: Double): String {
    val text = String.format(Locale.US, "%.6f", value)
    return text.trimEnd('0').trimEnd('.')
}

internal fun readablePlaceType(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.all { it.isDigit() }) return null
    return raw
}

internal fun parsePolyline(value: String?): List<LatLng> {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    return raw.split(';').mapNotNull { LatLng.parse(it) }
}

internal fun shortDateLabel(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') {
        return raw.substring(5, 7) + "/" + raw.substring(8, 10)
    }
    return raw.takeIf { it.isNotBlank() }
}

internal fun parseJsonText(text: String?): JsonElement? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    var index = raw.indexOfFirst { it == '{' || it == '[' }
    while (index >= 0 && index < raw.length) {
        val character = raw[index]
        if (character == '{' || character == '[') {
            val end = balancedEnd(raw, index)
            if (end != null) {
                val element = runCatching { AmapJson.parseToJsonElement(raw.substring(index, end + 1)) }.getOrNull()
                if (element is JsonObject || element is JsonArray) return element
            }
        }
        index++
    }
    return null
}

private fun balancedEnd(text: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val character = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return null
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonObject.textValue(vararg keys: String): String? {
    for (key in keys) {
        val primitive = this[key] as? JsonPrimitive ?: continue
        if (primitive is JsonNull) continue
        val content = primitive.contentOrNull?.trim()
        if (!content.isNullOrEmpty()) return content
    }
    return null
}

internal fun JsonObject.numberValue(vararg keys: String): Double? {
    for (key in keys) {
        val primitive = this[key] as? JsonPrimitive ?: continue
        primitive.contentOrNull?.toDoubleOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.intValue(vararg keys: String): Int? = numberValue(*keys)?.toInt()

internal fun JsonObject.objectValue(vararg keys: String): JsonObject? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonObject) return value
    }
    return null
}

internal fun JsonObject.arrayValue(vararg keys: String): JsonArray? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonArray) return value
    }
    return null
}

internal data class AmapPlace(
    val name: String,
    val address: String?,
    val typeName: String?,
    val distanceMeters: Int?,
    val location: LatLng?,
    val cityName: String?,
    val tel: String?,
)

internal data class AmapRegeo(
    val formattedAddress: String,
    val province: String?,
    val city: String?,
    val district: String?,
    val township: String?,
    val location: LatLng?,
)

internal data class AmapRouteStep(
    val instruction: String,
    val road: String?,
    val distanceMeters: Int?,
    val polyline: List<LatLng> = emptyList(),
)

internal data class AmapTransitLine(
    val lineName: String?,
    val walkingMeters: Int?,
)

internal data class AmapRoute(
    val mode: AmapRouteMode,
    val origin: LatLng?,
    val destination: LatLng?,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val steps: List<AmapRouteStep>,
    val transitLines: List<AmapTransitLine>,
)

internal data class AmapWeatherDay(
    val date: String?,
    val dayWeather: String?,
    val nightWeather: String?,
    val dayTemp: Int?,
    val nightTemp: Int?,
    val dayWind: String?,
    val nightWind: String?,
    val dayPower: String?,
    val nightPower: String?,
)

internal data class AmapWeather(
    val city: String?,
    val days: List<AmapWeatherDay>,
)

internal fun parsePlaces(payload: JsonElement?): List<AmapPlace> {
    val root = payload.asObjectOrNull() ?: return emptyList()
    val array = root.arrayValue("pois", "tips")
        ?: root.objectValue("data")?.arrayValue("pois")
        ?: root.objectValue("result")?.arrayValue("pois")
        ?: root.objectValue("data")?.objectValue("result")?.arrayValue("pois")
        ?: root.arrayValue("return")
        ?: root.objectValue("data")?.arrayValue("return")
        ?: root.objectValue("result")?.arrayValue("return")
        ?: root.arrayValue("geocodes")
        ?: root.objectValue("data")?.arrayValue("geocodes")
        ?: root.objectValue("result")?.arrayValue("geocodes")
        ?: return emptyList()
    return array.mapNotNull { it.asObjectOrNull()?.toPlace() }
}

private fun JsonObject.toPlace(): AmapPlace? {
    val name = textValue("name", "title", "formatted_address", "formattedAddress")
        ?: listOfNotNull(textValue("province"), textValue("city"), textValue("district"))
            .joinToString("")
            .takeIf { it.isNotBlank() }
        ?: return null
    return AmapPlace(
        name = name,
        address = textValue("address", "formatted_address", "formattedAddress"),
        typeName = readablePlaceType(textValue("type", "typecode", "level")),
        distanceMeters = intValue("distance", "distanceMeters", "distance_meters"),
        location = LatLng.parse(textValue("location")),
        cityName = textValue("cityname", "city"),
        tel = textValue("tel", "phone"),
    )
}

internal fun parseRegeo(payload: JsonElement?): AmapRegeo? {
    val root = payload.asObjectOrNull() ?: return null
    val regeo = root.objectValue("regeocode")
        ?: root.objectValue("data")?.objectValue("regeocode")
        ?: root.objectValue("result")?.objectValue("regeocode")
    if (regeo != null) {
        val component = regeo.objectValue("addressComponent", "address_component")
        val formatted = regeo.textValue("formatted_address", "formattedAddress")
            ?: listOfNotNull(
                component?.textValue("province"),
                component?.textValue("city"),
                component?.textValue("district"),
                component?.textValue("township"),
                component?.textValue("street"),
                component?.textValue("number", "streetNumber"),
            ).joinToString("").takeIf { it.isNotBlank() }
            ?: ""
        if (formatted.isBlank() && component == null) return null
        return AmapRegeo(
            formattedAddress = formatted,
            province = component?.textValue("province"),
            city = component?.textValue("city"),
            district = component?.textValue("district"),
            township = component?.textValue("township"),
            location = LatLng.parse(component?.textValue("location")),
        )
    }
    val province = root.textValue("provice", "province")
    val city = root.textValue("city")
    val district = root.textValue("district")
    val formatted = listOfNotNull(province, city, district).joinToString("")
    if (formatted.isBlank()) return null
    return AmapRegeo(
        formattedAddress = formatted,
        province = province,
        city = city,
        district = district,
        township = root.textValue("township"),
        location = LatLng.parse(root.textValue("location")),
    )
}

internal fun parseRoute(payload: JsonElement?, mode: AmapRouteMode): AmapRoute? {
    val root = payload.asObjectOrNull() ?: return null
    val route = root.objectValue("route")
        ?: root.objectValue("data")
        ?: root.objectValue("result")?.objectValue("route")
        ?: root
    val origin = LatLng.parse(route.textValue("origin") ?: root.textValue("origin"))
    val destination = LatLng.parse(route.textValue("destination") ?: root.textValue("destination"))
    if (mode == AmapRouteMode.TRANSIT) {
        val transits = route.arrayValue("transits") ?: root.arrayValue("transits")
        val first = transits?.firstOrNull()?.asObjectOrNull()
        val distance = route.intValue("distance", "distanceMeters") ?: first?.intValue("distance", "distanceMeters")
        val duration = first?.intValue("duration", "durationSeconds")
        val lines = first?.arrayValue("segments")?.mapNotNull { segment ->
            val obj = segment.asObjectOrNull() ?: return@mapNotNull null
            val bus = obj.objectValue("bus")?.arrayValue("buslines")?.firstOrNull()?.asObjectOrNull()
            val railway = obj.objectValue("railway")
            val lineName = bus?.textValue("name")?.takeIf { it.isNotBlank() }
                ?: railway?.let {
                    listOfNotNull(it.textValue("name"), it.textValue("trip"))
                        .joinToString(" ")
                        .takeIf { value -> value.isNotBlank() }
                }
            val walkingMeters = obj.objectValue("walking")?.intValue("distance", "distanceMeters")
            if (lineName == null && walkingMeters == null) return@mapNotNull null
            AmapTransitLine(lineName, walkingMeters)
        }.orEmpty()
        if (distance == null && duration == null && lines.isEmpty()) return null
        return AmapRoute(mode, origin, destination, distance, duration, emptyList(), lines)
    }
    val paths = route.arrayValue("paths")
        ?: root.arrayValue("paths")
        ?: root.objectValue("data")?.arrayValue("paths")
    val firstPath = paths?.firstOrNull()?.asObjectOrNull()
    val distance = route.intValue("distance", "distanceMeters") ?: firstPath?.intValue("distance", "distanceMeters")
    val duration = firstPath?.intValue("duration", "durationSeconds") ?: route.intValue("duration", "durationSeconds")
    val steps = firstPath?.arrayValue("steps")?.mapNotNull { step ->
        val obj = step.asObjectOrNull() ?: return@mapNotNull null
        val instruction = obj.textValue("instruction", "road") ?: return@mapNotNull null
        AmapRouteStep(
            instruction = instruction,
            road = obj.textValue("road"),
            distanceMeters = obj.intValue("distance", "distanceMeters"),
            polyline = parsePolyline(obj.textValue("polyline")),
        )
    }.orEmpty()
    if (distance == null && duration == null && steps.isEmpty()) return null
    return AmapRoute(mode, origin, destination, distance, duration, steps, emptyList())
}

internal fun parseWeather(payload: JsonElement?): AmapWeather? {
    val root = payload.asObjectOrNull() ?: return null
    val forecasts = root.arrayValue("forecasts")
        ?: root.objectValue("data")?.arrayValue("forecasts")
        ?: root.objectValue("result")?.arrayValue("forecasts")
    if (forecasts != null && forecasts.isNotEmpty()) {
        val first = forecasts.first().asObjectOrNull()
        val casts = first?.arrayValue("casts")?.mapNotNull { it.asObjectOrNull() }
            ?: forecasts.mapNotNull { it.asObjectOrNull() }
        val city = first?.textValue("city") ?: root.textValue("city")
        val days = casts.mapNotNull { it.toWeatherDay() }
        if (days.isNotEmpty()) return AmapWeather(city, days)
    }
    val lives = root.arrayValue("lives") ?: root.objectValue("data")?.arrayValue("lives")
    val live = lives?.firstOrNull()?.asObjectOrNull()
    if (live != null) {
        val weather = live.textValue("weather")
        val temperature = live.intValue("temperature")
        return AmapWeather(
            city = live.textValue("city"),
            days = listOf(
                AmapWeatherDay(
                    date = live.textValue("reporttime"),
                    dayWeather = weather,
                    nightWeather = weather,
                    dayTemp = temperature,
                    nightTemp = temperature,
                    dayWind = live.textValue("winddirection"),
                    nightWind = live.textValue("winddirection"),
                    dayPower = live.textValue("windpower"),
                    nightPower = live.textValue("windpower"),
                ),
            ),
        )
    }
    return null
}

private fun JsonObject.toWeatherDay(): AmapWeatherDay? {
    val dayWeather = textValue("dayweather", "dayWeather", "weather")
    val nightWeather = textValue("nightweather", "nightWeather", "weather")
    val dayTemp = intValue("daytemp", "dayTemp", "temperature")
    val nightTemp = intValue("nighttemp", "nightTemp", "temperature")
    val date = textValue("date", "reporttime")
    if (dayWeather == null && dayTemp == null && date == null) return null
    return AmapWeatherDay(
        date = date,
        dayWeather = dayWeather,
        nightWeather = nightWeather,
        dayTemp = dayTemp,
        nightTemp = nightTemp,
        dayWind = textValue("daywind", "dayWind"),
        nightWind = textValue("nightwind", "nightWind"),
        dayPower = textValue("daypower", "dayPower"),
        nightPower = textValue("nightpower", "nightPower"),
    )
}

internal enum class AmapFailure { INVALID_KEY, QUOTA, SERVICE, NETWORK, UNKNOWN }

internal fun classifyAmapFailure(statusCode: Int, body: String?): AmapFailure {
    val text = body.orEmpty()
    return when {
        statusCode == 401 || statusCode == 403 -> AmapFailure.INVALID_KEY
        text.contains("INVALID_USER_KEY", ignoreCase = true) ||
            text.contains("USER_KEY_RECYCLED", ignoreCase = true) ||
            text.contains("USERKEY_PLAT_NOMATCH", ignoreCase = true) ||
            text.contains("INVALID_USER_SCODE", ignoreCase = true) ||
            text.contains("SERVICE_NOT_AVAILABLE_FOR_USER", ignoreCase = true) -> AmapFailure.INVALID_KEY
        text.contains("DAILY_QUERY_OVER_LIMIT", ignoreCase = true) ||
            text.contains("ACCESS_TOO_FREQUENT", ignoreCase = true) ||
            text.contains("QPS_HAS_EXCEEDED_THE_LIMIT", ignoreCase = true) ||
            text.contains("CUQPS_HAS_EXCEEDED_THE_LIMIT", ignoreCase = true) -> AmapFailure.QUOTA
        statusCode >= 500 ||
            text.contains("SERVICE_NOT_AVAILABLE", ignoreCase = true) ||
            text.contains("UNKNOWN_ERROR", ignoreCase = true) -> AmapFailure.SERVICE
        else -> AmapFailure.UNKNOWN
    }
}

internal object AmapUri {
    private const val BASE = "https://uri.amap.com"

    fun marker(location: LatLng, name: String? = null): String = buildString {
        append(BASE).append("/marker?position=").append(location.wire)
        if (!name.isNullOrBlank()) append("&name=").append(encode(name))
        append("&coordinate=gaode&callnative=1")
    }

    fun search(keyword: String, city: String? = null): String = buildString {
        append(BASE).append("/search?keyword=").append(encode(keyword))
        if (!city.isNullOrBlank()) append("&city=").append(encode(city))
        append("&coordinate=gaode&callnative=1")
    }

    fun navigation(
        origin: LatLng?,
        destination: LatLng,
        mode: AmapRouteMode,
        originName: String? = null,
        destinationName: String? = null,
    ): String = buildString {
        append(BASE).append("/navigation?")
        if (origin != null) append("from=").append(point(origin, originName)).append("&")
        append("to=").append(point(destination, destinationName))
        append("&mode=").append(mode.uriMode)
        append("&policy=1&coordinate=gaode&callnative=1")
    }

    private fun point(location: LatLng, name: String?): String = buildString {
        append(location.wire)
        if (!name.isNullOrBlank()) append(',').append(encode(name))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal data class AmapPlaceItem(
    val name: String,
    val address: String?,
    val typeName: String?,
    val distanceMeters: Int?,
    val location: LatLng?,
)

internal data class AmapPlacesDetails(
    val query: String?,
    val city: String?,
    val items: List<AmapPlaceItem>,
)

internal data class AmapRegeoDetails(
    val formattedAddress: String,
    val province: String?,
    val city: String?,
    val district: String?,
    val township: String?,
    val location: LatLng?,
)

internal data class AmapRouteDetails(
    val mode: AmapRouteMode,
    val originName: String?,
    val destinationName: String?,
    val origin: LatLng?,
    val destination: LatLng?,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val steps: List<AmapRouteStep>,
    val transitLines: List<AmapTransitLine>,
    val navigationUrl: String?,
)

internal data class AmapWeatherDetails(
    val city: String?,
    val days: List<AmapWeatherDay>,
)

internal fun decodePlacesDetails(details: JsonObject?): AmapPlacesDetails {
    val root = details ?: return AmapPlacesDetails(null, null, emptyList())
    val items = root.arrayValue("items")?.mapNotNull { element ->
        val item = element.asObjectOrNull() ?: return@mapNotNull null
        val name = item.textValue("name") ?: return@mapNotNull null
        AmapPlaceItem(
            name = name,
            address = item.textValue("address"),
            typeName = readablePlaceType(item.textValue("typeName")),
            distanceMeters = item.intValue("distanceMeters"),
            location = latLngFrom(item),
        )
    }.orEmpty()
    return AmapPlacesDetails(root.textValue("query"), root.textValue("city"), items)
}

internal fun decodeRegeoDetails(details: JsonObject?): AmapRegeoDetails? {
    val root = details ?: return null
    val formatted = root.textValue("formattedAddress") ?: return null
    return AmapRegeoDetails(
        formattedAddress = formatted,
        province = root.textValue("province"),
        city = root.textValue("city"),
        district = root.textValue("district"),
        township = root.textValue("township"),
        location = latLngFrom(root),
    )
}

internal fun decodeRouteDetails(details: JsonObject?): AmapRouteDetails? {
    val root = details ?: return null
    val steps = root.arrayValue("steps")?.mapNotNull { element ->
        val step = element.asObjectOrNull() ?: return@mapNotNull null
        val instruction = step.textValue("instruction") ?: return@mapNotNull null
        AmapRouteStep(instruction, step.textValue("road"), step.intValue("distanceMeters"))
    }.orEmpty()
    val lines = root.arrayValue("transitLines")?.mapNotNull { element ->
        val line = element.asObjectOrNull() ?: return@mapNotNull null
        AmapTransitLine(line.textValue("lineName"), line.intValue("walkingMeters"))
    }.orEmpty()
    return AmapRouteDetails(
        mode = AmapRouteMode.fromWire(root.textValue("mode")),
        originName = root.textValue("originName"),
        destinationName = root.textValue("destinationName"),
        origin = latLngFrom(root.objectValue("origin") ?: JsonObject(emptyMap())),
        destination = latLngFrom(root.objectValue("destination") ?: JsonObject(emptyMap())),
        distanceMeters = root.intValue("distanceMeters"),
        durationSeconds = root.intValue("durationSeconds"),
        steps = steps,
        transitLines = lines,
        navigationUrl = root.textValue("navigationUrl"),
    )
}

internal fun decodeWeatherDetails(details: JsonObject?): AmapWeatherDetails? {
    val root = details ?: return null
    val days = root.arrayValue("days")?.mapNotNull { element ->
        val day = element.asObjectOrNull() ?: return@mapNotNull null
        AmapWeatherDay(
            date = day.textValue("date"),
            dayWeather = day.textValue("dayWeather"),
            nightWeather = day.textValue("nightWeather"),
            dayTemp = day.intValue("dayTemp"),
            nightTemp = day.intValue("nightTemp"),
            dayWind = day.textValue("dayWind"),
            nightWind = day.textValue("nightWind"),
            dayPower = day.textValue("dayPower"),
            nightPower = day.textValue("nightPower"),
        )
    }.orEmpty()
    return AmapWeatherDetails(root.textValue("city"), days)
}

internal fun latLngFrom(obj: JsonObject): LatLng? {
    val longitude = obj.numberValue("longitude") ?: return null
    val latitude = obj.numberValue("latitude") ?: return null
    return LatLng(longitude, latitude)
}
