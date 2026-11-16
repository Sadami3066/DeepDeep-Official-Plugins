package io.github.whywood.deepdeep.plugin.hotel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val DEEPDEEP_RAW_KEY = "deepdeep.raw"
internal const val DEEPDEEP_GUIDANCE_KEY = "deepdeep.guidance"
internal const val DEEPDEEP_CARD_KEY = "deepdeep.card"
internal const val HOTEL_MESSAGE_KEY = "message"

private val ANSI_PATTERN = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")
private val EXIT_PATTERN = Regex("""\[exit:\s*\d+]""")
private val DOTENV_TIP_LINE = Regex(
    """^\s*(?:◇|◈|⌘|◆)|injected env\s*\(|//\s*tip:\s*(?:⌘|◈|◇)|^\s*\{\s*(?:override|debug):\s*(?:true|false)\s*\}\s*$""",
)

internal fun cleanHotelRaw(raw: String?): String = raw.orEmpty()
    .replace(ANSI_PATTERN, "")
    .replace(EXIT_PATTERN, "")
    .lineSequence()
    .filterNot(DOTENV_TIP_LINE::containsMatchIn)
    .joinToString("\n")
    .trim()

internal fun hotelResultDetails(
    body: JsonObject,
    raw: String?,
    guidance: String,
    title: String,
): JsonObject = JsonObject(
    body + mapOf(
        DEEPDEEP_RAW_KEY to JsonPrimitive(cleanHotelRaw(raw)),
        DEEPDEEP_GUIDANCE_KEY to JsonPrimitive(guidance),
        DEEPDEEP_CARD_KEY to buildJsonObject { put("title", title) },
    ),
)

internal fun hotelErrorDetails(message: String, raw: String?): JsonObject = buildJsonObject {
    put(HOTEL_MESSAGE_KEY, message)
    val text = cleanHotelRaw(raw)
    if (text.isNotEmpty()) {
        put(DEEPDEEP_RAW_KEY, text)
        put(DEEPDEEP_GUIDANCE_KEY, message)
    }
}

internal fun withHotelBudget(details: HotelSearchDetails, maxPrice: Double?): HotelSearchDetails {
    if (maxPrice == null) return details
    val overBudget = details.hotels.mapNotNull { hotel ->
        val price = hotel.lowestPrice ?: return@mapNotNull null
        if (price <= maxPrice) return@mapNotNull null
        price to hotel.currency
    }
    val lowest = overBudget.minByOrNull { it.first }
    return details.copy(
        maxPrice = maxPrice,
        overBudgetCount = overBudget.size,
        lowestOverBudgetPrice = lowest?.let { (price, currency) -> hotelMoneyText(price, currency) },
    )
}

internal fun hotelIsOverBudget(hotel: HotelSummary, maxPrice: Double?): Boolean {
    val price = hotel.lowestPrice ?: return false
    return maxPrice != null && price > maxPrice
}

internal fun hotelBudgetFromQuery(query: String): Double? {
    BUDGET_PATTERNS.forEach { pattern ->
        pattern.find(query)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
    }
    return null
}

private val BUDGET_PATTERNS = listOf(
    Regex("""(\d+(?:\.\d+)?)\s*(?:元|块)\s*(?:以内|以下|之内|内|左右|上下)"""),
    Regex("""(?:预算|每晚|房价|价格)\s*(?:在|约|不超过|低于|最多)?\s*(\d+(?:\.\d+)?)\s*(?:元|块)?"""),
    Regex("""(?:不超过|低于|最多|小于)\s*(\d+(?:\.\d+)?)\s*(?:元|块)"""),
)

internal fun hotelDistinctLabels(values: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    values.forEach { value ->
        val text = value.trim()
        if (text.isNotEmpty()) seen.add(text)
    }
    return seen.toList()
}

internal fun hotelVisibleLabels(values: List<String>, expanded: Boolean, limit: Int = 3): List<String> {
    val distinct = hotelDistinctLabels(values)
    return if (expanded) distinct else distinct.take(limit)
}

internal fun hotelDisplayRatePlanName(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    if ('|' !in value) return value.trim(',', '，', ' ').takeIf { it.isNotEmpty() }
    val parts = value.split('|').map { it.trim().trim(',', '，', ' ') }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val bed = parts.first()
    val size = parts.drop(1).lastOrNull { part -> part.any(Char::isLetter) && part.any(Char::isDigit) }
    val normalized = if (size != null && size != bed) "$bed $size" else bed
    return normalized.trim().takeIf { it.isNotEmpty() }
}

internal fun hotelSearchGuidance(details: HotelSearchDetails, t: HotelTranslate): String {
    if (details.hotels.isEmpty()) {
        return t(
            "没有匹配结果：换地点或日期、放宽星级或减少筛选条件再试。",
            "No matches. Try another place or date, lower the star filter, or relax the other filters.",
        )
    }
    val notes = mutableListOf<String>()
    if (details.noMatchingRates) {
        notes.add(
            t(
                "当前筛选没有完全匹配的报价，返回的酒店仍可参考。",
                "No rates fully matched the filters; the returned hotels are still usable.",
            ),
        )
    }
    if (details.maxPrice != null) {
        notes.add(hotelBudgetGuidance(details, t))
    }
    if (details.maxPrice != null || details.filter.isNotEmpty()) {
        notes.add(
            t(
                "size 是上游返回上限；预算、标签、距离在服务端过滤，返回数量可能少于 size。",
                "size is the upstream cap; budget, tags and distance are filtered upstream, so fewer than size may return.",
            ),
        )
    }
    notes.add(
        t(
            "价格是每晚最低参考价；查看房型、床型和取消政策用 hotel_detail。",
            "Prices are the lowest nightly reference; use hotel_detail for room types, beds and cancellation policies.",
        ),
    )
    if (!details.nextPageToken.isNullOrBlank()) {
        notes.add(
            t(
                "结果还有下一页；当前搜索接口没有翻页参数，需要更多结果时增大 size 或细化地点重搜。",
                "There is another page; this search tool has no paging parameter, so raise size or narrow the place to get more.",
            ),
        )
    }
    return notes.joinToString("\n")
}

private fun hotelBudgetGuidance(details: HotelSearchDetails, t: HotelTranslate): String {
    val budget = hotelMoneyText(details.maxPrice, null).orEmpty()
    if (details.overBudgetCount > 0) {
        val lowest = details.lowestOverBudgetPrice.orEmpty()
        return if (lowest.isEmpty()) {
            t(
                "有 ${details.overBudgetCount} 家超出预算 $budget；结果没有被删除，回答时说明超预算。",
                "${details.overBudgetCount} hotels are over $budget; nothing was removed, so mention the overrun.",
            )
        } else {
            t(
                "有 ${details.overBudgetCount} 家超出预算 $budget（最低 $lowest）；结果没有被删除，回答时说明超预算。",
                "${details.overBudgetCount} hotels are over $budget (lowest $lowest); nothing was removed, so mention the overrun.",
            )
        }
    }
    val unpriced = details.hotels.count { it.lowestPrice == null }
    return if (unpriced > 0) {
        t(
            "有 $unpriced 家价格待确认，无法判断是否在预算 $budget 内。",
            "$unpriced hotels have no confirmed price, so the $budget budget cannot be checked for them.",
        )
    } else {
        t(
            "已按每晚预算 $budget 交给上游筛选，返回结果都在预算内；筛选后数量可能少于 size。",
            "The $budget nightly budget was applied upstream, so all returned hotels are within budget; fewer than size may come back.",
        )
    }
}

internal fun hotelDetailGuidance(details: HotelDetailDetails, t: HotelTranslate): String {
    val groups = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }
    if (groups.isEmpty()) {
        val reason = details.errorMessage?.takeIf { it.isNotBlank() }
        return if (reason != null) {
            t("没有可用房型：$reason。", "No rooms available: $reason.")
        } else {
            t("没有可用房型：换日期或酒店再试。", "No rooms available. Try another date or hotel.")
        }
    }
    val notes = mutableListOf<String>()
    notes.add(
        t(
            "房型按名称分组，组内按总价从低到高；预订时把所选方案的 rate_plan_id 传给 hotel_book。",
            "Room types are grouped by name and sorted by total within each group; pass the chosen plan's rate_plan_id to hotel_book.",
        ),
    )
    val nights = hotelNights(details.checkIn, details.checkOut)
    if (details.rooms.any { it.totalDerived }) {
        notes.add(
            t(
                "上游只给每晚均价，总价按 $nights 晚换算并标注折算。",
                "Upstream returns nightly averages only; totals are derived for $nights night(s) and labelled.",
            ),
        )
    }
    if (details.rooms.any { it.onRequest == true }) {
        notes.add(t("标注需酒店确认的方案要等酒店确认。", "Plans marked on request need hotel confirmation."))
    }
    return notes.joinToString("\n")
}

internal fun hotelOrdersGuidance(details: HotelOrdersDetails, t: HotelTranslate): String {
    if (details.orders.isEmpty()) {
        return t(
            "当前没有酒店订单，可以直接告诉用户没有订单。",
            "There are no hotel orders; tell the user there are none.",
        )
    }
    return t(
        "列表没有取消原因；用户问取消原因、确认号、酒店电话、入住人或支付截止时间时，用 hotel_order_detail 传订单号查询。",
        "The list has no cancellation reason; for the reason, confirmation number, hotel phone, guests or payment deadline, call hotel_order_detail with the order number.",
    )
}

internal fun hotelOrderDetailGuidance(details: HotelOrderDetail, t: HotelTranslate): String {
    val notes = mutableListOf<String>()
    notes.add(
        if (details.cancelPolicy.isNullOrBlank()) {
            t("上游没有返回取消原因或政策，不要编造。", "Upstream returned no cancellation reason or policy; do not invent one.")
        } else {
            t(
                "取消原因与取消政策看 cancelPolicy，免费取消截止看 freeCancelDeadline。",
                "Read the cancellation reason and policy from cancelPolicy and the free-cancellation deadline from freeCancelDeadline.",
            )
        },
    )
    if (!details.paymentUrl.isNullOrBlank()) {
        notes.add(t("支付链接可以直接交给用户完成支付。", "The payment link can be handed to the user to pay."))
    }
    return notes.joinToString("\n")
}

internal fun hotelBookingGuidance(details: HotelBookingDetails, t: HotelTranslate): String =
    if (details.paymentUrl.isNullOrBlank()) {
        t(
            "订单已创建但没有返回支付链接，引导用户到 RollingGo 订单页面完成支付，不要说已经付款。",
            "The order exists but no payment link came back; point the user to the RollingGo order page and never claim it is paid.",
        )
    } else {
        t(
            "订单已创建，请在 30 分钟内完成支付；把支付链接交给用户，不要说已经付款。",
            "The order exists; pay within 30 minutes. Give the user the payment link and never claim it is paid.",
        )
    }
