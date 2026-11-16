package io.github.whywood.deepdeep.plugin.amap

internal fun placeSearchGuidance(localizer: AmapLocalizer, returned: Int): String = if (returned == 0) {
    localizer.t(
        "高德没有返回地点，换关键词或补充城市后重试。",
        "Amap returned no places. Try another keyword or add a city.",
    )
} else {
    localizer.tf(
        "高德本次返回 %d 个地点，单次返回数量有限且不支持翻页；地点结果包含名称、地址、类型和图片，没有坐标、距离、评分或电话，需要路线时请用户提供起终点坐标。",
        "Amap returned %d places this time; the upstream returns a limited number per request and does not support paging. Place results contain name, address, type, and photos, with no coordinates, distance, rating, or phone. Ask the user for start and destination coordinates when a route is needed.",
        returned,
    )
}

internal fun aroundSearchGuidance(localizer: AmapLocalizer, returned: Int): String = if (returned == 0) {
    localizer.t(
        "高德半径内没有地点，扩大半径或换关键词后重试。",
        "Amap found no places within the radius. Widen the radius or try another keyword.",
    )
} else {
    localizer.tf(
        "高德本次返回 %d 个周边地点，单次返回数量有限且不支持翻页；结果包含名称、地址、类型和图片，没有坐标和距离，关键词由高德匹配。",
        "Amap returned %d nearby places this time; the upstream returns a limited number per request and does not support paging. Results contain name, address, type, and photos, with no coordinates or distance; Amap matches the keywords.",
        returned,
    )
}

internal fun regeoGuidance(localizer: AmapLocalizer): String = localizer.t(
    "高德逆地理只到区县，没有街道、门牌和坐标；需要精确位置时请用户补充地址。",
    "Amap reverse geocoding stops at the district level with no street, number, or coordinates. Ask the user for the address when an exact location is needed.",
)

internal fun routeGuidance(localizer: AmapLocalizer, mode: AmapRouteMode, hasRoute: Boolean): String = when {
    !hasRoute -> localizer.t(
        "高德没有规划出路线，检查起终点坐标；公交还需要起点城市和终点城市。",
        "Amap found no route. Check the start and destination coordinates; transit also needs both cities.",
    )
    mode == AmapRouteMode.TRANSIT -> localizer.t(
        "高德公交方案按上游排序，只含线路、站点和步行距离，不含轨迹坐标串。",
        "Amap orders transit options itself and returns lines, stops, and walking distance without a track geometry.",
    )
    else -> localizer.t(
        "高德路线不含轨迹坐标串，驾车有时只返回 1 条备选；需要更多方案或轨迹请在高德地图里查看。",
        "Amap routes have no track geometry, and driving sometimes returns only one option. Open Amap Maps for more options or the track.",
    )
}

internal fun weatherGuidance(localizer: AmapLocalizer, hasWeather: Boolean): String = if (hasWeather) {
    localizer.t(
        "高德只返回未来几天预报，天数由上游决定；预报仅供参考。",
        "Amap returns only the next few days; the number of days is up to the upstream service. Forecasts are for reference only.",
    )
} else {
    localizer.t(
        "高德没有返回天气，稍后重试；城市名或城市编码要写清楚。",
        "Amap returned no weather. Try again later and use a clear city name or code.",
    )
}

internal fun failureGuidance(localizer: AmapLocalizer, failure: AmapFailure): String = when (failure) {
    AmapFailure.INVALID_KEY -> localizer.t(
        "到插件页重新填写并验证高德 Key；地图预览还需要开通静态地图服务。",
        "Re-enter and verify the Amap key on the plugin page. The map preview also needs the static map service enabled.",
    )
    AmapFailure.QUOTA -> localizer.t(
        "稍后再试，或到高德开放平台确认今日额度和并发限制。",
        "Try again later, or check today's quota and rate limits in the Amap console.",
    )
    AmapFailure.PARAMETER -> localizer.t(
        "按上游原文修正参数后重试；周边半径不要超过 50000 米，经纬度要在有效范围内。",
        "Fix the parameters as the upstream message says and retry. Keep the nearby radius within 50000 m and coordinates in range.",
    )
    AmapFailure.SERVICE -> localizer.t(
        "高德服务暂时不可用，稍后重试。",
        "Amap is temporarily unavailable. Try again later.",
    )
    AmapFailure.NETWORK -> localizer.t(
        "请求没有到达高德，检查网络后重试；这不是高德返回的错误。",
        "The request did not reach Amap. Check the connection and retry; this is not an error returned by Amap.",
    )
    AmapFailure.PROTOCOL -> localizer.t(
        "高德返回的协议响应无法识别，稍后重试；持续失败时到插件页重新验证 Key。",
        "Amap returned an unrecognized protocol response. Try again later; if it keeps failing, verify the key on the plugin page.",
    )
    AmapFailure.INTERNAL -> localizer.t(
        "插件处理高德返回时出错，原始返回已保留；稍后重试。",
        "The plugin failed while processing the Amap response; the upstream output is preserved. Try again later.",
    )
    AmapFailure.UNKNOWN -> localizer.t(
        "高德返回了无法识别的结果，稍后重试。",
        "Amap returned an unexpected result. Try again later.",
    )
}
