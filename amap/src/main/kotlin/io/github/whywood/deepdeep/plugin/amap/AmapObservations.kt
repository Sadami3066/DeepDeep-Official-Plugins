package io.github.whywood.deepdeep.plugin.amap

internal fun placeSearchObservation(
    localizer: AmapLocalizer,
    keywords: String,
    city: String?,
    places: List<AmapPlace>,
): String {
    if (places.isEmpty()) {
        return localizer.tf(
            "没有找到「%s」相关地点，可以换个关键词或补充城市后重试。",
            "No places found for “%s”. Try another keyword or add a city.",
            keywords,
        )
    }
    val header = when {
        !city.isNullOrBlank() -> localizer.tf(
            "找到 %d 个地点「%s」（城市：%s）：",
            "Found %d places for “%s” (city: %s):",
            places.size,
            keywords,
            city,
        )
        else -> localizer.tf(
            "找到 %d 个地点「%s」：",
            "Found %d places for “%s”:",
            places.size,
            keywords,
        )
    }
    return buildObservation(header, places.mapIndexed { index, place -> placeFact(localizer, index + 1, place) })
}

internal fun aroundSearchObservation(
    localizer: AmapLocalizer,
    center: LatLng,
    radiusMeters: Int,
    places: List<AmapPlace>,
): String {
    if (places.isEmpty()) {
        return localizer.tf(
            "半径 %d 米内没有找到地点（中心 %s），可以扩大范围或换个关键词。",
            "No places found within %d m of %s. Widen the radius or try another keyword.",
            radiusMeters,
            center.display,
        )
    }
    val header = localizer.tf(
        "半径 %d 米内找到 %d 个地点（中心 %s）：",
        "Within %d m: found %d places (center %s):",
        radiusMeters,
        places.size,
        center.display,
    )
    return buildObservation(header, places.mapIndexed { index, place -> placeFact(localizer, index + 1, place) })
}

internal fun regeoObservation(
    localizer: AmapLocalizer,
    regeo: AmapRegeo,
    location: LatLng?,
): String {
    val coordinates = location?.display ?: regeo.location?.display
    val header = if (coordinates == null) {
        localizer.t("坐标对应的地址：", "Address for the coordinates:")
    } else {
        localizer.tf("坐标 %s 对应的地址：", "The coordinates %s resolve to:", coordinates)
    }
    val areas = listOfNotNull(
        regeo.province?.takeIf { it.isNotBlank() },
        regeo.city?.takeIf { it.isNotBlank() && it != regeo.province },
        regeo.district?.takeIf { it.isNotBlank() },
        regeo.township?.takeIf { it.isNotBlank() },
    )
    val body = if (areas.isEmpty()) {
        regeo.formattedAddress
    } else {
        localizer.tf(
            "%s（%s）",
            "%s (%s)",
            regeo.formattedAddress,
            areas.joinToString(" · "),
        )
    }
    return "$header\n$body"
}

internal fun routeObservation(
    localizer: AmapLocalizer,
    route: AmapRoute,
    origin: LatLng,
    destination: LatLng,
    originName: String?,
    destinationName: String?,
): String {
    val start = routePoint(localizer, originName, origin)
    val end = routePoint(localizer, destinationName, destination)
    val summary = routeSummary(localizer, route)
    val header = if (summary.isEmpty()) {
        localizer.tf(
            "%s：%s → %s。",
            "%s: %s → %s.",
            localizer.routeTitle(route.mode),
            start,
            end,
        )
    } else {
        localizer.tf(
            "%s：%s → %s，%s。",
            "%s: %s → %s, %s.",
            localizer.routeTitle(route.mode),
            start,
            end,
            summary,
        )
    }
    val lines = if (route.mode == AmapRouteMode.TRANSIT) {
        transitSteps(localizer, route)
    } else {
        driveSteps(localizer, route)
    }
    return buildObservation(header, lines)
}

private fun routeSummary(localizer: AmapLocalizer, route: AmapRoute): String {
    val parts = buildList {
        localizer.distanceText(route.distanceMeters)?.let { add(localizer.tf("全程 %s", "total %s", it)) }
        localizer.durationText(route.durationSeconds)?.let { add(localizer.tf("约 %s", "about %s", it)) }
    }
    return parts.joinToString(localizer.t("，", ", "))
}

private fun routePoint(localizer: AmapLocalizer, name: String?, location: LatLng): String =
    if (name.isNullOrBlank()) {
        location.display
    } else {
        localizer.tf("%s（%s）", "%s (%s)", name, location.display)
    }

private fun driveSteps(localizer: AmapLocalizer, route: AmapRoute): List<String> {
    if (route.steps.isEmpty()) {
        return listOf(
            localizer.t(
                "这条路线没有返回分段步骤，可以在高德地图里查看详细走向。",
                "This route has no step-by-step directions. Open Amap Maps for the full path.",
            ),
        )
    }
    return route.steps.mapIndexed { index, step ->
        val facts = buildList {
            step.road?.takeIf { it.isNotBlank() }?.let(::add)
            localizer.distanceText(step.distanceMeters)?.let(::add)
        }
        if (facts.isEmpty()) {
            localizer.tf("%d. %s", "%d. %s", index + 1, step.instruction)
        } else {
            localizer.tf(
                "%d. %s（%s）",
                "%d. %s (%s)",
                index + 1,
                step.instruction,
                facts.joinToString(localizer.t("，", ", ")),
            )
        }
    }
}

private fun transitSteps(localizer: AmapLocalizer, route: AmapRoute): List<String> {
    if (route.transitLines.isEmpty()) {
        return listOf(
            localizer.t(
                "这条公交路线没有返回换乘分段，可以在高德地图里查看详细走向。",
                "This transit route has no segment details. Open Amap Maps for the full path.",
            ),
        )
    }
    return route.transitLines.mapIndexed { index, line ->
        val name = line.lineName?.takeIf { it.isNotBlank() }
        val walk = line.walkingMeters?.takeIf { it > 0 }
        when {
            name != null && walk != null -> localizer.tf(
                "%d. %s（步行 %d 米）",
                "%d. %s (%d m walk)",
                index + 1,
                name,
                walk,
            )
            name != null -> localizer.tf("%d. %s", "%d. %s", index + 1, name)
            walk != null -> localizer.tf("%d. 步行 %d 米", "%d. Walk %d m", index + 1, walk)
            else -> localizer.tf("%d. 步行", "%d. Walk", index + 1)
        }
    }
}

internal fun weatherObservation(
    localizer: AmapLocalizer,
    weather: AmapWeather,
    fallbackCity: String,
): String {
    val city = weather.city?.takeIf { it.isNotBlank() } ?: fallbackCity
    val header = localizer.tf(
        "%s天气（%d 天）：",
        "Weather for %s (%d days):",
        city,
        weather.days.size,
    )
    val lines = weather.days.map { day ->
        val facts = buildList {
            weatherText(localizer, day)?.let(::add)
            temperatureText(day)?.let(::add)
            windText(localizer, day)?.let(::add)
        }
        val date = shortDateLabel(day.date) ?: localizer.t("今天", "Today")
        if (facts.isEmpty()) {
            localizer.tf("%s：暂无数据", "%s: no data", date)
        } else {
            localizer.tf("%s：%s", "%s: %s", date, facts.joinToString(localizer.t("，", ", ")))
        }
    }
    return buildObservation(header, lines)
}

private fun weatherText(localizer: AmapLocalizer, day: AmapWeatherDay): String? {
    val dayWeather = day.dayWeather?.takeIf { it.isNotBlank() }
    val nightWeather = day.nightWeather?.takeIf { it.isNotBlank() && it != dayWeather }
    return when {
        dayWeather != null && nightWeather != null -> localizer.tf("%s转%s", "%s to %s", dayWeather, nightWeather)
        dayWeather != null -> dayWeather
        nightWeather != null -> nightWeather
        else -> null
    }
}

private fun temperatureText(day: AmapWeatherDay): String? {
    val low = day.nightTemp
    val high = day.dayTemp
    return when {
        low != null && high != null -> "$low°~$high°"
        high != null -> "$high°"
        low != null -> "$low°"
        else -> null
    }
}

private fun windText(localizer: AmapLocalizer, day: AmapWeatherDay): String? {
    val direction = day.dayWind?.takeIf { it.isNotBlank() }
    val power = day.dayPower?.takeIf { it.isNotBlank() }
    return when {
        direction != null && power != null -> "$direction $power"
        direction != null -> direction
        power != null -> power
        else -> null
    }
}

private fun placeFact(localizer: AmapLocalizer, index: Int, place: AmapPlace): String {
    val facts = buildList {
        readablePlaceType(place.typeName)?.let(::add)
        place.address?.takeIf { it.isNotBlank() }?.let(::add)
        place.location?.let { add(localizer.tf("坐标 %s", "coordinates %s", it.display)) }
        localizer.distanceText(place.distanceMeters)?.let { add(localizer.tf("距离 %s", "%s away", it)) }
    }
    return if (facts.isEmpty()) {
        localizer.tf("%d. %s", "%d. %s", index, place.name)
    } else {
        localizer.tf(
            "%d. %s（%s）",
            "%d. %s (%s)",
            index,
            place.name,
            facts.joinToString(localizer.t("｜", " | ")),
        )
    }
}

private fun buildObservation(header: String, lines: List<String>): String =
    buildString {
        append(header)
        lines.forEach { line ->
            append('\n')
            append(line)
        }
    }
