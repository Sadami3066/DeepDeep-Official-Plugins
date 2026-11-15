package io.github.whywood.deepdeep.plugin.ride

internal fun ridePlacesText(places: List<RidePlace>, translate: RideTranslate, limit: Int): String {
    if (places.isEmpty()) {
        return translate("没有找到匹配的地点，可以换个关键词或城市再试。", "No matching places found. Try another keyword or city.")
    }
    return buildString {
        append(translate("找到 ${places.size} 个地点：", "${places.size} place(s) found:"))
        places.take(limit).forEachIndexed { index, place ->
            append("\n").append(index + 1).append(". ").append(place.displayName ?: translate("未命名地点", "Unnamed place"))
            place.address?.takeIf { it.isNotBlank() && it != place.name }?.let {
                append(" · ").append(translate("地址：$it", "address: $it"))
            }
            if (!place.longitude.isNullOrBlank() && !place.latitude.isNullOrBlank()) {
                append(" · ").append(
                    translate(
                        "经度 ${place.longitude}，纬度 ${place.latitude}",
                        "longitude ${place.longitude}, latitude ${place.latitude}",
                    ),
                )
            }
        }
        append("\n").append(
            translate(
                "把经度作为 from_lng/to_lng、纬度作为 from_lat/to_lat 传给 ride_estimate。",
                "Pass longitude as from_lng/to_lng and latitude as from_lat/to_lat to ride_estimate.",
            ),
        )
    }
}

internal fun rideEstimateText(estimate: RideEstimate, translate: RideTranslate, limit: Int): String {
    if (estimate.isEmpty) {
        return translate("没有可用的车型或价格，换个地址再试。", "No ride options for this route. Try another address.")
    }
    return buildString {
        append(estimate.route.ifBlank { translate("本次行程", "This trip") })
        val routeFacts = listOfNotNull(
            estimate.distance?.let { translate("全程 $it", "distance $it") },
            estimate.duration?.let { translate("约 $it", "about $it") },
            estimate.eta?.let { translate("接驾约 $it", "pickup in $it") },
        )
        if (routeFacts.isNotEmpty()) append("\n").append(routeFacts.joinToString(" · "))
        estimate.traceId?.takeIf { it.isNotBlank() }?.let { append("\n").append("traceId：").append(it) }
        append("\n").append(translate("可用车型与参考价：", "Available ride options and estimated fares:"))
        estimate.items.take(limit).forEachIndexed { index, item ->
            append("\n").append(index + 1).append(". ").append(item.productName)
            val prices = listOfNotNull(
                item.priceText?.let { translate("参考 $it", "list $it") },
                item.priceDiscounted?.let { translate("券后 $it", "$it after coupon") },
            )
            if (prices.isNotEmpty()) append("：").append(prices.joinToString(" · "))
            val facts = listOfNotNull(
                item.productCategory?.let { translate("车型品类 $it", "category $it") },
                item.eta?.let { translate("接驾约 $it", "pickup in $it") },
                item.distance?.let { translate("距离 $it", "distance $it") },
                item.duration?.let { translate("约 $it", "about $it") },
            )
            if (facts.isNotEmpty()) append("；").append(facts.joinToString(" · "))
        }
        append("\n").append(
            translate(
                "下单时用 ride_create_order，product_name 传上面的车型名；traceId 由插件内部保存，不需要传给工具。",
                "Use ride_create_order to book and pass the vehicle name above as product_name; the plugin keeps the traceId internally.",
            ),
        )
    }
}

internal fun rideOrderText(order: RideOrder, translate: RideTranslate): String {
    if (order.isEmpty) return translate("当前没有进行中的滴滴订单。", "No active Didi order found.")
    return buildString {
        rideStatusLabel(order.statusCode ?: order.status, order.statusText, translate)?.let {
            append(translate("状态：$it", "Status: $it"))
        }
        val driverFacts = listOfNotNull(
            order.driver?.let { translate("司机 $it", "driver $it") },
            order.carModel?.let { translate("车型 $it", "car $it") },
            order.plate?.let { translate("车牌 $it", "plate $it") },
            order.phone?.let { translate("电话 $it", "phone $it") },
        )
        if (driverFacts.isNotEmpty()) append("\n").append(driverFacts.joinToString(" · "))
        val positionFacts = listOfNotNull(
            order.distanceKm?.let { translate("距上车点 $it", "$it from pickup") },
            order.eta?.let { translate("预计到达 $it", "ETA $it") },
            order.phase?.let {
                when (it) {
                    "to_pickup" -> translate("前往上车点", "heading to pickup")
                    "to_dropoff" -> translate("前往目的地", "heading to destination")
                    else -> it
                }
            },
        )
        if (positionFacts.isNotEmpty()) append("\n").append(positionFacts.joinToString(" · "))
        val orderFacts = listOfNotNull(
            order.orderId?.let { translate("订单号 $it", "order $it") },
            order.phoneSuffix?.let { translate("账号尾号 $it", "account suffix $it") },
            order.route.takeIf { it.isNotBlank() }?.let { translate("行程 $it", "trip $it") },
        )
        if (orderFacts.isNotEmpty()) append("\n").append(orderFacts.joinToString(" · "))
    }
}

internal fun rideCreatedOrderText(order: RideOrder, translate: RideTranslate): String = buildString {
    append(translate("订单已创建，正在为你匹配司机；支付在滴滴内完成。", "Order created. Matching a driver now; payment happens in Didi."))
    order.orderId?.takeIf { it.isNotBlank() }?.let { append("\n").append(translate("订单号：$it", "Order no.: $it")) }
    order.route.takeIf { it.isNotBlank() }?.let { append("\n").append(translate("行程：$it", "Trip: $it")) }
    order.phoneSuffix?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(translate("账号手机尾号：$it", "Account phone suffix: $it"))
    }
    rideStatusLabel(order.statusCode ?: order.status, order.statusText, translate)?.let {
        append("\n").append(translate("状态：$it", "Status: $it"))
    }
}

internal fun rideDriverLocationText(location: RideDriverLocation, translate: RideTranslate): String = buildString {
    location.summary?.takeIf { it.isNotBlank() }?.let {
        append(translate("司机位置：$it", "Driver location: $it"))
    }
    if (!location.longitude.isNullOrBlank() && !location.latitude.isNullOrBlank()) {
        if (isNotEmpty()) append("\n")
        append(
            translate(
                "经度 ${location.longitude}，纬度 ${location.latitude}",
                "longitude ${location.longitude}, latitude ${location.latitude}",
            ),
        )
    }
    if (isEmpty()) append(translate("暂时无法获取司机位置，稍后再试。", "Driver location is not available yet. Try again shortly."))
}
