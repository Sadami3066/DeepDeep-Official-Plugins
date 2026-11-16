package io.github.whywood.deepdeep.plugin.amap

internal data class AmapSearchPaging(
    val totalCount: Int? = null,
    val pageSizeSupported: Boolean = false,
    val pageNumSupported: Boolean = false,
    val pageSize: Int? = null,
    val pageNum: Int? = null,
)

internal fun placeSearchObservation(
    localizer: AmapLocalizer,
    keywords: String,
    city: String?,
    places: List<AmapPlace>,
    paging: AmapSearchPaging? = null,
): String {
    if (places.isEmpty()) {
        return localizer.tf(
            "没有找到「%s」相关地点，可以换个关键词或补充城市后重试。",
            "No places found for “%s”. Try another keyword or add a city.",
            keywords,
        )
    }
    val total = paging?.totalCount?.takeIf { it > places.size }
    val header = when {
        !city.isNullOrBlank() && total != null -> localizer.tf(
            "找到 %d 个地点「%s」（城市：%s，共 %d 条）：",
            "Found %d places for “%s” (city: %s, %d in total):",
            places.size,
            keywords,
            city,
            total,
        )
        !city.isNullOrBlank() -> localizer.tf(
            "找到 %d 个地点「%s」（城市：%s）：",
            "Found %d places for “%s” (city: %s):",
            places.size,
            keywords,
            city,
        )
        total != null -> localizer.tf(
            "找到 %d 个地点「%s」（共 %d 条）：",
            "Found %d places for “%s” (%d in total):",
            places.size,
            keywords,
            total,
        )
        else -> localizer.tf(
            "找到 %d 个地点「%s」：",
            "Found %d places for “%s”:",
            places.size,
            keywords,
        )
    }
    val lines = places.mapIndexed { index, place ->
        placeFact(localizer, index + 1, place, city)
    } + listOfNotNull(pagingFooter(localizer, places.size, paging))
    return buildObservation(header, lines)
}

internal fun aroundSearchObservation(
    localizer: AmapLocalizer,
    center: LatLng,
    radiusMeters: Int,
    places: List<AmapPlace>,
    paging: AmapSearchPaging? = null,
): String {
    if (places.isEmpty()) {
        return localizer.tf(
            "半径 %d 米内没有找到地点（中心 %s），可以扩大范围或换个关键词。",
            "No places found within %d m of %s. Widen the radius or try another keyword.",
            radiusMeters,
            center.display,
        )
    }
    val total = paging?.totalCount?.takeIf { it > places.size }
    val header = if (total != null) {
        localizer.tf(
            "半径 %d 米内找到 %d 个地点（中心 %s，共 %d 条）：",
            "Within %d m: found %d places (center %s, %d in total):",
            radiusMeters,
            places.size,
            center.display,
            total,
        )
    } else {
        localizer.tf(
            "半径 %d 米内找到 %d 个地点（中心 %s）：",
            "Within %d m: found %d places (center %s):",
            radiusMeters,
            places.size,
            center.display,
        )
    }
    val lines = places.mapIndexed { index, place ->
        placeFact(localizer, index + 1, place, null)
    } + listOfNotNull(pagingFooter(localizer, places.size, paging))
    return buildObservation(header, lines)
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
    val street = listOfNotNull(
        regeo.street?.takeIf { it.isNotBlank() },
        regeo.streetNumber?.takeIf { it.isNotBlank() },
    ).joinToString("")
    val facts = buildList {
        if (areas.isNotEmpty()) add(areas.joinToString(" · "))
        if (street.isNotBlank()) add(localizer.tf("街道 %s", "street %s", street))
    }
    val body = if (facts.isEmpty()) {
        regeo.formattedAddress
    } else {
        localizer.tf("%s（%s）", "%s (%s)", regeo.formattedAddress, facts.joinToString(localizer.t("｜", " | ")))
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
    val header = localizer.tf(
        "%s：%s → %s。",
        "%s: %s → %s.",
        localizer.routeTitle(route.mode),
        start,
        end,
    )
    val best = route.best
    val lines = buildList {
        if (best == null) {
            add(localizer.t("这条路线没有返回分段。", "This route did not return any segments."))
            return@buildList
        }
        add(localizer.tf("最优：%s。", "Best: %s.", pathSummary(localizer, best)))
        val alternatives = route.paths.drop(1)
        if (alternatives.isNotEmpty()) {
            add(localizer.tf("另有 %d 条备选：", "%d more options:", alternatives.size))
            alternatives.forEachIndexed { index, path ->
                add(localizer.tf("- 备选 %d：%s", "- Option %d: %s", index + 1, pathSummary(localizer, path)))
            }
        }
        add(localizer.t("最优路线分段：", "Best route segments:"))
        addAll(
            if (route.mode == AmapRouteMode.TRANSIT) {
                transitSteps(localizer, best)
            } else {
                driveSteps(localizer, best)
            },
        )
    }
    return buildObservation(header, lines)
}

private fun pathSummary(localizer: AmapLocalizer, path: AmapRoutePath): String {
    val parts = buildList {
        localizer.distanceText(path.distanceMeters)?.let { add(localizer.tf("全程 %s", "total %s", it)) }
        localizer.durationText(path.durationSeconds)?.let { add(localizer.tf("约 %s", "about %s", it)) }
        localizer.distanceText(path.walkingDistanceMeters)?.let { add(localizer.tf("步行 %s", "walk %s", it)) }
        if (path.segmentCount > 0) add(localizer.tf("%d 段", "%d segments", path.segmentCount))
    }
    return parts.joinToString(localizer.t("，", ", ")).ifBlank {
        localizer.t("暂无距离和耗时", "no distance or duration")
    }
}

private fun routePoint(localizer: AmapLocalizer, name: String?, location: LatLng): String =
    if (name.isNullOrBlank()) {
        location.display
    } else {
        localizer.tf("%s（%s）", "%s (%s)", name, location.display)
    }

private fun driveSteps(localizer: AmapLocalizer, path: AmapRoutePath): List<String> {
    if (path.steps.isEmpty()) {
        return listOf(
            localizer.t(
                "这条路线没有返回分段步骤，可以在高德地图里查看详细走向。",
                "This route has no step-by-step directions. Open Amap Maps for the full path.",
            ),
        )
    }
    return path.steps.mapIndexed { index, step ->
        val facts = buildList {
            step.road?.takeIf { it.isNotBlank() }?.let(::add)
            localizer.distanceText(step.distanceMeters)?.let(::add)
            localizer.durationText(step.durationSeconds)?.let(::add)
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

private fun transitSteps(localizer: AmapLocalizer, path: AmapRoutePath): List<String> {
    if (path.transitLines.isEmpty()) {
        return listOf(
            localizer.t(
                "这条公交路线没有返回换乘分段，可以在高德地图里查看详细走向。",
                "This transit route has no segment details. Open Amap Maps for the full path.",
            ),
        )
    }
    return path.transitLines.mapIndexed { index, line ->
        transitSegment(localizer, index + 1, line)
    }
}

private fun transitSegment(localizer: AmapLocalizer, index: Int, line: AmapTransitLine): String {
    val facts = buildList {
        val walk = line.walkingMeters?.takeIf { it > 0 }
        val walkTime = localizer.durationText(line.walkingSeconds)
        when {
            walk != null && walkTime != null -> add(
                localizer.tf("步行 %d 米（%s）", "walk %d m (%s)", walk, walkTime),
            )
            walk != null -> add(localizer.tf("步行 %d 米", "walk %d m", walk))
            walkTime != null -> add(localizer.tf("步行 %s", "walk %s", walkTime))
        }
        if (line.options.isNotEmpty()) {
            line.options.forEach { option -> add(transitOptionText(localizer, option)) }
        } else {
            line.lineName?.takeIf { it.isNotBlank() }?.let(::add)
            val from = line.departureStop?.takeIf { it.isNotBlank() }
            val to = line.arrivalStop?.takeIf { it.isNotBlank() }
            if (from != null && to != null) {
                add(localizer.tf("%s → %s", "%s → %s", from, to))
            } else {
                from?.let { add(localizer.tf("上车 %s", "board at %s", it)) }
                to?.let { add(localizer.tf("下车 %s", "get off at %s", it)) }
            }
            addStopFacts(localizer, line.viaCount, line.viaStops)
            localizer.distanceText(line.lineDistanceMeters)?.let { add(localizer.tf("线路 %s", "line %s", it)) }
            localizer.durationText(line.lineDurationSeconds)?.let { add(localizer.tf("约 %s", "about %s", it)) }
            addFirstLastFacts(localizer, line.startTime, line.endTime)
        }
        line.entrance?.takeIf { it.isNotBlank() }?.let { add(localizer.tf("入口 %s", "entrance %s", it)) }
        line.exit?.takeIf { it.isNotBlank() }?.let { add(localizer.tf("出口 %s", "exit %s", it)) }
    }
    if (facts.isEmpty()) return localizer.tf("%d. 步行", "%d. Walk", index)
    return localizer.tf("%d. %s", "%d. %s", index, facts.joinToString(localizer.t("｜", " | ")))
}

private fun transitOptionText(localizer: AmapLocalizer, option: AmapTransitOption): String {
    val facts = buildList {
        add(option.name)
        val from = option.departureStop?.takeIf { it.isNotBlank() }
        val to = option.arrivalStop?.takeIf { it.isNotBlank() }
        if (from != null && to != null) {
            add(localizer.tf("%s → %s", "%s → %s", from, to))
        } else {
            from?.let { add(localizer.tf("上车 %s", "board at %s", it)) }
            to?.let { add(localizer.tf("下车 %s", "get off at %s", it)) }
        }
        addStopFacts(localizer, option.viaCount, option.viaStops)
        localizer.distanceText(option.distanceMeters)?.let(::add)
        localizer.durationText(option.durationSeconds)?.let { add(localizer.tf("约 %s", "about %s", it)) }
        addFirstLastFacts(localizer, option.startTime, option.endTime)
    }
    return facts.joinToString(localizer.t("，", ", "))
}

private fun MutableList<String>.addStopFacts(
    localizer: AmapLocalizer,
    viaCount: Int?,
    viaStops: List<String>,
) {
    val stopCount = viaCount ?: viaStops.size.takeIf { it > 0 }
    when {
        stopCount != null && viaStops.isNotEmpty() -> add(
            localizer.tf(
                "经停 %d 站：%s",
                "%d stops: %s",
                stopCount,
                viaStops.joinToString(localizer.t("、", ", ")),
            ),
        )
        stopCount != null -> add(localizer.tf("经停 %d 站", "%d stops", stopCount))
        viaStops.isNotEmpty() -> add(
            localizer.tf(
                "经停：%s",
                "stops: %s",
                viaStops.joinToString(localizer.t("、", ", ")),
            ),
        )
    }
}

private fun MutableList<String>.addFirstLastFacts(
    localizer: AmapLocalizer,
    startTime: String?,
    endTime: String?,
) {
    val start = startTime?.takeIf { it.isNotBlank() }
    val end = endTime?.takeIf { it.isNotBlank() }
    if (start != null && end != null) {
        add(localizer.tf("首班 %s 末班 %s", "first %s, last %s", start, end))
    } else {
        start?.let { add(localizer.tf("首班 %s", "first %s", it)) }
        end?.let { add(localizer.tf("末班 %s", "last %s", it)) }
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
    val dayText = windPart(day.dayWind, day.dayPower)
    val nightText = windPart(day.nightWind, day.nightPower)
    val dayDiffers = day.dayWind != day.nightWind || day.dayPower != day.nightPower
    return when {
        dayText != null && nightText != null && dayDiffers ->
            localizer.tf("%s（夜间 %s）", "%s (night %s)", dayText, nightText)
        dayText != null -> dayText
        nightText != null -> nightText
        else -> null
    }
}

private fun windPart(direction: String?, power: String?): String? {
    val dir = direction?.takeIf { it.isNotBlank() }
    val strength = power?.takeIf { it.isNotBlank() }
    return when {
        dir != null && strength != null -> "$dir $strength"
        dir != null -> dir
        strength != null -> strength
        else -> null
    }
}

private fun placeFact(
    localizer: AmapLocalizer,
    index: Int,
    place: AmapPlace,
    searchCity: String?,
): String {
    val facts = buildList {
        readablePlaceType(place.typeName)?.let(::add)
        place.rating?.let { add(localizer.tf("评分 %.1f", "rating %.1f", it)) }
        place.businessArea?.takeIf { it.isNotBlank() }?.let { add(localizer.tf("商圈 %s", "area %s", it)) }
        place.cityName?.takeIf { it.isNotBlank() && it != searchCity }?.let {
            add(localizer.tf("城市 %s", "city %s", it))
        }
        place.tel?.takeIf { it.isNotBlank() }?.let { add(localizer.tf("电话 %s", "tel %s", it)) }
        place.address?.takeIf { it.isNotBlank() }?.let(::add)
        place.location?.let { add(localizer.tf("坐标 %s", "coordinates %s", it.display)) }
        localizer.distanceText(place.distanceMeters)?.let { add(localizer.tf("距离 %s", "%s away", it)) }
        if (place.photos.isNotEmpty()) {
            add(
                localizer.tf(
                    "照片 %d 张：%s",
                    "%d photos: %s",
                    place.photos.size,
                    place.photos.joinToString(" "),
                ),
            )
        }
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

private fun pagingFooter(
    localizer: AmapLocalizer,
    returned: Int,
    paging: AmapSearchPaging?,
): String? {
    if (paging == null) return null
    val total = paging.totalCount
    val pageSupported = paging.pageSizeSupported || paging.pageNumSupported
    val requested = paging.pageNum != null || paging.pageSize != null
    val hasMore = total != null && total > returned
    return when {
        hasMore && paging.pageNumSupported -> localizer.tf(
            "本页 %d 条，共 %d 条；用 page_num=%d 继续。",
            "%d on this page, %d in total; continue with page_num=%d.",
            returned,
            total,
            (paging.pageNum ?: 1) + 1,
        )
        hasMore && paging.pageSizeSupported -> localizer.tf(
            "本页 %d 条，共 %d 条；可以调大 page_size 继续。",
            "%d on this page, %d in total; raise page_size to continue.",
            returned,
            total,
        )
        hasMore -> localizer.tf(
            "本页 %d 条，共 %d 条；接口不支持翻页，单次最多返回 %d 条。",
            "%d on this page, %d in total; the service has no paging, so at most %d come back at a time.",
            returned,
            total,
            returned,
        )
        requested && !pageSupported -> localizer.t(
            "接口不支持 page_size/page_num，已按默认条数返回。",
            "The service does not support page_size/page_num; the default number of rows was returned.",
        )
        !pageSupported -> localizer.tf(
            "本次返回 %d 条；接口不支持翻页，单次最多返回 %d 条。",
            "%d returned; the service has no paging, so at most %d come back at a time.",
            returned,
            returned,
        )
        paging.pageNumSupported -> localizer.tf(
            "本页 %d 条；可以用 page_num 继续。",
            "%d on this page; continue with page_num.",
            returned,
        )
        else -> localizer.tf(
            "本页 %d 条；可以调整 page_size。",
            "%d on this page; adjust page_size to change it.",
            returned,
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
