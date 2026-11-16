package io.github.whywood.deepdeep.plugin.ride

internal fun ridePlaceGuidance(translate: RideTranslate): String = translate(
    "坐标分别作为 from_lng/to_lng、from_lat/to_lat 传给 ride_estimate；要更多结果换更具体的关键词或加上城市。",
    "Pass the coordinates as from_lng/to_lng and from_lat/to_lat to ride_estimate; for more results use a more specific keyword or add the city.",
)

internal fun rideEstimateGuidance(estimate: RideEstimate, translate: RideTranslate): String {
    if (estimate.isEmpty) {
        return translate(
            "这次没有返回可用车型，换个地址或稍后重试；不要编造价格。",
            "No ride options came back. Change the address or retry later; never invent fares.",
        )
    }
    return listOf(
        translate(
            "上/下车点是滴滴实际吸附后的点位；与用户给的地点不一致时，先把实际点位告诉用户再下单。",
            "Pickup and drop-off are the points DiDi actually snapped to; if they differ from what the user gave, tell the user before booking.",
        ),
        translate(
            "distanceKm 单位公里，eta 与 duration 单位分钟。",
            "distanceKm is in kilometres; eta and duration are in minutes.",
        ),
        translate(
            "用户没有指定车型时必须先询问，不要替用户选择。",
            "If the user has not chosen a vehicle, ask them; never choose for them.",
        ),
    ).joinToString("\n")
}

internal fun rideOrderGuidance(empty: Boolean, translate: RideTranslate): String = if (empty) {
    translate(
        "没有进行中的订单；历史订单在滴滴 App 查看，不要反复查询。",
        "There is no active order; historical orders are in the DiDi app. Don't keep re-querying.",
    )
} else {
    translate(
        "状态由滴滴实时返回，可能随后变化；距离单位公里，时间与 eta 单位分钟。",
        "Status comes from DiDi and may change; distance is in kilometres, times and eta in minutes.",
    )
}

internal fun rideDriverGuidance(translate: RideTranslate): String = translate(
    "位置由滴滴实时返回，仅供查看；距离单位公里，时间与 eta 单位分钟。",
    "Location comes from DiDi and is read-only; distance is in kilometres, times and eta in minutes.",
)

internal fun rideLinkGuidance(translate: RideTranslate): String = translate(
    "在滴滴内确认车型和价格后下单；插件不会自动下单或支付。",
    "Confirm the ride and fare in DiDi before booking; the plugin never books or pays for you.",
)

internal fun rideCreateGuidance(translate: RideTranslate): String = translate(
    "支付在滴滴内完成；司机接单后用 ride_order_status 查状态、ride_driver_location 查位置。",
    "Payment happens in DiDi; after a driver accepts, use ride_order_status for status and ride_driver_location for the driver.",
)

internal fun rideCancelGuidance(translate: RideTranslate): String = translate(
    "取消结果以滴滴返回为准；订单已开始或司机已接单时无法通过接口取消。",
    "The cancellation result comes from DiDi; once the trip starts or a driver accepts, it cannot be cancelled through the API.",
)
