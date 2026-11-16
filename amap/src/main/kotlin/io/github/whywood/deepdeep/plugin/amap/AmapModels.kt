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
    val raw = value?.trim()?.substringBefore(';')?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.any { !it.isDigit() }) return raw
    for (length in intArrayOf(6, 4, 2)) {
        if (raw.length < length) continue
        AmapPlaceTypes[raw.take(length)]?.let { return it }
    }
    return null
}

internal fun httpsPhotoUrl(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return when {
        raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.substring("http://".length)
        raw.startsWith("//") -> "https:$raw"
        else -> raw
    }
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

internal fun JsonObject.textOrJoined(vararg keys: String): String? {
    for (key in keys) {
        when (val value = this[key]) {
            is JsonPrimitive -> {
                if (value is JsonNull) continue
                val content = value.contentOrNull?.trim()
                if (!content.isNullOrEmpty()) return content
            }
            is JsonArray -> {
                val parts = value.mapNotNull { element ->
                    when (element) {
                        is JsonPrimitive -> if (element is JsonNull) null else element.contentOrNull?.trim()
                        is JsonObject -> element.textValue("name", "value", "title")
                        else -> null
                    }?.takeIf { it.isNotEmpty() }
                }
                if (parts.isNotEmpty()) return parts.joinToString(", ")
            }
            else -> continue
        }
    }
    return null
}

internal data class AmapPlace(
    val id: String?,
    val name: String,
    val address: String?,
    val typecode: String?,
    val typeName: String?,
    val location: LatLng?,
    val photos: List<String> = emptyList(),
)

internal data class AmapRegeo(
    val formattedAddress: String,
    val country: String?,
    val province: String?,
    val city: String?,
    val district: String?,
    val township: String?,
    val location: LatLng?,
    val street: String? = null,
    val streetNumber: String? = null,
)

internal data class AmapRouteStep(
    val instruction: String,
    val road: String?,
    val distanceMeters: Int?,
    val durationSeconds: Int? = null,
    val polyline: List<LatLng> = emptyList(),
)

internal data class AmapTransitOption(
    val name: String,
    val departureStop: String? = null,
    val arrivalStop: String? = null,
    val viaStops: List<String> = emptyList(),
    val viaCount: Int? = null,
    val distanceMeters: Int? = null,
    val durationSeconds: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
)

internal data class AmapTransitLine(
    val lineName: String?,
    val walkingMeters: Int?,
    val walkingSeconds: Int? = null,
    val departureStop: String? = null,
    val arrivalStop: String? = null,
    val entrance: String? = null,
    val exit: String? = null,
    val viaStops: List<String> = emptyList(),
    val viaCount: Int? = null,
    val buslineNames: List<String> = emptyList(),
    val lineDistanceMeters: Int? = null,
    val lineDurationSeconds: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val options: List<AmapTransitOption> = emptyList(),
)

internal data class AmapRoutePath(
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val steps: List<AmapRouteStep> = emptyList(),
    val transitLines: List<AmapTransitLine> = emptyList(),
    val walkingDistanceMeters: Int? = null,
)

internal data class AmapRoute(
    val mode: AmapRouteMode,
    val origin: LatLng?,
    val destination: LatLng?,
    val paths: List<AmapRoutePath>,
) {
    val best: AmapRoutePath?
        get() = paths.firstOrNull()

    val distanceMeters: Int?
        get() = best?.distanceMeters

    val durationSeconds: Int?
        get() = best?.durationSeconds

    val steps: List<AmapRouteStep>
        get() = best?.steps.orEmpty()

    val transitLines: List<AmapTransitLine>
        get() = best?.transitLines.orEmpty()
}

internal data class AmapWeatherDay(
    val date: String?,
    val week: String? = null,
    val dayWeather: String?,
    val nightWeather: String?,
    val dayTemp: Int?,
    val nightTemp: Int?,
    val dayWind: String?,
    val nightWind: String?,
    val dayPower: String?,
    val nightPower: String?,
    val dayTempFloat: Double? = null,
    val nightTempFloat: Double? = null,
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
    val typecode = textValue("typecode", "type_code")
    return AmapPlace(
        id = textValue("id", "poiid"),
        name = name,
        address = textValue("address", "formatted_address", "formattedAddress"),
        typecode = typecode,
        typeName = readablePlaceType(textValue("type", "level") ?: typecode),
        location = LatLng.parse(textValue("location")),
        photos = photoUrls(),
    )
}

private fun JsonObject.photoUrls(): List<String> = buildList {
    textValue("photo", "photo_url", "image")?.let(::add)
    arrayValue("photos")?.forEach { element ->
        when (element) {
            is JsonPrimitive -> if (element !is JsonNull) element.contentOrNull?.let(::add)
            is JsonObject -> element.textValue("url", "URL", "photo_url")?.let(::add)
            else -> Unit
        }
    }
}.mapNotNull(::httpsPhotoUrl).distinct()

internal fun parseRegeo(payload: JsonElement?): AmapRegeo? {
    val root = payload.asObjectOrNull() ?: return null
    val regeo = root.objectValue("regeocode")
        ?: root.objectValue("data")?.objectValue("regeocode")
        ?: root.objectValue("result")?.objectValue("regeocode")
    if (regeo != null) {
        val component = regeo.objectValue("addressComponent", "address_component")
        val street = component?.textOrJoined("street", "streetName")
        val number = component?.textOrJoined("number", "streetNumber", "street_number")
        val componentProvince = component?.textValue("province")
        val formatted = regeo.textValue("formatted_address", "formattedAddress")
            ?: listOfNotNull(
                componentProvince,
                component?.textValue("city")?.takeIf { it != componentProvince },
                component?.textValue("district"),
                component?.textValue("township"),
                street,
                number,
            ).joinToString("").takeIf { it.isNotBlank() }
            ?: ""
        if (formatted.isBlank() && component == null) return null
        return AmapRegeo(
            formattedAddress = formatted,
            country = regeo.textValue("country") ?: root.textValue("country"),
            province = component?.textValue("province"),
            city = component?.textValue("city"),
            district = component?.textValue("district"),
            township = component?.textValue("township"),
            location = LatLng.parse(component?.textValue("location")),
            street = street,
            streetNumber = number,
        )
    }
    val province = root.textValue("provice", "province")
    val city = root.textValue("city")
    val district = root.textValue("district")
    val street = root.textOrJoined("street", "streetName")
    val number = root.textOrJoined("number", "streetNumber")
    val formatted = listOfNotNull(province, city?.takeIf { it != province }, district).joinToString("")
    if (formatted.isBlank()) return null
    return AmapRegeo(
        formattedAddress = formatted,
        country = root.textValue("country"),
        province = province,
        city = city,
        district = district,
        township = root.textValue("township"),
        location = LatLng.parse(root.textValue("location")),
        street = street,
        streetNumber = number,
    )
}

internal fun parseRoute(payload: JsonElement?, mode: AmapRouteMode): AmapRoute? {
    val root = payload.asObjectOrNull() ?: return null
    val route = root.objectValue("route")
        ?: root.objectValue("data")?.objectValue("route")
        ?: root.objectValue("data")
        ?: root.objectValue("result")?.objectValue("route")
        ?: root
    val origin = LatLng.parse(route.textValue("origin") ?: root.textValue("origin"))
    val destination = LatLng.parse(route.textValue("destination") ?: root.textValue("destination"))
    val paths = if (mode == AmapRouteMode.TRANSIT) {
        val transits = route.arrayValue("transits")
            ?: root.arrayValue("transits")
            ?: root.objectValue("data")?.arrayValue("transits")
        parseTransitPaths(transits.orEmpty(), route)
    } else {
        val rawPaths = route.arrayValue("paths")
            ?: root.arrayValue("paths")
            ?: root.objectValue("data")?.arrayValue("paths")
            ?: root.objectValue("result")?.arrayValue("paths")
        parseRoadPaths(rawPaths.orEmpty())
    }.ifEmpty { listOfNotNull(normalizedPath(route, mode)) }
    if (paths.isEmpty()) return null
    return AmapRoute(mode, origin, destination, paths)
}

private fun parseRoadPaths(paths: List<JsonElement>): List<AmapRoutePath> = paths.mapNotNull { element ->
    val path = element.asObjectOrNull() ?: return@mapNotNull null
    val steps = path.arrayValue("steps")?.mapNotNull { stepElement ->
        val step = stepElement.asObjectOrNull() ?: return@mapNotNull null
        val road = step.textValue("road", "road_name")
        val instruction = step.textValue("instruction", "assistant_action", "action")
            ?: road
            ?: return@mapNotNull null
        AmapRouteStep(
            instruction = instruction,
            road = road,
            distanceMeters = step.intValue("distance", "distanceMeters"),
            durationSeconds = step.intValue("duration", "durationSeconds"),
            polyline = parsePolyline(step.textValue("polyline")),
        )
    }.orEmpty()
    val distance = path.intValue("distance", "distanceMeters")
    val duration = path.intValue("duration", "durationSeconds")
    if (distance == null && duration == null && steps.isEmpty()) return@mapNotNull null
    AmapRoutePath(distance, duration, steps)
}

private fun parseTransitPaths(transits: List<JsonElement>, route: JsonObject): List<AmapRoutePath> {
    val paths = transits.mapNotNull { element ->
        val transit = element.asObjectOrNull() ?: return@mapNotNull null
        val lines = transit.arrayValue("segments")?.mapNotNull { segment ->
            parseTransitSegment(segment.asObjectOrNull() ?: return@mapNotNull null)
        }.orEmpty()
        val distance = transit.intValue("distance", "distanceMeters")
        val duration = transit.intValue("duration", "durationSeconds")
        val walkingDistance = transit.intValue("walking_distance", "walkingDistance")
        if (distance == null && duration == null && lines.isEmpty()) return@mapNotNull null
        AmapRoutePath(distance, duration, emptyList(), lines, walkingDistance)
    }
    val routeDistance = route.intValue("distance", "distanceMeters") ?: return paths
    return paths.mapIndexed { index, path ->
        if (index == 0 && path.distanceMeters == null) path.copy(distanceMeters = routeDistance) else path
    }
}

private data class TransitOption(
    val name: String,
    val departureStop: String?,
    val arrivalStop: String?,
    val viaStops: List<String>,
    val viaCount: Int?,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val startTime: String?,
    val endTime: String?,
)

private fun parseTransitSegment(segment: JsonObject): AmapTransitLine? {
    val walking = segment.objectValue("walking")
    val walkingMeters = walking?.intValue("distance", "distanceMeters")
    val walkingSeconds = walking?.intValue("duration", "durationSeconds")
    val entrance = stopName(segment, "entrance")
    val exit = stopName(segment, "exit")
    val buslines = segment.objectValue("bus")?.arrayValue("buslines").orEmpty()
    val railway = segment.objectValue("railway")
    val options = buildList {
        buslines.forEach { element ->
            val busline = element.asObjectOrNull() ?: return@forEach
            val name = busline.textValue("name") ?: return@forEach
            add(
                TransitOption(
                    name = name,
                    departureStop = stopName(busline, "departure_stop"),
                    arrivalStop = stopName(busline, "arrival_stop"),
                    viaStops = stopNames(busline.arrayValue("via_stops")),
                    viaCount = busline.intValue("via_num", "via_stops_num"),
                    distanceMeters = busline.intValue("distance", "distanceMeters"),
                    durationSeconds = busline.intValue("duration", "durationSeconds"),
                    startTime = busline.textValue("start_time", "startTime"),
                    endTime = busline.textValue("end_time", "endTime"),
                ),
            )
        }
        if (railway != null) {
            val name = railway.textOrJoined("name", "trip")
            if (!name.isNullOrBlank()) {
                add(
                    TransitOption(
                        name = name,
                        departureStop = stopName(railway, "departure_stop"),
                        arrivalStop = stopName(railway, "arrival_stop"),
                        viaStops = stopNames(railway.arrayValue("via_stops")),
                        viaCount = railway.intValue("via_num"),
                        distanceMeters = railway.intValue("distance"),
                        durationSeconds = railway.intValue("duration"),
                        startTime = railway.textValue("start_time"),
                        endTime = railway.textValue("end_time"),
                    ),
                )
            }
        }
    }
    val lineNames = options.map { it.name }
    val lineName = lineNames.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    val viaStops = options.flatMap { it.viaStops }.distinct()
    val viaCount = options.firstNotNullOfOrNull { it.viaCount }
    val lineDistance = options.firstNotNullOfOrNull { it.distanceMeters }
    val lineDuration = options.firstNotNullOfOrNull { it.durationSeconds }
    val startTime = options.firstNotNullOfOrNull { it.startTime }
    val endTime = options.firstNotNullOfOrNull { it.endTime }
    val departure = options.firstNotNullOfOrNull { it.departureStop }
    val arrival = options.firstNotNullOfOrNull { it.arrivalStop }
    val hasFacts = lineName != null || walkingMeters != null || entrance != null || exit != null ||
        departure != null || arrival != null
    if (!hasFacts) return null
    return AmapTransitLine(
        lineName = lineName,
        walkingMeters = walkingMeters,
        walkingSeconds = walkingSeconds,
        departureStop = departure,
        arrivalStop = arrival,
        entrance = entrance,
        exit = exit,
        viaStops = viaStops,
        viaCount = viaCount,
        buslineNames = lineNames,
        lineDistanceMeters = lineDistance,
        lineDurationSeconds = lineDuration,
        startTime = startTime,
        endTime = endTime,
        options = options.map { option ->
            AmapTransitOption(
                name = option.name,
                departureStop = option.departureStop,
                arrivalStop = option.arrivalStop,
                viaStops = option.viaStops,
                viaCount = option.viaCount,
                distanceMeters = option.distanceMeters,
                durationSeconds = option.durationSeconds,
                startTime = option.startTime,
                endTime = option.endTime,
            )
        },
    )
}

private fun stopName(owner: JsonObject, key: String): String? {
    val direct = owner.objectValue(key)
    if (direct != null) return direct.textOrJoined("name", "stop_name")
    val array = owner.arrayValue(key)
    if (array != null) {
        return array.firstOrNull()?.let { element ->
            when (element) {
                is JsonObject -> element.textOrJoined("name", "stop_name")
                is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }
    return owner.textOrJoined(key)
}

private fun stopNames(array: JsonArray?): List<String> = array?.mapNotNull { element ->
    when (element) {
        is JsonObject -> element.textOrJoined("name", "stop_name")
        is JsonPrimitive -> if (element is JsonNull) null else element.contentOrNull?.trim()
        else -> null
    }?.takeIf { it.isNotEmpty() }
}.orEmpty()

private fun normalizedPath(route: JsonObject, mode: AmapRouteMode): AmapRoutePath? {
    if (mode == AmapRouteMode.TRANSIT) {
        val lines = route.arrayValue("segments")?.mapNotNull { segment ->
            parseTransitSegment(segment.asObjectOrNull() ?: return@mapNotNull null)
        }.orEmpty()
        val distance = route.intValue("distance", "distanceMeters")
        val duration = route.intValue("duration", "durationSeconds")
        val walkingDistance = route.intValue("walking_distance", "walkingDistance")
        if (distance == null && duration == null && lines.isEmpty()) return null
        return AmapRoutePath(distance, duration, emptyList(), lines, walkingDistance)
    }
    val steps = route.arrayValue("steps")?.mapNotNull { element ->
        val step = element.asObjectOrNull() ?: return@mapNotNull null
        val instruction = step.textValue("instruction", "road") ?: return@mapNotNull null
        AmapRouteStep(
            instruction = instruction,
            road = step.textValue("road"),
            distanceMeters = step.intValue("distance", "distanceMeters"),
            durationSeconds = step.intValue("duration", "durationSeconds"),
            polyline = parsePolyline(step.textValue("polyline")),
        )
    }.orEmpty()
    val distance = route.intValue("distance", "distanceMeters")
    val duration = route.intValue("duration", "durationSeconds")
    if (distance == null && duration == null && steps.isEmpty()) return null
    return AmapRoutePath(distance, duration, steps)
}

internal fun parseWeather(payload: JsonElement?): AmapWeather? {
    val root = payload.asObjectOrNull() ?: return null
    val forecasts = root.arrayValue("forecasts")
        ?: root.objectValue("data")?.arrayValue("forecasts")
        ?: root.objectValue("result")?.arrayValue("forecasts")
    if (forecasts != null && forecasts.isNotEmpty()) {
        val days = mutableListOf<AmapWeatherDay>()
        var city: String? = null
        forecasts.forEach { element ->
            val forecast = element.asObjectOrNull() ?: return@forEach
            if (city == null) city = forecast.textValue("city")
            val casts = forecast.arrayValue("casts")
            if (casts != null) {
                casts.forEach { cast ->
                    cast.asObjectOrNull()?.toWeatherDay()?.let(days::add)
                }
            } else {
                forecast.toWeatherDay()?.let(days::add)
            }
        }
        if (days.isNotEmpty()) return AmapWeather(city ?: root.textValue("city"), days)
    }
    val lives = root.arrayValue("lives") ?: root.objectValue("data")?.arrayValue("lives")
    if (lives != null && lives.isNotEmpty()) {
        val days = lives.mapNotNull { element ->
            val live = element.asObjectOrNull() ?: return@mapNotNull null
            val weather = live.textValue("weather")
            val temperature = live.intValue("temperature")
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
            )
        }
        if (days.isNotEmpty()) return AmapWeather(lives.first().asObjectOrNull()?.textValue("city"), days)
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
        week = textValue("week", "weekday"),
        dayWeather = dayWeather,
        nightWeather = nightWeather,
        dayTemp = dayTemp,
        nightTemp = nightTemp,
        dayWind = textValue("daywind", "dayWind"),
        nightWind = textValue("nightwind", "nightWind"),
        dayPower = textValue("daypower", "dayPower"),
        nightPower = textValue("nightpower", "nightPower"),
        dayTempFloat = numberValue("daytemp_float", "dayTempFloat"),
        nightTempFloat = numberValue("nighttemp_float", "nightTempFloat"),
    )
}

internal enum class AmapFailure { INVALID_KEY, QUOTA, PARAMETER, SERVICE, NETWORK, PROTOCOL, INTERNAL, UNKNOWN }

internal fun classifyAmapFailure(statusCode: Int, body: String?): AmapFailure {
    val text = body.orEmpty()
    return when {
        statusCode == 401 || statusCode == 403 -> AmapFailure.INVALID_KEY
        text.contains("INVALID_USER_KEY", ignoreCase = true) ||
            text.contains("USER_KEY_RECYCLED", ignoreCase = true) ||
            text.contains("USERKEY_PLAT_NOMATCH", ignoreCase = true) ||
            text.contains("INVALID_USER_SCODE", ignoreCase = true) ||
            text.contains("INVALID_USER_IP", ignoreCase = true) ||
            text.contains("INVALID_USER_DOMAIN", ignoreCase = true) ||
            text.contains("INVALID_USER_SIGNATURE", ignoreCase = true) ||
            text.contains("SERVICE_NOT_AVAILABLE_FOR_USER", ignoreCase = true) -> AmapFailure.INVALID_KEY
        text.contains("DAILY_QUERY_OVER_LIMIT", ignoreCase = true) ||
            text.contains("ACCESS_TOO_FREQUENT", ignoreCase = true) ||
            text.contains("QPS_HAS_EXCEEDED_THE_LIMIT", ignoreCase = true) ||
            text.contains("CUQPS_HAS_EXCEEDED_THE_LIMIT", ignoreCase = true) ||
            text.contains("QUOTA_PLAN_RUN_OUT", ignoreCase = true) -> AmapFailure.QUOTA
        statusCode == 429 -> AmapFailure.QUOTA
        statusCode >= 500 ||
            text.contains("SERVICE_NOT_AVAILABLE", ignoreCase = true) ||
            text.contains("UNKNOWN_ERROR", ignoreCase = true) ||
            text.contains("INTERNAL_ERROR", ignoreCase = true) ||
            text.contains("SERVER_ERROR", ignoreCase = true) -> AmapFailure.SERVICE
        text.contains("INVALID_PARAMS", ignoreCase = true) ||
            text.contains("MISSING_REQUIRED_PARAMS", ignoreCase = true) ||
            text.contains("INVALID_PARAMETER", ignoreCase = true) ||
            text.contains("INVALID_REQUEST", ignoreCase = true) ||
            text.contains("参数", ignoreCase = false) -> AmapFailure.PARAMETER
        statusCode == 400 || statusCode == 422 -> AmapFailure.PARAMETER
        statusCode == 404 || statusCode == 405 -> AmapFailure.PROTOCOL
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
    val id: String?,
    val name: String,
    val address: String?,
    val typecode: String?,
    val typeName: String?,
    val location: LatLng?,
    val photos: List<String> = emptyList(),
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
    val street: String? = null,
    val streetNumber: String? = null,
    val country: String? = null,
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
    val paths: List<AmapRoutePath> = emptyList(),
    val walkingDistanceMeters: Int? = null,
) {
    val allPaths: List<AmapRoutePath>
        get() = paths.ifEmpty {
            listOf(AmapRoutePath(distanceMeters, durationSeconds, steps, transitLines, walkingDistanceMeters))
        }
}

internal data class AmapWeatherDetails(
    val city: String?,
    val days: List<AmapWeatherDay>,
)

internal fun decodePlacesDetails(details: JsonObject?): AmapPlacesDetails {
    val root = details ?: return AmapPlacesDetails(null, null, emptyList())
    val items = root.arrayValue("items")?.mapNotNull { element ->
        val item = element.asObjectOrNull() ?: return@mapNotNull null
        val name = item.textValue("name") ?: return@mapNotNull null
        val typecode = item.textValue("typecode")
        AmapPlaceItem(
            id = item.textValue("id"),
            name = name,
            address = item.textValue("address"),
            typecode = typecode,
            typeName = readablePlaceType(item.textValue("typeName") ?: typecode),
            location = latLngFrom(item),
            photos = (item.arrayValue("photos")?.mapNotNull { photo ->
                (photo as? JsonPrimitive)?.contentOrNull
            }.orEmpty() + listOfNotNull(item.textValue("photo")))
                .mapNotNull(::httpsPhotoUrl)
                .distinct(),
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
        street = root.textValue("street"),
        streetNumber = root.textValue("streetNumber"),
        country = root.textValue("country"),
    )
}

internal fun decodeRouteDetails(details: JsonObject?): AmapRouteDetails? {
    val root = details ?: return null
    val steps = decodeRouteSteps(root.arrayValue("steps"))
    val lines = decodeTransitLines(root.arrayValue("transitLines"))
    val paths = root.arrayValue("paths")?.mapNotNull { element ->
        element.asObjectOrNull()?.let(::decodeRoutePath)
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
        paths = paths,
        walkingDistanceMeters = root.intValue("walkingDistanceMeters"),
    )
}

private fun decodeRoutePath(root: JsonObject): AmapRoutePath = AmapRoutePath(
    distanceMeters = root.intValue("distanceMeters"),
    durationSeconds = root.intValue("durationSeconds"),
    steps = decodeRouteSteps(root.arrayValue("steps")),
    transitLines = decodeTransitLines(root.arrayValue("transitLines")),
    walkingDistanceMeters = root.intValue("walkingDistanceMeters"),
)

private fun decodeRouteSteps(array: JsonArray?): List<AmapRouteStep> = array?.mapNotNull { element ->
    val step = element.asObjectOrNull() ?: return@mapNotNull null
    val instruction = step.textValue("instruction") ?: return@mapNotNull null
    AmapRouteStep(
        instruction = instruction,
        road = step.textValue("road"),
        distanceMeters = step.intValue("distanceMeters"),
        durationSeconds = step.intValue("durationSeconds"),
    )
}.orEmpty()

private fun decodeTransitLines(array: JsonArray?): List<AmapTransitLine> = array?.mapNotNull { element ->
    val line = element.asObjectOrNull() ?: return@mapNotNull null
    AmapTransitLine(
        lineName = line.textValue("lineName"),
        walkingMeters = line.intValue("walkingMeters"),
        walkingSeconds = line.intValue("walkingSeconds"),
        departureStop = line.textValue("departureStop"),
        arrivalStop = line.textValue("arrivalStop"),
        entrance = line.textValue("entrance"),
        exit = line.textValue("exit"),
        viaStops = line.arrayValue("viaStops")?.mapNotNull { stop ->
            (stop as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty(),
        viaCount = line.intValue("viaCount"),
        buslineNames = line.arrayValue("buslineNames")?.mapNotNull { name ->
            (name as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty(),
        lineDistanceMeters = line.intValue("lineDistanceMeters"),
        lineDurationSeconds = line.intValue("lineDurationSeconds"),
        startTime = line.textValue("startTime"),
        endTime = line.textValue("endTime"),
        options = line.arrayValue("options")?.mapNotNull { element ->
            element.asObjectOrNull()?.let { option ->
                AmapTransitOption(
                    name = option.textValue("name") ?: return@mapNotNull null,
                    departureStop = option.textValue("departureStop"),
                    arrivalStop = option.textValue("arrivalStop"),
                    viaStops = option.arrayValue("viaStops")?.mapNotNull { stop ->
                        (stop as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    }.orEmpty(),
                    viaCount = option.intValue("viaCount"),
                    distanceMeters = option.intValue("distanceMeters"),
                    durationSeconds = option.intValue("durationSeconds"),
                    startTime = option.textValue("startTime"),
                    endTime = option.textValue("endTime"),
                )
            }
        }.orEmpty(),
    )
}.orEmpty()

internal fun decodeWeatherDetails(details: JsonObject?): AmapWeatherDetails? {
    val root = details ?: return null
    val days = root.arrayValue("days")?.mapNotNull { element ->
        val day = element.asObjectOrNull() ?: return@mapNotNull null
        AmapWeatherDay(
            date = day.textValue("date"),
            week = day.textValue("week"),
            dayWeather = day.textValue("dayWeather"),
            nightWeather = day.textValue("nightWeather"),
            dayTemp = day.intValue("dayTemp"),
            nightTemp = day.intValue("nightTemp"),
            dayWind = day.textValue("dayWind"),
            nightWind = day.textValue("nightWind"),
            dayPower = day.textValue("dayPower"),
            nightPower = day.textValue("nightPower"),
            dayTempFloat = day.numberValue("dayTempFloat"),
            nightTempFloat = day.numberValue("nightTempFloat"),
        )
    }.orEmpty()
    return AmapWeatherDetails(root.textValue("city"), days)
}

internal fun latLngFrom(obj: JsonObject): LatLng? {
    val longitude = obj.numberValue("longitude") ?: return null
    val latitude = obj.numberValue("latitude") ?: return null
    return LatLng(longitude, latitude)
}


