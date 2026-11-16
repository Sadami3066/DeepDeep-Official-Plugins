package io.github.whywood.deepdeep.plugin.hotel

import java.util.Locale

internal typealias HotelTranslate = (String, String) -> String

internal fun hotelSearchText(details: HotelSearchDetails, t: HotelTranslate): String {
    if (details.hotels.isEmpty()) {
        val note = details.filter.joinToString(" · ").takeIf { it.isNotBlank() }
        return buildString {
            append(t("没有找到符合条件的酒店，可以放宽星级、距离或价格再试。", "No hotels matched. Try relaxing stars, distance or price."))
            if (note != null) append("\n").append(t("本次筛选：$note", "Filters: $note"))
        }
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
        if (details.filter.isNotEmpty()) {
            append("\n").append(t("本次筛选：${details.filter.joinToString(" · ")}", "Filters: ${details.filter.joinToString(" · ")}"))
        }
        if (details.noMatchingRates) {
            append("\n").append(
                t(
                    "当前筛选下没有完全匹配的报价，下面仍列出返回的酒店。",
                    "No rates fully matched the filters; the returned hotels are still listed.",
                ),
            )
        }
        details.hotels.forEachIndexed { index, hotel ->
            append("\n").append(index + 1).append(". ").append(hotel.name)
            hotel.hotelId?.let { append(t("（hotelId $it）", " (hotelId $it)")) }
            hotel.nameEn?.takeIf { it.isNotBlank() && !hotel.name.contains(it) }?.let {
                append("\n   ").append(t("英文名：$it", "English name: $it"))
            }
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
            if (hotel.amenities.isNotEmpty()) {
                append("\n   ").append(
                    t("设施：${hotel.amenities.joinToString(" · ")}", "Facilities: ${hotel.amenities.joinToString(" · ")}"),
                )
            }
            hotel.bookingUrl?.let { append("\n   ").append(t("预订链接：$it", "Booking link: $it")) }
        }
        append("\n").append(t("价格是参考价，实际金额以 RollingGo 价格确认为准。", "Prices are indicative; RollingGo confirms the final amount."))
    }
}

internal fun hotelDetailText(details: HotelDetailDetails, t: HotelTranslate): String = buildString {
    append(details.name)
    details.nameEn?.takeIf { it.isNotBlank() && !details.name.contains(it) }?.let {
        append(" / ").append(it)
    }
    details.hotelId?.let { append(t("（hotelId $it）", " (hotelId $it)")) }
    details.starRating?.takeIf { it.isNotBlank() }?.let { append(" · ").append("$it★") }
    val stay = listOfNotNull(details.checkIn, details.checkOut)
    if (stay.size == 2) {
        append("\n").append(t("入住 ${stay[0]} → 离店 ${stay[1]}", "Check-in ${stay[0]} → check-out ${stay[1]}"))
        append(t("，${details.roomCount} 间房，每间 ${details.adultCount} 位成人", ", ${details.roomCount} room(s), ${details.adultCount} adult(s) each"))
    }
    if (details.filter.isNotEmpty()) {
        append("\n").append(t("筛选：${details.filter.joinToString(" · ")}", "Filters: ${details.filter.joinToString(" · ")}"))
    }
    if (details.noMatchingRates) {
        append("\n").append(
            t("当前筛选下没有完全匹配的房价，以下仍为返回的全部房型。", "No rates fully matched the filters; all returned rooms are still listed."),
        )
    }
    val groups = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }
    if (details.rooms.isEmpty()) {
        append("\n").append(t("暂时没有可订房型。", "No rooms are available right now."))
        details.errorMessage?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("RollingGo 返回：$it", "RollingGo said: $it")) }
        details.message?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("RollingGo 返回：$it", "RollingGo said: $it")) }
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
            val meal = plan.meal?.takeIf { it.isNotBlank() }
            val mealText = when {
                meal != null && plan.mealAmount != null -> "$meal（${plan.mealAmount} 份）"
                meal != null -> meal
                plan.mealAmount != null -> t("早餐 ${plan.mealAmount} 份", "${plan.mealAmount} breakfast(s)")
                else -> null
            }
            val totalText = plan.priceText?.let {
                if (plan.totalDerived) t("总价 $it（按 $nights 晚折算）", "total $it (derived for $nights night(s))") else t("总价 $it", "total $it")
            }
            val cancellationText = plan.cancellations.ifEmpty { listOfNotNull(plan.cancellation) }
                .joinToString("；")
                .takeIf { it.isNotBlank() }
            append("\n   ")
            append(
                listOfNotNull(
                    plan.bed?.takeIf { it.isNotBlank() },
                    plan.avgPriceText?.let { t("每晚 $it", "$it per night") },
                    totalText,
                    if (plan.priceText == null && plan.avgPriceText == null) t("价格待确认", "price pending") else null,
                    mealText,
                    cancellationText ?: t("取消政策未提供", "cancellation policy not provided"),
                ).joinToString(" · "),
            )
            if (plan.cancellations.size > 1) {
                append("\n   ").append(t("全部取消政策：", "All cancellation policies: "))
                append(plan.cancellations.joinToString("；"))
            }
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
            if (plan.images.isNotEmpty()) {
                append("\n   ").append(t("房型图片：${plan.images.joinToString(" · ")}", "Room images: ${plan.images.joinToString(" · ")}"))
            }
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
                order.orderDate?.let { t("下单时间 $it", "ordered $it") },
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
                order.currency?.takeIf { order.totalPrice == null }?.let { t("币种：$it", "Currency: $it") },
            )
            if (state.isNotEmpty()) append("\n   ").append(state.joinToString(" · "))
            listOfNotNull(
                order.meal?.takeIf { it.isNotBlank() }?.let { t("早餐：$it", "Meal: $it") },
                order.cancelPolicy?.takeIf { it.isNotBlank() }?.let { t("取消政策：$it", "Cancellation: $it") },
            ).takeIf { it.isNotEmpty() }?.let { append("\n   ").append(it.joinToString(" · ")) }
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
            order.orderDetailUrl?.takeIf { it.isNotBlank() }?.let {
                append("\n   ").append(t("订单详情链接：$it", "Order detail link: $it"))
            }
        }
        append("\n").append(
            t(
                "查看某笔订单的完整信息（确认号、酒店电话、入住人、支付截止时间等）用 hotel_order_detail，order_no 取上面的订单号。",
                "For full details of one order (confirmation no., hotel phone, guests, payment deadline), use hotel_order_detail with the order number.",
            ),
        )
    }
}

internal fun hotelOrderDetailText(details: HotelOrderDetail, t: HotelTranslate): String = buildString {
    append(t("订单详情", "Order details"))
    append("\n").append(t("订单号：${details.orderNo}", "Order no.: ${details.orderNo}"))
    details.orderStatus?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("订单状态：${hotelOrderStatusText(it, t)}", "Status: ${hotelOrderStatusText(it, t)}"))
    }
    details.orderDate?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("下单时间：$it", "Ordered: $it")) }
    details.hotelName?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("酒店：$it", "Hotel: $it")) }
    details.hotelAddress?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("地址：$it", "Address: $it")) }
    details.hotelPhone?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("酒店电话：$it", "Hotel phone: $it")) }
    details.hotelImageUrl?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("酒店图片：$it", "Hotel photo: $it")) }
    details.platformSupportPhone?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("平台客服：$it", "Platform support: $it"))
    }
    if (details.latitude != null && details.longitude != null) {
        append("\n").append(t("坐标：${details.latitude}, ${details.longitude}", "Coordinates: ${details.latitude}, ${details.longitude}"))
    }
    details.hotelConfirmationNo?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("酒店确认号：$it", "Hotel confirmation no.: $it"))
    }
    details.referenceNo?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("参考号：$it", "Reference: $it")) }
    val stay = listOfNotNull(
        details.checkIn?.let { checkIn ->
            details.checkOut?.let { checkOut -> t("入住 $checkIn → 离店 $checkOut", "Stay $checkIn → $checkOut") }
        },
        details.nights?.let { t("$it 晚", "$it night(s)") },
        details.roomCount?.let { t("$it 间", "$it room(s)") },
        details.roomName,
        details.bedType,
        details.meal,
    )
    if (stay.isNotEmpty()) append("\n").append(stay.joinToString(" · "))
    listOfNotNull(
        details.checkInTime?.takeIf { it.isNotBlank() }?.let { t("最早入住 $it", "Check-in from $it") },
        details.checkOutTime?.takeIf { it.isNotBlank() }?.let { t("最晚退房 $it", "Check-out by $it") },
    ).takeIf { it.isNotEmpty() }?.let { append("\n").append(it.joinToString(" · ")) }
    if (details.guestNames.isNotEmpty()) {
        append("\n").append(t("入住人：${details.guestNames.joinToString("、")}", "Guests: ${details.guestNames.joinToString(", ")}"))
    }
    val contact = listOfNotNull(
        details.contactName?.takeIf { it.isNotBlank() },
        details.contactPhone?.takeIf { it.isNotBlank() },
        details.contactEmail?.takeIf { it.isNotBlank() },
    )
    if (contact.isNotEmpty()) append("\n").append(t("联系人：${contact.joinToString(" · ")}", "Contact: ${contact.joinToString(" · ")}"))
    details.totalPrice?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("总价：$it", "Total: $it")) }
    details.currency?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("币种：$it", "Currency: $it")) }
    details.paymentType?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("支付方式：$it", "Payment type: $it")) }
    details.paymentDeadline?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("支付截止：$it", "Pay by: $it"))
    }
    details.paymentUrl?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("支付链接：$it", "Payment link: $it")) }
    details.cancelPolicy?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("取消政策：$it", "Cancellation policy: $it"))
    }
    details.isCancelable?.let {
        append("\n").append(
            if (it) t("当前可免费取消", "Free cancellation is available now") else t("当前不可免费取消", "Free cancellation is no longer available"),
        )
    }
    details.freeCancelDeadline?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("免费取消截止：$it", "Free cancellation until: $it"))
    }
    details.orderDetailUrl?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("订单页面：$it", "Order page: $it"))
    }
    details.message?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
}

internal fun hotelBookingText(details: HotelBookingDetails, t: HotelTranslate): String = buildString {
    append(t("订单已创建，等待支付。", "Order created and awaiting payment."))
    append("\n").append(t("订单号：${details.orderNo}", "Order no.: ${details.orderNo}"))
    details.hotelName.takeIf { it.isNotBlank() }?.let { append("\n").append(t("酒店：$it", "Hotel: $it")) }
    details.roomName?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("房型：$it", "Room: $it"))
        details.ratePlanName?.takeIf { name -> name.isNotBlank() }?.let { name -> append(" · ").append(name) }
    }
    val roomFacts = listOfNotNull(
        details.bed?.takeIf { it.isNotBlank() },
        details.meal?.takeIf { it.isNotBlank() },
        details.roomCount?.let { t("$it 间", "$it room(s)") },
        details.adultCount?.let { t("每间 $it 位成人", "$it adult(s) each") },
    )
    if (roomFacts.isNotEmpty()) append("\n").append(roomFacts.joinToString(" · "))
    if (details.checkIn != null && details.checkOut != null) {
        append("\n").append(t("入住 ${details.checkIn} → 离店 ${details.checkOut}", "Stay ${details.checkIn} → ${details.checkOut}"))
        details.nights?.let { append(t("，$it 晚", ", $it night(s)")) }
    }
    details.cancellation?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(t("取消政策：$it", "Cancellation: $it"))
    }
    details.totalPrice?.takeIf { it.isNotBlank() }?.let {
        append("\n").append(
            if (details.totalDerived) {
                t("总价：$it（按 ${details.nights ?: hotelNights(details.checkIn, details.checkOut)} 晚折算）", "Total: $it (derived for ${details.nights ?: hotelNights(details.checkIn, details.checkOut)} night(s))")
            } else {
                t("总价：$it", "Total: $it")
            },
        )
    }
    val contact = listOfNotNull(
        details.contactName?.takeIf { it.isNotBlank() },
        details.contactEmail?.takeIf { it.isNotBlank() },
    )
    if (contact.isNotEmpty()) append("\n").append(t("联系人：${contact.joinToString(" · ")}", "Contact: ${contact.joinToString(" · ")}"))
    details.paymentType?.takeIf { it.isNotBlank() }?.let { append("\n").append(t("支付方式：$it", "Payment type: $it")) }
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
        "CREATED", "UNPAID", "PENDING" -> t("待支付", "awaiting payment")
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
    val policies = plan.cancellations.ifEmpty { listOfNotNull(plan.cancellation) }.joinToString("；")
    val facts = listOfNotNull(
        plan.ratePlanName,
        plan.bed,
        plan.avgPriceText?.let { t("每晚 $it", "$it per night") },
        plan.priceText,
        plan.meal,
        policies.takeIf { it.isNotBlank() },
        dates,
    ).joinToString(" · ")
    return listOf(details.name, plan.name, facts).filter { it.isNotBlank() }.joinToString("\n")
}
