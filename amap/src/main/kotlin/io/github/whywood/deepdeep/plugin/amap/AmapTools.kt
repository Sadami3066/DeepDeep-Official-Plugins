package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.Cards
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

private const val RAW_KEY = Cards.RAW_KEY
private const val GUIDANCE_KEY = Cards.GUIDANCE_KEY
private const val CARD_KEY = Cards.KEY

internal fun amapTools(host: PluginHost): List<PluginTool> {
    val client = AmapClient(host)
    return listOf(
        placeSearchTool(host, client),
        aroundSearchTool(host, client),
        regeoTool(host, client),
        routeTool(
            host = host,
            client = client,
            name = "amap_route_driving",
            label = host.t("驾车路线", "Driving route"),
            description = host.t(
                "规划驾车路线，返回高德给出的路线和每段距离、耗时；高德不返回轨迹串，驾车有时只有 1 条备选。起终点用经纬度。",
                "Plan a driving route with the returned route, segment distances, and durations. Amap returns no track geometry, and driving sometimes has only one option. Origin and destination use coordinates.",
            ),
            tool = "maps_direction_driving",
            mode = AmapRouteMode.DRIVING,
        ),
        transitTool(host, client),
        routeTool(
            host = host,
            client = client,
            name = "amap_route_walking",
            label = host.t("步行路线", "Walking route"),
            description = host.t(
                "规划步行路线，返回高德给出的路线和每段距离、耗时；高德不返回轨迹串。起终点用经纬度。",
                "Plan a walking route with the returned route, segment distances, and durations. Amap returns no track geometry. Origin and destination use coordinates.",
            ),
            tool = "maps_direction_walking",
            mode = AmapRouteMode.WALKING,
        ),
        routeTool(
            host = host,
            client = client,
            name = "amap_route_bicycling",
            label = host.t("骑行路线", "Cycling route"),
            description = host.t(
                "规划骑行路线，返回高德给出的路线和每段距离、耗时；高德不返回轨迹串。起终点用经纬度。",
                "Plan a cycling route with the returned route, segment distances, and durations. Amap returns no track geometry. Origin and destination use coordinates.",
            ),
            tool = "maps_bicycling",
            mode = AmapRouteMode.BICYCLING,
        ),
        weatherTool(host, client),
    )
}

private fun placeSearchTool(host: PluginHost, client: AmapClient): PluginTool = pluginTool(
    name = "amap_place_search",
    label = host.t("地点搜索", "Place search"),
    description = host.t(
        "按关键词和城市搜索地点。高德单次返回数量有限且不支持翻页；结果有名称、地址、类型和图片，没有坐标、距离、评分或电话。",
        "Search places by keyword and city. Amap returns a limited number of places per request and does not support paging; results have name, address, type, and photos, with no coordinates, distance, rating, or phone.",
    ),
    parameters = parameters(
        required = listOf("keywords"),
        "keywords" to stringProperty(host.t("搜索关键词，例如「西湖」", "Keywords, for example “West Lake”")),
        "city" to stringProperty(host.t("城市名称，可选", "City name, optional")),
        "types" to stringProperty(host.t("地点类型，可选，例如「景点」", "Place type, optional")),
    ),
    promptGuidelines = listOf(
        host.t(
            "结果有名称、地址、类型和图片；周边的中心点和路线起终点用经纬度即可。",
            "Results have name, address, type, and photos; nearby centers and route endpoints take coordinates.",
        ),
    ),
    execute = { call ->
        val keywords = call.args.str("keywords")
        val city = call.args.str("city").takeIf { it.isNotEmpty() }
        val types = call.args.str("types").takeIf { it.isNotEmpty() }
        if (keywords.isEmpty()) {
            inputError(host, host.t("请提供要搜索的关键词。", "Provide keywords to search."))
        } else {
            perform(
                host = host,
                tool = "maps_text_search",
                client = client,
                arguments = buildJsonObject {
                    put("keywords", keywords)
                    if (city != null) put("city", city)
                    if (types != null) put("types", types)
                },
            ) { payload, raw ->
                val places = parsePlaces(payload)
                textResult(
                    "",
                    details = cardDetails(
                        title = host.tf("地点：%s（%d 处）", "Places: %s (%d)", keywords, places.size),
                        raw = raw,
                        guidance = placeSearchGuidance(host.localizer(), places.size),
                        body = placesDetails(
                            kind = "places",
                            query = keywords,
                            city = city,
                            places = places,
                            map = searchMapSpec(places),
                        ),
                    ),
                )
            }
        }
    },
)

private fun aroundSearchTool(host: PluginHost, client: AmapClient): PluginTool = pluginTool(
    name = "amap_around_search",
    label = host.t("周边搜索", "Nearby search"),
    description = host.t(
        "搜索某个经纬度附近的地点。高德单次返回数量有限且不支持翻页；结果有名称、地址、类型和图片，没有坐标和距离。关键词由高德匹配，半径按参数使用。",
        "Search places around a coordinate. Amap returns a limited number of places per request and does not support paging; results have name, address, type, and photos, with no coordinates or distance. Amap matches the keywords and the radius is passed through.",
    ),
    parameters = parameters(
        required = listOf("longitude", "latitude"),
        "longitude" to numberProperty(host.t("中心点经度", "Center longitude"), -180.0, 180.0),
        "latitude" to numberProperty(host.t("中心点纬度", "Center latitude"), -90.0, 90.0),
        "keywords" to stringProperty(
            host.t(
                "搜索关键词，可选；由高德上游匹配，可能返回周边全部地点",
                "Keywords, optional; Amap matches them upstream and may return every nearby place",
            ),
        ),
        "radius" to intProperty(host.t("搜索半径（米），默认 1000，最大 50000", "Radius in meters, default 1000, max 50000"), 1, 50000),
    ),
    promptGuidelines = listOf(
        host.t(
            "中心点用经纬度；结果没有坐标和距离。",
            "The center takes coordinates; results have no coordinates or distance.",
        ),
    ),
    execute = { call ->
        val longitude = call.args.number("longitude")
        val latitude = call.args.number("latitude")
        val error = coordinateError(host, longitude, latitude)
        if (error != null) {
            inputError(host, error)
        } else {
            val keywords = call.args.str("keywords").takeIf { it.isNotEmpty() }
            val radius = (call.args.int("radius") ?: 1000).coerceIn(1, 50000)
            val location = LatLng(longitude!!, latitude!!)
            perform(
                host = host,
                tool = "maps_around_search",
                client = client,
                arguments = buildJsonObject {
                    put("location", location.wire)
                    put("radius", radius.toString())
                    if (keywords != null) put("keywords", keywords)
                },
            ) { payload, raw ->
                val places = parsePlaces(payload)
                val title = if (keywords == null) {
                    host.tf("附近 %d 处地点", "%d places nearby", places.size)
                } else {
                    host.tf("附近 %d 处「%s」", "%d places near “%s”", places.size, keywords)
                }
                textResult(
                    "",
                    details = cardDetails(
                        title = title,
                        raw = raw,
                        guidance = aroundSearchGuidance(host.localizer(), places.size),
                        body = placesDetails(
                            kind = "around",
                            query = keywords,
                            city = null,
                            places = places,
                            map = aroundMapSpec(location, places, radius),
                        ),
                    ),
                )
            }
        }
    },
)

private fun regeoTool(host: PluginHost, client: AmapClient): PluginTool = pluginTool(
    name = "amap_regeo",
    label = host.t("坐标转地址", "Address lookup"),
    description = host.t(
        "把经纬度转换为行政区划。高德 MCP 只返回到区县一级，没有街道、门牌和坐标。参数：经度和纬度。",
        "Turn coordinates into an administrative area. The Amap MCP stops at the district level, with no street, number, or coordinates. Parameters: longitude and latitude.",
    ),
    parameters = parameters(
        required = listOf("longitude", "latitude"),
        "longitude" to numberProperty(host.t("经度", "Longitude"), -180.0, 180.0),
        "latitude" to numberProperty(host.t("纬度", "Latitude"), -90.0, 90.0),
    ),
    promptGuidelines = listOf(
        host.t(
            "结果只到区县，没有街道和门牌；不要补造街道、门牌或坐标。",
            "Results stop at the district level with no street or number; never invent them.",
        ),
    ),
    execute = { call ->
        val longitude = call.args.number("longitude")
        val latitude = call.args.number("latitude")
        val error = coordinateError(host, longitude, latitude)
        if (error != null) {
            inputError(host, error)
        } else {
            val location = LatLng(longitude!!, latitude!!)
            perform(
                host = host,
                tool = "maps_regeocode",
                client = client,
                arguments = buildJsonObject { put("location", location.wire) },
            ) { payload, raw ->
                val regeo = parseRegeo(payload)
                if (regeo == null) {
                    emptyResult(raw, regeoGuidance(host.localizer()))
                } else {
                    textResult(
                        "",
                        details = cardDetails(
                            title = host.tf("位置：%s", "Location: %s", regeo.formattedAddress),
                            raw = raw,
                            guidance = regeoGuidance(host.localizer()),
                            body = regeoDetails(regeo, regeo.location ?: location),
                        ),
                    )
                }
            }
        }
    },
)

private fun transitTool(host: PluginHost, client: AmapClient): PluginTool = pluginTool(
    name = "amap_route_transit",
    label = host.t("公交路线", "Transit route"),
    description = host.t(
        "规划公交、地铁和火车路线，返回全部换乘方案、线路、上下车站和步行距离；跨城需要起点城市和终点城市；高德不返回轨迹串。起终点用经纬度。",
        "Plan transit routes by bus, metro, and train, returning every option with lines, boarding and alighting stops, and walking distance; cross-city routes need both cities; Amap returns no track geometry. Origin and destination use coordinates.",
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
        routeRequest(host, client, call, "maps_direction_transit_integrated", AmapRouteMode.TRANSIT) { args, origin, destination ->
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
    client: AmapClient,
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
        routeRequest(host, client, call, tool, mode) { _, origin, destination ->
            buildJsonObject {
                put("origin", origin.wire)
                put("destination", destination.wire)
            }
        }
    },
)

private suspend fun routeRequest(
    host: PluginHost,
    client: AmapClient,
    call: PluginToolCall,
    tool: String,
    mode: AmapRouteMode,
    arguments: (JsonObject, LatLng, LatLng) -> JsonObject,
): PluginToolResult {
    val originLongitude = call.args.number("origin_longitude")
    val originLatitude = call.args.number("origin_latitude")
    val destinationLongitude = call.args.number("destination_longitude")
    val destinationLatitude = call.args.number("destination_latitude")
    val originError = coordinateError(host, originLongitude, originLatitude)
    val destinationError = coordinateError(host, destinationLongitude, destinationLatitude)
    val error = originError ?: destinationError
    if (error != null) return inputError(host, error)
    if (mode == AmapRouteMode.TRANSIT && (call.args.str("city").isEmpty() || call.args.str("cityd").isEmpty())) {
        return inputError(
            host,
            host.t("公交路线需要起点城市和终点城市。", "Transit routes need an origin city and a destination city."),
        )
    }
    val origin = LatLng(originLongitude!!, originLatitude!!)
    val destination = LatLng(destinationLongitude!!, destinationLatitude!!)
    val originName = call.args.str("origin_name").takeIf { it.isNotEmpty() }
    val destinationName = call.args.str("destination_name").takeIf { it.isNotEmpty() }
    return perform(
        host = host,
        tool = tool,
        client = client,
        arguments = arguments(call.args, origin, destination),
    ) { payload, raw ->
        val route = parseRoute(payload, mode)
        if (route == null) {
            emptyResult(raw, routeGuidance(host.localizer(), mode, hasRoute = false))
        } else {
            val navigationUrl = AmapUri.navigation(origin, destination, mode, originName, destinationName)
            val resolvedOrigin = route.origin ?: origin
            val resolvedDestination = route.destination ?: destination
            val routeTitle = host.routeTitle(mode)
            val start = originName ?: resolvedOrigin.display
            val end = destinationName ?: resolvedDestination.display
            textResult(
                "",
                details = cardDetails(
                    title = host.tf("%s：%s → %s", "%s: %s → %s", routeTitle, start, end),
                    raw = raw,
                    guidance = routeGuidance(host.localizer(), mode, hasRoute = true),
                    body = routeDetails(
                        route = route,
                        originName = originName,
                        destinationName = destinationName,
                        navigationUrl = navigationUrl,
                        origin = resolvedOrigin,
                        destination = resolvedDestination,
                    ),
                ),
            )
        }
    }
}

private fun weatherTool(host: PluginHost, client: AmapClient): PluginTool = pluginTool(
    name = "amap_weather",
    label = host.t("天气查询", "Weather"),
    description = host.t(
        "查询城市天气预报，返回白天和夜间的天气、温度、风向和风力；预报天数由高德决定。参数为城市名称或城市编码。",
        "Check a city forecast with day and night weather, temperatures, wind direction, and strength; the number of days is up to Amap. The parameter is a city name or city code.",
    ),
    parameters = parameters(
        required = listOf("city"),
        "city" to stringProperty(host.t("城市名称或城市编码，例如「杭州」", "City name or city code, for example “Hangzhou”")),
    ),
    promptGuidelines = listOf(
        host.t(
            "结果按白天和夜间给出天气、温度、风向和风力。",
            "Results separate day and night weather, temperatures, wind direction, and strength.",
        ),
    ),
    execute = { call ->
        val city = call.args.str("city")
        if (city.isEmpty()) {
            inputError(host, host.t("请提供城市名称。", "Provide a city name."))
        } else {
            perform(
                host = host,
                tool = "maps_weather",
                client = client,
                arguments = buildJsonObject { put("city", city) },
            ) { payload, raw ->
                val weather = parseWeather(payload)
                if (weather == null || weather.days.isEmpty()) {
                    emptyResult(raw, weatherGuidance(host.localizer(), hasWeather = false))
                } else {
                    val resolvedCity = weather.city ?: city
                    textResult(
                        "",
                        details = cardDetails(
                            title = host.tf(
                                "%s天气（%d 天）",
                                "Weather for %s (%d days)",
                                resolvedCity,
                                weather.days.size,
                            ),
                            raw = raw,
                            guidance = weatherGuidance(host.localizer(), hasWeather = true),
                            body = weatherDetails(weather, city),
                        ),
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
    client: AmapClient = AmapClient(host),
    summarize: suspend (JsonElement, String) -> PluginToolResult,
): PluginToolResult = try {
    val call = client.call(tool, arguments)
    summarize(call.payload, call.raw)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: AmapException) {
    runCatching { host.logs().warn("amap $tool failed: ${error.failure}") }
    errorResult(host, error.failure, error.raw)
} catch (error: Throwable) {
    runCatching { host.logs().warn("amap $tool crashed: ${error::class.java.simpleName}") }
    val detail = "${error::class.java.simpleName}: ${error.message.orEmpty()}".trim()
    errorResult(host, AmapFailure.INTERNAL, detail.takeIf { it.isNotBlank() })
}

private fun coordinateError(host: PluginHost, longitude: Double?, latitude: Double?): String? = when {
    longitude == null || latitude == null -> host.t("请提供有效的经纬度。", "Provide valid coordinates.")
    longitude !in -180.0..180.0 || latitude !in -90.0..90.0 -> host.t("经纬度超出有效范围。", "Coordinates are out of range.")
    else -> null
}

private fun cardDetails(title: String, raw: String, guidance: String, body: JsonObject): JsonObject = buildJsonObject {
    body.forEach { (key, value) -> put(key, value) }
    if (raw.isNotBlank()) put(RAW_KEY, raw)
    put(GUIDANCE_KEY, guidance)
    put(CARD_KEY, buildJsonObject { put("title", title) })
}

private fun errorResult(host: PluginHost, failure: AmapFailure, raw: String?): PluginToolResult = textResult(
    "",
    details = buildJsonObject {
        raw?.takeIf { it.isNotBlank() }?.let { put(RAW_KEY, it) }
        put(GUIDANCE_KEY, failureGuidance(host.localizer(), failure))
    },
    isError = true,
)

private fun emptyResult(raw: String, guidance: String): PluginToolResult = textResult(
    "",
    details = buildJsonObject {
        raw.takeIf { it.isNotBlank() }?.let { put(RAW_KEY, it) }
        put(GUIDANCE_KEY, guidance)
    },
    isError = true,
)

private fun inputError(host: PluginHost, message: String): PluginToolResult = textResult(
    "",
    details = buildJsonObject { put(GUIDANCE_KEY, message) },
    isError = true,
)

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
        places.forEach { place ->
            add(buildJsonObject {
                putIfPresent("id", place.id)
                put("name", place.name)
                putIfPresent("address", place.address)
                putIfPresent("typecode", place.typecode)
                putIfPresent("typeName", place.typeName)
                place.location?.let { location ->
                    put("longitude", location.longitude)
                    put("latitude", location.latitude)
                }
                put("photo", place.photos.firstOrNull())
                put("photos", buildJsonArray { place.photos.forEach { add(JsonPrimitive(it)) } })
            })
        }
    })
}

private fun regeoDetails(regeo: AmapRegeo, location: LatLng): JsonObject = buildJsonObject {
    put("kind", "regeo")
    put("formattedAddress", regeo.formattedAddress)
    putIfPresent("country", regeo.country)
    putIfPresent("province", regeo.province)
    putIfPresent("city", regeo.city)
    putIfPresent("district", regeo.district)
    putIfPresent("township", regeo.township)
    putIfPresent("street", regeo.street)
    putIfPresent("streetNumber", regeo.streetNumber)
    put("precision", "district")
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
    put(
        "map",
        routeMapSpec(
            origin = origin,
            destination = destination,
            polyline = route.best?.let(::routePolyline).orEmpty(),
        ).toJson(),
    )
    putIfPresent("distanceMeters", route.distanceMeters)
    putIfPresent("durationSeconds", route.durationSeconds)
    putIfPresent("walkingDistanceMeters", route.best?.walkingDistanceMeters)
    put("steps", stepsJson(route.steps))
    put("transitLines", transitLinesJson(route.transitLines))
    put("paths", buildJsonArray {
        route.paths.forEach { path ->
            add(buildJsonObject {
                putIfPresent("distanceMeters", path.distanceMeters)
                putIfPresent("durationSeconds", path.durationSeconds)
                putIfPresent("walkingDistanceMeters", path.walkingDistanceMeters)
                put("steps", stepsJson(path.steps))
                put("transitLines", transitLinesJson(path.transitLines))
            })
        }
    })
    put("navigationUrl", navigationUrl)
}

private fun stepsJson(steps: List<AmapRouteStep>): JsonArray = buildJsonArray {
    steps.forEach { step ->
        add(buildJsonObject {
            put("instruction", step.instruction)
            putIfPresent("road", step.road)
            putIfPresent("distanceMeters", step.distanceMeters)
            putIfPresent("durationSeconds", step.durationSeconds)
        })
    }
}

private fun transitLinesJson(lines: List<AmapTransitLine>): JsonArray = buildJsonArray {
    lines.forEach { line ->
        add(buildJsonObject {
            putIfPresent("lineName", line.lineName)
            putIfPresent("walkingMeters", line.walkingMeters)
            putIfPresent("walkingSeconds", line.walkingSeconds)
            putIfPresent("departureStop", line.departureStop)
            putIfPresent("arrivalStop", line.arrivalStop)
            putIfPresent("entrance", line.entrance)
            putIfPresent("exit", line.exit)
            putIfPresent("viaCount", line.viaCount)
            putIfPresent("lineDistanceMeters", line.lineDistanceMeters)
            putIfPresent("lineDurationSeconds", line.lineDurationSeconds)
            putIfPresent("startTime", line.startTime)
            putIfPresent("endTime", line.endTime)
            if (line.viaStops.isNotEmpty()) {
                put("viaStops", buildJsonArray { line.viaStops.forEach { add(JsonPrimitive(it)) } })
            }
            if (line.buslineNames.isNotEmpty()) {
                put("buslineNames", buildJsonArray { line.buslineNames.forEach { add(JsonPrimitive(it)) } })
            }
            if (line.options.isNotEmpty()) {
                put("options", buildJsonArray {
                    line.options.forEach { option ->
                        add(buildJsonObject {
                            put("name", option.name)
                            putIfPresent("departureStop", option.departureStop)
                            putIfPresent("arrivalStop", option.arrivalStop)
                            putIfPresent("viaCount", option.viaCount)
                            putIfPresent("distanceMeters", option.distanceMeters)
                            putIfPresent("durationSeconds", option.durationSeconds)
                            putIfPresent("startTime", option.startTime)
                            putIfPresent("endTime", option.endTime)
                            if (option.viaStops.isNotEmpty()) {
                                put("viaStops", buildJsonArray { option.viaStops.forEach { add(JsonPrimitive(it)) } })
                            }
                        })
                    }
                })
            }
        })
    }
}

private fun weatherDetails(weather: AmapWeather, fallbackCity: String): JsonObject = buildJsonObject {
    put("kind", "weather")
    put("city", weather.city ?: fallbackCity)
    put("days", buildJsonArray {
        weather.days.forEach { day ->
            add(buildJsonObject {
                putIfPresent("date", day.date)
                putIfPresent("week", day.week)
                putIfPresent("dayWeather", day.dayWeather)
                putIfPresent("nightWeather", day.nightWeather)
                putIfPresent("dayTemp", day.dayTemp)
                putIfPresent("nightTemp", day.nightTemp)
                putIfPresent("dayWind", day.dayWind)
                putIfPresent("nightWind", day.nightWind)
                putIfPresent("dayPower", day.dayPower)
                putIfPresent("nightPower", day.nightPower)
                putIfPresent("dayTempFloat", day.dayTempFloat)
                putIfPresent("nightTempFloat", day.nightTempFloat)
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

private fun JsonObjectBuilder.putIfPresent(key: String, value: Double?) {
    if (value != null) put(key, value)
}
