package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun amapTools(host: PluginHost): List<PluginTool> = listOf(
    placeSearchTool(host),
    aroundSearchTool(host),
    regeoTool(host),
    routeTool(
        host = host,
        name = "amap_route_driving",
        label = host.t("驾车路线", "Driving route"),
        description = host.t(
            "规划驾车路线。参数：起点和终点的经纬度，可选起终点名称。",
            "Plan a driving route. Parameters: origin and destination coordinates, optional place names.",
        ),
        tool = "maps_direction_driving",
        mode = AmapRouteMode.DRIVING,
    ),
    transitTool(host),
    routeTool(
        host = host,
        name = "amap_route_walking",
        label = host.t("步行路线", "Walking route"),
        description = host.t(
            "规划步行路线。参数：起点和终点的经纬度，可选起终点名称。",
            "Plan a walking route. Parameters: origin and destination coordinates, optional place names.",
        ),
        tool = "maps_direction_walking",
        mode = AmapRouteMode.WALKING,
    ),
    routeTool(
        host = host,
        name = "amap_route_bicycling",
        label = host.t("骑行路线", "Cycling route"),
        description = host.t(
            "规划骑行路线。参数：起点和终点的经纬度，可选起终点名称。",
            "Plan a cycling route. Parameters: origin and destination coordinates, optional place names.",
        ),
        tool = "maps_bicycling",
        mode = AmapRouteMode.BICYCLING,
    ),
    weatherTool(host),
)

private fun placeSearchTool(host: PluginHost): PluginTool = pluginTool(
    name = "amap_place_search",
    label = host.t("地点搜索", "Place search"),
    description = host.t(
        "按关键词和城市搜索地点，返回名称、地址、类型和经纬度；需要规划路线时用返回的坐标调用 amap_route_*。",
        "Search places by keyword and city. Returns names, addresses, types and coordinates for amap_route_*.",
    ),
    parameters = parameters(
        required = listOf("keywords"),
        "keywords" to stringProperty(host.t("搜索关键词，例如「西湖」", "Keywords, for example “West Lake”")),
        "city" to stringProperty(host.t("城市名称，可选", "City name, optional")),
        "types" to stringProperty(host.t("地点类型，可选，例如「景点」", "Place type, optional")),
    ),
    promptGuidelines = listOf(
        host.t(
            "结果包含名称、地址、类型、坐标和距离；需要路线时用返回的坐标调用 amap_route_*，不要编造地点或坐标。",
            "Results include name, address, type, coordinates and distance. Use the coordinates with amap_route_* for routes; never invent places or coordinates.",
        ),
    ),
    execute = { call ->
        val keywords = call.args.str("keywords")
        val city = call.args.str("city").takeIf { it.isNotEmpty() }
        val types = call.args.str("types").takeIf { it.isNotEmpty() }
        if (keywords.isEmpty()) {
            textResult(host.t("请提供要搜索的关键词。", "Provide keywords to search."), isError = true)
        } else {
            perform(
                host = host,
                tool = "maps_text_search",
                arguments = buildJsonObject {
                    put("keywords", keywords)
                    if (city != null) put("city", city)
                    if (types != null) put("types", types)
                },
            ) { payload ->
                val places = parsePlaces(payload)
                val text = placeSearchObservation(host.localizer(), keywords, city, places)
                textResult(
                    text,
                    details = placesDetails(
                        kind = "places",
                        query = keywords,
                        city = city,
                        places = places,
                        map = searchMapSpec(places),
                    ),
                )
            }
        }
    },
)

private fun aroundSearchTool(host: PluginHost): PluginTool = pluginTool(
    name = "amap_around_search",
    label = host.t("周边搜索", "Nearby search"),
    description = host.t(
        "搜索某个坐标附近的地点。参数：中心点经纬度，可选关键词和半径（米，默认 1000）。",
        "Search places around a coordinate. Parameters: center coordinates, optional keywords and radius in meters (default 1000).",
    ),
    parameters = parameters(
        required = listOf("longitude", "latitude"),
        "longitude" to numberProperty(host.t("中心点经度", "Center longitude"), -180.0, 180.0),
        "latitude" to numberProperty(host.t("中心点纬度", "Center latitude"), -90.0, 90.0),
        "keywords" to stringProperty(host.t("搜索关键词，可选", "Keywords, optional")),
        "radius" to intProperty(host.t("搜索半径（米），默认 1000", "Radius in meters, default 1000"), 1, 50000),
    ),
    execute = { call ->
        val longitude = call.args.number("longitude")
        val latitude = call.args.number("latitude")
        val error = coordinateError(host, longitude, latitude)
        if (error != null) {
            textResult(error, isError = true)
        } else {
            val keywords = call.args.str("keywords").takeIf { it.isNotEmpty() }
            val radius = (call.args.int("radius") ?: 1000).coerceIn(1, 50000)
            val location = LatLng(longitude!!, latitude!!)
            perform(
                host = host,
                tool = "maps_around_search",
                arguments = buildJsonObject {
                    put("location", location.wire)
                    put("radius", radius.toString())
                    if (keywords != null) put("keywords", keywords)
                },
            ) { payload ->
                val places = parsePlaces(payload)
                val text = aroundSearchObservation(host.localizer(), location, radius, places)
                textResult(
                    text,
                    details = placesDetails(
                        kind = "around",
                        query = keywords,
                        city = null,
                        places = places,
                        map = aroundMapSpec(location, places, radius),
                    ),
                )
            }
        }
    },
)

private fun regeoTool(host: PluginHost): PluginTool = pluginTool(
    name = "amap_regeo",
    label = host.t("坐标转地址", "Address lookup"),
    description = host.t(
        "把经纬度转换为行政区划地址。参数：经度和纬度。",
        "Turn coordinates into an address. Parameters: longitude and latitude.",
    ),
    parameters = parameters(
        required = listOf("longitude", "latitude"),
        "longitude" to numberProperty(host.t("经度", "Longitude"), -180.0, 180.0),
        "latitude" to numberProperty(host.t("纬度", "Latitude"), -90.0, 90.0),
    ),
    execute = { call ->
        val longitude = call.args.number("longitude")
        val latitude = call.args.number("latitude")
        val error = coordinateError(host, longitude, latitude)
        if (error != null) {
            textResult(error, isError = true)
        } else {
            val location = LatLng(longitude!!, latitude!!)
            perform(
                host = host,
                tool = "maps_regeocode",
                arguments = buildJsonObject { put("location", location.wire) },
            ) { payload ->
                val regeo = parseRegeo(payload)
                if (regeo == null) {
                    textResult(
                        host.t("没有把坐标转换成地址，请检查经纬度后重试。", "Could not resolve the coordinates. Check them and try again."),
                        isError = true,
                    )
                } else {
                    textResult(
                        regeoObservation(host.localizer(), regeo, regeo.location ?: location),
                        details = regeoDetails(regeo, regeo.location ?: location),
                    )
                }
            }
        }
    },
)

private fun transitTool(host: PluginHost): PluginTool = pluginTool(
    name = "amap_route_transit",
    label = host.t("公交路线", "Transit route"),
    description = host.t(
        "规划公交、地铁和火车路线。参数：起终点经纬度、起点城市和终点城市。",
        "Plan transit routes by bus, metro and train. Parameters: origin and destination coordinates, origin city and destination city.",
    ),
    parameters = parameters(
        required = listOf(
            "origin_longitude",
            "origin_latitude",
            "destination_longitude",
            "destination_latitude",
            "city",
            "cityd",
        ),
        "origin_longitude" to numberProperty(host.t("起点经度", "Origin longitude"), -180.0, 180.0),
        "origin_latitude" to numberProperty(host.t("起点纬度", "Origin latitude"), -90.0, 90.0),
        "destination_longitude" to numberProperty(host.t("终点经度", "Destination longitude"), -180.0, 180.0),
        "destination_latitude" to numberProperty(host.t("终点纬度", "Destination latitude"), -90.0, 90.0),
        "city" to stringProperty(host.t("起点城市", "Origin city")),
        "cityd" to stringProperty(host.t("终点城市", "Destination city")),
        "origin_name" to stringProperty(host.t("起点名称，可选", "Origin name, optional")),
        "destination_name" to stringProperty(host.t("终点名称，可选", "Destination name, optional")),
    ),
    execute = { call ->
        routeRequest(host, call, "maps_direction_transit_integrated", AmapRouteMode.TRANSIT) { args, origin, destination, originName, destinationName ->
            buildJsonObject {
                put("origin", origin.wire)
                put("destination", destination.wire)
                put("city", args.str("city"))
                put("cityd", args.str("cityd"))
            }
        }
    },
)

private fun routeTool(
    host: PluginHost,
    name: String,
    label: String,
    description: String,
    tool: String,
    mode: AmapRouteMode,
): PluginTool = pluginTool(
    name = name,
    label = label,
    description = description,
    parameters = parameters(
        required = listOf("origin_longitude", "origin_latitude", "destination_longitude", "destination_latitude"),
        "origin_longitude" to numberProperty(host.t("起点经度", "Origin longitude"), -180.0, 180.0),
        "origin_latitude" to numberProperty(host.t("起点纬度", "Origin latitude"), -90.0, 90.0),
        "destination_longitude" to numberProperty(host.t("终点经度", "Destination longitude"), -180.0, 180.0),
        "destination_latitude" to numberProperty(host.t("终点纬度", "Destination latitude"), -90.0, 90.0),
        "origin_name" to stringProperty(host.t("起点名称，可选", "Origin name, optional")),
        "destination_name" to stringProperty(host.t("终点名称，可选", "Destination name, optional")),
    ),
    execute = { call ->
        routeRequest(host, call, tool, mode) { _, origin, destination, _, _ ->
            buildJsonObject {
                put("origin", origin.wire)
                put("destination", destination.wire)
            }
        }
    },
)

private suspend fun routeRequest(
    host: PluginHost,
    call: PluginToolCall,
    tool: String,
    mode: AmapRouteMode,
    arguments: (JsonObject, LatLng, LatLng, String?, String?) -> JsonObject,
): PluginToolResult {
    val originLongitude = call.args.number("origin_longitude")
    val originLatitude = call.args.number("origin_latitude")
    val destinationLongitude = call.args.number("destination_longitude")
    val destinationLatitude = call.args.number("destination_latitude")
    val originError = coordinateError(host, originLongitude, originLatitude)
    val destinationError = coordinateError(host, destinationLongitude, destinationLatitude)
    val error = originError ?: destinationError
    if (error != null) return textResult(error, isError = true)
    if (mode == AmapRouteMode.TRANSIT && (call.args.str("city").isEmpty() || call.args.str("cityd").isEmpty())) {
        return textResult(
            host.t("公交路线需要起点城市和终点城市。", "Transit routes need an origin city and a destination city."),
            isError = true,
        )
    }
    val origin = LatLng(originLongitude!!, originLatitude!!)
    val destination = LatLng(destinationLongitude!!, destinationLatitude!!)
    val originName = call.args.str("origin_name").takeIf { it.isNotEmpty() }
    val destinationName = call.args.str("destination_name").takeIf { it.isNotEmpty() }
    return perform(
        host = host,
        tool = tool,
        arguments = arguments(call.args, origin, destination, originName, destinationName),
    ) { payload ->
        val route = parseRoute(payload, mode)
        if (route == null) {
            textResult(
                host.t("没有规划出路线，请检查起终点坐标后重试。", "No route found. Check the coordinates and try again."),
                isError = true,
            )
        } else {
            val navigationUrl = AmapUri.navigation(origin, destination, mode, originName, destinationName)
            val resolvedOrigin = route.origin ?: origin
            val resolvedDestination = route.destination ?: destination
            textResult(
                routeObservation(
                    localizer = host.localizer(),
                    route = route,
                    origin = resolvedOrigin,
                    destination = resolvedDestination,
                    originName = originName,
                    destinationName = destinationName,
                ),
                details = routeDetails(
                    route = route,
                    originName = originName,
                    destinationName = destinationName,
                    navigationUrl = navigationUrl,
                    origin = resolvedOrigin,
                    destination = resolvedDestination,
                ),
            )
        }
    }
}

private fun weatherTool(host: PluginHost): PluginTool = pluginTool(
    name = "amap_weather",
    label = host.t("天气查询", "Weather"),
    description = host.t(
        "查询城市未来几天的天气。参数：城市名称或城市编码。",
        "Check the weather forecast for a city. Parameter: city name or city code.",
    ),
    parameters = parameters(
        required = listOf("city"),
        "city" to stringProperty(host.t("城市名称或城市编码，例如「杭州」", "City name or city code, for example “Hangzhou”")),
    ),
    promptGuidelines = listOf(
        host.t(
            "结果包含城市、日期、白天与夜间天气、温度、风向和风力；不要编造预报数据，可以补充出行建议。",
            "Results include city, dates, day and night weather, temperatures, wind direction and strength. Never invent forecast data; travel advice is fine.",
        ),
    ),
    execute = { call ->
        val city = call.args.str("city")
        if (city.isEmpty()) {
            textResult(host.t("请提供城市名称。", "Provide a city name."), isError = true)
        } else {
            perform(
                host = host,
                tool = "maps_weather",
                arguments = buildJsonObject { put("city", city) },
            ) { payload ->
                val weather = parseWeather(payload)
                if (weather == null || weather.days.isEmpty()) {
                    textResult(
                        host.tf(
                            "没有获取到「%s」的天气，请稍后重试。",
                            "Could not load weather for “%s”. Try again later.",
                            city,
                        ),
                        isError = true,
                    )
                } else {
                    textResult(
                        weatherObservation(host.localizer(), weather, city),
                        details = weatherDetails(weather, city),
                    )
                }
            }
        }
    },
)

private suspend fun perform(
    host: PluginHost,
    tool: String,
    arguments: JsonObject,
    summarize: (JsonElement) -> PluginToolResult,
): PluginToolResult = try {
    val payload = AmapClient(host).call(tool, arguments)
    summarize(payload)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    val message = when (error) {
        is AmapException -> error.message.orEmpty()
        else -> host.t("网络连接失败，请检查网络后重试", "Network unavailable. Check your connection and try again.")
    }.ifBlank { host.t("查询失败，请稍后重试", "Lookup failed. Try again later.") }
    runCatching { host.logs().warn("amap $tool failed: $message") }
    textResult(message, isError = true)
}

private fun coordinateError(host: PluginHost, longitude: Double?, latitude: Double?): String? = when {
    longitude == null || latitude == null -> host.t("请提供有效的经纬度。", "Provide valid coordinates.")
    longitude !in -180.0..180.0 || latitude !in -90.0..90.0 -> host.t("经纬度超出有效范围。", "Coordinates are out of range.")
    else -> null
}

private fun placesDetails(
    kind: String,
    query: String?,
    city: String?,
    places: List<AmapPlace>,
    map: AmapMapSpec?,
): JsonObject = buildJsonObject {
    put("kind", kind)
    put("query", query.orEmpty())
    put("city", city.orEmpty())
    if (map != null) put("map", map.toJson())
    put("items", buildJsonArray {
        places.take(8).forEach { place ->
            add(buildJsonObject {
                put("name", place.name)
                putIfPresent("address", place.address)
                putIfPresent("typeName", place.typeName)
                putIfPresent("distanceMeters", place.distanceMeters)
                place.location?.let { location ->
                    put("longitude", location.longitude)
                    put("latitude", location.latitude)
                }
            })
        }
    })
}

private fun regeoDetails(regeo: AmapRegeo, location: LatLng): JsonObject = buildJsonObject {
    put("kind", "regeo")
    put("formattedAddress", regeo.formattedAddress)
    putIfPresent("province", regeo.province)
    putIfPresent("city", regeo.city)
    putIfPresent("district", regeo.district)
    putIfPresent("township", regeo.township)
    put("longitude", location.longitude)
    put("latitude", location.latitude)
    put("map", regeoMapSpec(location).toJson())
}

private fun routeDetails(
    route: AmapRoute,
    originName: String?,
    destinationName: String?,
    navigationUrl: String,
    origin: LatLng,
    destination: LatLng,
): JsonObject = buildJsonObject {
    put("kind", "route")
    put("mode", route.mode.wire)
    putIfPresent("originName", originName)
    putIfPresent("destinationName", destinationName)
    put("origin", buildJsonObject {
        put("longitude", origin.longitude)
        put("latitude", origin.latitude)
    })
    put("destination", buildJsonObject {
        put("longitude", destination.longitude)
        put("latitude", destination.latitude)
    })
    put("map", routeMapSpec(origin, destination, routePolyline(route)).toJson())
    putIfPresent("distanceMeters", route.distanceMeters)
    putIfPresent("durationSeconds", route.durationSeconds)
    put("steps", buildJsonArray {
        route.steps.take(6).forEach { step ->
            add(buildJsonObject {
                put("instruction", step.instruction)
                putIfPresent("road", step.road)
                putIfPresent("distanceMeters", step.distanceMeters)
            })
        }
    })
    put("transitLines", buildJsonArray {
        route.transitLines.take(6).forEach { line ->
            add(buildJsonObject {
                putIfPresent("lineName", line.lineName)
                putIfPresent("walkingMeters", line.walkingMeters)
            })
        }
    })
    put("navigationUrl", navigationUrl)
}

private fun weatherDetails(weather: AmapWeather, fallbackCity: String): JsonObject = buildJsonObject {
    put("kind", "weather")
    put("city", weather.city ?: fallbackCity)
    put("days", buildJsonArray {
        weather.days.take(5).forEach { day ->
            add(buildJsonObject {
                putIfPresent("date", day.date)
                putIfPresent("dayWeather", day.dayWeather)
                putIfPresent("nightWeather", day.nightWeather)
                putIfPresent("dayTemp", day.dayTemp)
                putIfPresent("nightTemp", day.nightTemp)
                putIfPresent("dayWind", day.dayWind)
                putIfPresent("nightWind", day.nightWind)
                putIfPresent("dayPower", day.dayPower)
                putIfPresent("nightPower", day.nightPower)
            })
        }
    })
}

private fun parameters(required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        properties.forEach { (name, schema) -> put(name, schema) }
    })
    put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun numberProperty(description: String, minimum: Double? = null, maximum: Double? = null): JsonObject =
    buildJsonObject {
        put("type", "number")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

private fun intProperty(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

private fun JsonObject.str(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

private fun JsonObject.number(name: String): Double? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    if (!value.isNullOrBlank()) put(key, value)
}

private fun JsonObjectBuilder.putIfPresent(key: String, value: Int?) {
    if (value != null) put(key, value)
}
