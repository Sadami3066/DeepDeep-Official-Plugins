package io.github.whywood.deepdeep.plugin.hotel

import java.util.Locale

internal typealias HotelTranslate = (String, String) -> String

internal fun hotelOrderStatusText(raw: String?, t: HotelTranslate): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = when (value.uppercase(Locale.ROOT)) {
        "1", "PENDING", "UNPAID", "CREATED" -> t("待支付", "awaiting payment")
        "3", "FINISHED" -> t("已完成或已关闭", "finished or closed")
        "CANCELLED", "CANCELED" -> t("已取消", "cancelled")
        else -> null
    }
    return label ?: value
}

internal fun hotelPaymentStatusText(raw: String?, t: HotelTranslate): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = when (value.uppercase(Locale.ROOT)) {
        "CREATED", "UNPAID", "PENDING" -> t("待支付", "awaiting payment")
        "PAID", "SUCCESS", "SUCCEEDED" -> t("已支付", "paid")
        "REFUNDED" -> t("已退款", "refunded")
        else -> null
    }
    return label ?: value
}

internal fun hotelPlanChoiceLabel(plan: HotelRoomPlan, t: HotelTranslate): String {
    val price = plan.priceText ?: t("价格未提供", "price not provided")
    val planName = hotelDisplayRatePlanName(plan.ratePlanName)
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
        hotelDisplayRatePlanName(plan.ratePlanName),
        plan.bed,
        plan.avgPriceText?.let { t("每晚 $it", "$it per night") },
        plan.priceText,
        plan.meal,
        policies.takeIf { it.isNotBlank() },
        dates,
    ).joinToString(" · ")
    return listOf(details.name, plan.name, facts).filter { it.isNotBlank() }.joinToString("\n")
}

internal fun hotelTagsGuidance(tags: HotelSearchTags, t: HotelTranslate): String {
    if (tags.count == 0) {
        return t(
            "没有拿到筛选标签；需要具体设施或品牌时直接按用户原话传 required_tags / preferred_brands。",
            "No search tags came back; pass required_tags / preferred_brands from the user's words.",
        )
    }
    return t(
        "这些是上游支持的酒店标签，字段只有 name/category；用户明确要某类设施、品牌或位置特征时，把对应标签名传给 hotel_search 的 required_tags，品牌传给 preferred_brands（没有 preferredTags/excludedTags 这类字段）。不要把所有标签都塞给用户。",
        "These are the upstream hotel tags; the fields are only name/category. When the user asks for a facility, brand or location trait, pass the tag name in required_tags and brands in preferred_brands (there are no preferredTags/excludedTags fields). Do not dump every tag on the user.",
    )
}

internal fun hotelCancelQuoteGuidance(quote: HotelCancelQuote, t: HotelTranslate): String {
    if (quote.alreadyCancelled) {
        return t(
            "该订单已经取消，无需再次取消；不要重试，也不要让用户联系客服。",
            "This order is already cancelled; do nothing else, don't retry and don't send the user to support.",
        )
    }
    if (!quote.canCancel) {
        return t(
            "这笔订单不能在线取消${quote.message?.let { "：$it" } ?: "，需要联系 RollingGo 客服处理"}。不要重试取消。",
            "This order cannot be cancelled online${quote.message?.let { ": $it" } ?: "; contact RollingGo support"}. Do not retry.",
        )
    }
    val fee = quote.feeText?.let { t("取消罚金 $it", "cancellation fee $it") } ?: t("取消罚金以确认页为准", "the fee is shown at confirmation")
    return t(
        "预取消只查询罚金与凭证，还没有真正取消：$fee。向用户说明罚金和取消政策，用户明确同意后再调用 hotel_cancel(order_no, confirm=true)；凭证约 10 分钟有效。",
        "The pre-check only quoted the fee and credentials; nothing is cancelled yet: $fee. Explain the fee and policy, then call hotel_cancel(order_no, confirm=true) after explicit consent. Credentials expire in about 10 minutes.",
    )
}

internal fun hotelCancelResultGuidance(result: HotelCancelResult, t: HotelTranslate): String {
    val message = result.message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    return if (result.cancelled) {
        t(
            "订单已取消$message。如实告诉用户取消结果；如果需要退款进度，去 RollingGo 订单页查看。",
            "The order was cancelled$message. Report the result; check the RollingGo order page for refund progress.",
        )
    } else {
        t(
            "取消没有成功$message。不要假设已取消，向用户说明原因并建议确认订单状态或联系客服。",
            "The cancellation did not succeed$message. Do not assume it is cancelled; explain why and suggest checking the order status or contacting support.",
        )
    }
}
