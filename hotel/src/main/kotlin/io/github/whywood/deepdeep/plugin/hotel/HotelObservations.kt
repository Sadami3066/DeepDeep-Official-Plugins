package io.github.whywood.deepdeep.plugin.hotel

import java.util.Locale

internal typealias HotelTranslate = (String, String) -> String

internal fun hotelSearchText(details: HotelSearchDetails, t: HotelTranslate, limit: Int): String {
    if (details.hotels.isEmpty()) {
        return t("没有找到符合条件的酒店，可以放宽星级、距离或价格再试。", "No hotels matched. Try relaxing stars, distance or price.")
    }
    val scope = listOfNotNull(
        details.place.takeIf { it.isNotBlank() },
        details.checkIn?.takeIf { it.isNotBlank() }?.let {
            t("$it 入住 ${details.nights} 晚", "$it, ${details.nights} night(s)")
        },
    ).joinToString(" · ")
    return buildString {
        append(t("找到 ${details.hotels.size} 家酒店", "${details.hotels.size} hotels found"))
        if (scope.isNotBlank()) append("（").append(scope).append("）")
        details.hotels.take(limit).forEachIndexed { index, hotel ->
            append("\n").append(index + 1).append(". ").append(hotel.name)
            hotel.hotelId?.let { append(t("（hotelId $it）", " (hotelId $it)")) }
            val facts = listOfNotNull(
                hotel.starRating?.let { "$it★" },
                hotel.priceText?.let { t("参考价 $it", "from $it") },
                hotel.distanceMeters?.let(::hotelDistanceText) ?: hotel.distance,
            )
            if (facts.isNotEmpty()) append("\n   ").append(facts.joinToString(" · "))
            hotel.address?.let { append("\n   ").append(t("地址：$it", "Address: $it")) }
            if (hotel.tags.isNotEmpty()) {
                append("\n   ").append(t("标签：${hotel.tags.joinToString(" · ")}", "Tags: ${hotel.tags.joinToString(" · ")}"))
            }
            hotel.bookingUrl?.let { append("\n   ").append(t("预订链接：$it", "Booking link: $it")) }
        }
        append("\n").append(t("价格是参考价，实际金额以 RollingGo 价格确认为准。", "Prices are indicative; RollingGo confirms the final amount."))
    }
}

internal fun hotelDetailText(details: HotelDetailDetails, t: HotelTranslate): String = buildString {
    append(details.name)
    details.hotelId?.let { append(t("（hotelId $it）", " (hotelId $it)")) }
    val stay = listOfNotNull(details.checkIn, details.checkOut)
    if (stay.size == 2) {
        append("\n").append(t("入住 ${stay[0]} → 离店 ${stay[1]}", "Check-in ${stay[0]} → check-out ${stay[1]}"))
        append(t("，${details.roomCount} 间房，每间 ${details.adultCount} 位成人", ", ${details.roomCount} room(s), ${details.adultCount} adult(s) each"))
    }
    val groups = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }
    if (details.rooms.isEmpty()) {
        append("\n").append(t("暂时没有可订房型。", "No rooms are available right now."))
        details.errorMessage?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("RollingGo 返回：$it", "RollingGo said: $it")) }
        return@buildString
    }
    val nights = hotelNights(details.checkIn, details.checkOut)
    append("\n").append(
        t(
            "共 ${groups.size} 个房型、${details.rooms.size} 个价格方案，按总价从低到高（总价按 $nights 晚计算）：",
            "${groups.size} room type(s), ${details.rooms.size} rate plan(s), from the lowest total price (for $nights night(s)):",
        ),
    )
    var index = 1
    groups.forEach { group ->
        group.plans.forEach { plan ->
            append("\n").append(index).append(". ").append(plan.name)
            plan.ratePlanName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append("\n   ")
            append(
                listOfNotNull(
                    plan.bed?.takeIf { it.isNotBlank() },
                    plan.avgPriceText?.let { t("每晚 $it", "$it per night") },
                    plan.priceText?.let { t("总价 $it（$nights 晚）", "total $it for $nights night(s)") },
                    if (plan.priceText == null && plan.avgPriceText == null) t("价格待确认", "price pending") else null,
                    plan.meal?.takeIf { it.isNotBlank() },
                    plan.cancellation ?: t("取消政策未提供", "cancellation policy not provided"),
                ).joinToString(" · "),
            )
            append("\n   ")
            append(
                listOfNotNull(
                    plan.ratePlanId?.let { t("价格方案 ID：$it", "Rate plan ID: $it") },
                    plan.checkIn?.let { checkIn ->
                        plan.checkOut?.let { checkOut -> t("入住 $checkIn → 离店 $checkOut", "Stay $checkIn → $checkOut") }
                    },
                    plan.inventoryCount?.let { t("剩余 $it 间", "$it room(s) left") },
                    plan.onRequest?.takeIf { it }?.let { t("需酒店确认", "On request") },
                    plan.roomSize?.takeIf { it.isNotBlank() }?.let { t("面积 $it㎡", "size $it sqm") },
                    plan.floor?.takeIf { it.isNotBlank() }?.let { t("$it 层", "floors $it") },
                    plan.maxOccupancy?.let { t("可住 $it 人", "up to $it guests") },
                    plan.hasWindow?.let { if (it) t("有窗", "window") else t("无窗", "no window") },
                    plan.hasWifi?.takeIf { it }?.let { t("有 WiFi", "WiFi") },
                ).joinToString(" · "),
            )
            index += 1
        }
    }
    details.bookingUrl?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("官方预订链接：$it", "Official booking link: $it"))
    }
    append("\n").append(
        t(
            "预订时把所选方案的价格方案 ID 作为 rate_plan_id 传给 hotel_book。",
            "Pass the chosen plan's rate plan ID to hotel_book as rate_plan_id.",
        ),
    )
}

internal fun hotelOrdersText(details: HotelOrdersDetails, t: HotelTranslate): String {
    if (details.orders.isEmpty()) {
        val message = details.message?.takeIf { it.isNotBlank() }
        return if (message != null) {
            t("当前没有酒店订单。RollingGo 返回：$message", "No hotel orders yet. RollingGo said: $message")
        } else {
            t("当前没有酒店订单。", "No hotel orders yet.")
        }
    }
    return buildString {
        append(t("共 ${details.orders.size} 笔酒店订单", "${details.orders.size} hotel order(s)"))
        details.orders.forEachIndexed { index, order ->
            append("\n").append(index + 1).append(". ").append(order.hotelName)
            val orderNumbers = listOfNotNull(
                order.orderNo?.let { t("订单号 $it", "order no. $it") },
                order.subOrderNo?.let { t("子订单号 $it", "sub-order no. $it") },
                order.referenceNo?.let { t("参考号 $it", "reference $it") },
            )
            if (orderNumbers.isNotEmpty()) {
                append("\n   ").append(orderNumbers.joinToString(" · "))
            }
            val stay = listOfNotNull(
                order.checkIn?.let { checkIn ->
                    order.checkOut?.let { checkOut -> t("入住 $checkIn → 离店 $checkOut", "Stay $checkIn → $checkOut") }
                },
                order.nights?.let { t("$it 晚", "$it night(s)") },
                order.roomCount?.let { t("$it 间", "$it room(s)") },
                order.roomName,
            )
            if (stay.isNotEmpty()) append("\n   ").append(stay.joinToString(" · "))
            val state = listOfNotNull(
                hotelOrderStatusText(order.status, t)?.let { t("状态：$it", "Status: $it") },
                hotelPaymentStatusText(order.paymentStatus, t)?.let { t("支付状态：$it", "Payment: $it") },
                order.totalPrice?.let { t("总价：$it", "Total: $it") },
            )
            if (state.isNotEmpty()) append("\n   ").append(state.joinToString(" · "))
            order.hotelAddress?.takeIf { it.isNotBlank() }?.let {
                append("\n   ").append(t("地址：$it", "Address: $it"))
            }
            val contact = listOfNotNull(
                order.contactName?.takeIf { it.isNotBlank() },
                order.contactEmail?.takeIf { it.isNotBlank() },
            )
            if (contact.isNotEmpty()) append("\n   ").append(t("联系人：${contact.joinToString(" ")}", "Contact: ${contact.joinToString(" ")}"))
            order.paymentUrl?.takeIf { it.isNotBlank() }?.let {
                append("\n   ").append(t("支付链接：$it", "Payment link: $it"))
            }
        }
    }
}

internal fun hotelBookingText(details: HotelBookingDetails, t: HotelTranslate): String = buildString {
    append(t("订单已创建，等待支付。", "Order created and awaiting payment."))
    append("\n").append(t("订单号：${details.orderNo}", "Order no.: ${details.orderNo}"))
    details.hotelName.takeIf { it.isNotBlank() }?.let { append("\n").append(t("酒店：$it", "Hotel: $it")) }
    details.roomName?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("房型：$it", "Room: $it"))
        details.ratePlanName?.takeIf { name -> name.isNotBlank() }?.let { name -> append(" · ").append(name) }
    }
    if (details.checkIn != null && details.checkOut != null) {
        append("\n").append(t("入住 ${details.checkIn} → 离店 ${details.checkOut}", "Stay ${details.checkIn} → ${details.checkOut}"))
    }
    details.cancellation?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("取消政策：$it", "Cancellation: $it"))
    }
    details.totalPrice?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("总价：$it", "Total: $it")) }
    details.paymentUrl?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("支付链接：$it", "Payment link: $it"))
        append("\n").append(t("请在 30 分钟内完成支付，支付在 RollingGo 官方页面完成。", "Pay within 30 minutes on the official RollingGo page."))
    } ?: append("\n").append(t("请在 RollingGo 完成支付。", "Complete payment on RollingGo."))
    details.message?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
}

internal fun hotelOrderStatusText(raw: String?, t: HotelTranslate): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = when (value.uppercase(Locale.ROOT)) {
        "1", "PENDING", "UNPAID", "CREATED" -> t("待支付", "awaiting payment")
        "3", "FINISHED" -> t("已完成或已关闭", "finished or closed")
        "CANCELLED", "CANCELED" -> t("已取消", "cancelled")
        else -> null
    }
    return if (label != null) "$label（$value）" else value
}

internal fun hotelPaymentStatusText(raw: String?, t: HotelTranslate): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = when (value.uppercase(Locale.ROOT)) {
        "CREATED" -> t("待支付", "awaiting payment")
        "PAID", "SUCCESS", "SUCCEEDED" -> t("已支付", "paid")
        "REFUNDED" -> t("已退款", "refunded")
        else -> null
    }
    return if (label != null) "$label（$value）" else value
}

internal fun hotelPlanChoiceLabel(plan: HotelRoomPlan, t: HotelTranslate): String {
    val price = plan.priceText ?: t("价格未提供", "price not provided")
    val planName = plan.ratePlanName?.takeIf { it.isNotBlank() }
    return listOfNotNull(plan.name, planName, price).joinToString(" · ")
}

internal fun hotelPlanConfirmMessage(plan: HotelRoomPlan, details: HotelDetailDetails, t: HotelTranslate): String {
    val dates = if (plan.checkIn != null && plan.checkOut != null) {
        t("入住 ${plan.checkIn} → 离店 ${plan.checkOut}", "Stay ${plan.checkIn} → ${plan.checkOut}")
    } else {
        null
    }
    val facts = listOfNotNull(
        plan.ratePlanName,
        plan.bed,
        plan.avgPriceText?.let { t("每晚 $it", "$it per night") },
        plan.priceText,
        plan.meal,
        plan.cancellation,
        dates,
    ).joinToString(" · ")
    return listOf(details.name, plan.name, facts).filter { it.isNotBlank() }.joinToString("\n")
}
