package io.github.whywood.deepdeep.plugin.ride

internal fun ridePlacesText(places: List<RidePlace>, translate: RideTranslate): String {
    if (places.isEmpty()) {
        return translate("没有找到匹配的地点，可以换个关键词或城市再试。", "No matching places found. Try another keyword or city.")
    }
    return buildString {
        append(translate("找到 ${places.size} 个地点：", "${places.size} place(s) found:"))
        places.forEachIndexed { index, place ->
            append("\n").append(index + 1).append(". ").append(place.displayName ?: translate("未命名地点", "Unnamed place"))
            place.address?.takeIf { it.isNotBlank() && it != place.name }?.let {
                append(" · ").append(translate("地址：$it", "address: $it"))
            }
            place.city?.takeIf { it.isNotBlank() }?.let {
                append(" · ").append(translate("城市：$it", "city: $it"))
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
                "以上是滴滴接口本次返回的全部地点；要更多结果，换更具体的关键词或加上城市。\n" +
                    "把经度作为 from_lng/to_lng、纬度作为 from_lat/to_lat 传给 ride_estimate。",
                "These are all places this DiDi response returned; use a more specific keyword or add the city for more.\n" +
                    "Pass longitude as from_lng/to_lng and latitude as from_lat/to_lat to ride_estimate.",
            ),
        )
    }
}

internal fun rideEstimateText(estimate: RideEstimate, translate: RideTranslate): String {
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
        estimate.phoneSuffix?.takeIf { it.isNotBlank() }?.let {
            append("\n").append(translate("账号手机尾号：$it", "account phone suffix: $it"))
        }
        append("\n").append(
            translate(
                "可用车型与参考价（${estimate.items.size} 种）：",
                "Available ride options and estimated fares (${estimate.items.size}):",
            ),
        )
        estimate.items.forEachIndexed { index, item ->
            append("\n").append(index + 1).append(". ").append(item.productName)
            val prices = listOfNotNull(
                item.priceText?.let { translate("参考 ${rideMoney(it, item.currency)}", "list ${rideMoney(it, item.currency)}") },
                item.priceDiscounted?.let { translate("券后 ${rideMoney(it, item.currency)}", "${rideMoney(it, item.currency)} after coupon") },
            )
            if (prices.isNotEmpty()) append("：").append(prices.joinToString(" · "))
            val facts = listOfNotNull(
                item.productId?.takeIf { it != item.productCategory }?.let { translate("车型 ID $it", "product id $it") },
                item.productCategory?.let { translate("车型品类 $it", "category $it") },
                item.coupon?.let { translate("优惠 $it", "coupon $it") },
                item.eta?.let { translate("接驾约 $it", "pickup in $it") },
                item.distance?.let { translate("距离 $it", "distance $it") },
                item.duration?.let { translate("约 $it", "about $it") },
            )
            if (facts.isNotEmpty()) append("；").append(facts.joinToString(" · "))
        }
        append("\n").append(
            translate(
                "以上是滴滴接口本次返回的全部 ${estimate.items.size} 种车型。",
                "These are all ${estimate.items.size} options this DiDi response returned.",
            ),
        )
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
            order.driverRating?.let { translate("评分 $it", "rating $it") },
            order.driverOrderCount?.let { translate("已服务 $it 单", "$it trips") },
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
        val timeFacts = listOfNotNull(
            order.createdAt?.let { translate("下单时间 ${rideTimeText(it)}", "ordered ${rideTimeText(it)}") },
            order.startedAt?.let { translate("行程开始 ${rideTimeText(it)}", "trip started ${rideTimeText(it)}") },
            order.finishedAt?.let { translate("行程结束 ${rideTimeText(it)}", "trip ended ${rideTimeText(it)}") },
            order.updatedAt?.let { translate("状态更新 ${rideTimeText(it)}", "status updated ${rideTimeText(it)}") },
        )
        if (timeFacts.isNotEmpty()) append("\n").append(timeFacts.joinToString(" · "))
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
    order.createdAt?.let { append("\n").append(translate("下单时间：${rideTimeText(it)}", "Ordered: ${rideTimeText(it)}")) }
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
    val facts = listOfNotNull(
        location.driverName?.let { translate("司机 $it", "driver $it") },
        location.plate?.let { translate("车牌 $it", "plate $it") },
        location.status?.let { translate("状态 $it", "status $it") },
        location.distance?.let { translate("距上车点 $it", "$it from pickup") },
        location.eta?.let { translate("预计到达 $it", "ETA $it") },
        location.updatedAt?.let { translate("更新于 ${rideTimeText(it)}", "updated ${rideTimeText(it)}") },
    )
    if (facts.isNotEmpty()) append("\n").append(facts.joinToString(" · "))
    if (isEmpty()) append(translate("暂时无法获取司机位置，稍后再试。", "Driver location is not available yet. Try again shortly."))
}
