package io.github.whywood.superpi.plugin.ride

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideGuidanceTest {
    private val zh: RideTranslate = { text, _ -> text }

    private val estimate = RideEstimate(
        fromName = "中山大学(广州校区南校园)",
        toName = "广州塔",
        items = listOf(RideEstimateItem("快车", "24", null, "1")),
        traceId = "trace-1",
        distanceKm = "5.9",
        eta = "21",
        from = RideEndpoint("中山大学(广州校区南校园)-南门", "海珠区新港西路135号", "23.09", "113.29"),
        to = RideEndpoint("广州塔地铁站出入口东侧", "海珠区阅江西路222号", "23.10", "113.32"),
    )

    @Test
    fun estimateGuidanceStatesSnappingUnitsAndAskingForVehicle() {
        val guidance = rideEstimateGuidance(estimate, zh)

        assertTrue(guidance.contains("滴滴实际吸附"))
        assertTrue(guidance.contains("公里"))
        assertTrue(guidance.contains("分钟"))
        assertTrue(guidance.contains("必须先询问"))
    }

    @Test
    fun emptyEstimateGuidanceForbidsInventingFares() {
        val guidance = rideEstimateGuidance(estimate.copy(items = emptyList()), zh)

        assertTrue(guidance.contains("没有返回可用车型"))
        assertTrue(guidance.contains("不要编造价格"))
        assertFalse(guidance.contains("必须先询问"))
    }

    @Test
    fun placeGuidanceTellsHowToPassCoordinates() {
        val guidance = ridePlaceGuidance(zh)

        assertTrue(guidance.contains("from_lng"))
        assertTrue(guidance.contains("ride_estimate"))
        assertTrue(guidance.contains("城市"))
    }

    @Test
    fun orderGuidanceDistinguishesEmptyFromActiveOrder() {
        val empty = rideOrderGuidance(true, zh)
        val active = rideOrderGuidance(false, zh)

        assertTrue(empty.contains("没有进行中的订单"))
        assertTrue(empty.contains("滴滴 App"))
        assertFalse(empty.contains("单位公里"))
        assertTrue(active.contains("单位公里"))
        assertTrue(active.contains("单位分钟"))
    }

    @Test
    fun driverLinkCreateAndCancelGuidanceStayActionable() {
        assertTrue(rideDriverGuidance(zh).contains("公里"))
        assertTrue(rideLinkGuidance(zh).contains("不会自动下单"))
        assertTrue(rideCreateGuidance(zh).contains("ride_order_status"))
        assertTrue(rideCreateGuidance(zh).contains("ride_driver_location"))
        assertTrue(rideCancelGuidance(zh).contains("司机已接单"))
    }

    @Test
    fun errorKindsSeparateRateLimitNoOrderAndParameters() {
        assertEquals(RideErrorKind.RATE_LIMIT, rideErrorKind(RATE_LIMIT_CODE, "命中限流"))
        assertEquals(RideErrorKind.RATE_LIMIT, rideErrorKind(null, "Too Many Requests"))
        assertEquals(RideErrorKind.NO_ORDER, rideErrorKind(-32011, "订单不存在"))
        assertEquals(RideErrorKind.NO_ORDER, rideErrorKind(null, "没有进行中的订单"))
        assertEquals(RideErrorKind.PARAM, rideErrorKind(-32010, null))
        assertEquals(RideErrorKind.PARAM, rideErrorKind(null, "参数错误"))
        assertEquals(RideErrorKind.AUTH, rideErrorKind(-32002, null))
        assertEquals(RideErrorKind.NETWORK, rideErrorKind(null, "网络连接失败"))
        assertEquals(RideErrorKind.UPSTREAM, rideErrorKind(-32040, "订单已经取消过了"))
    }

    @Test
    fun rateLimitGuidanceKeepsQuotaHintAndSaysItIsNotNoOrder() {
        val guidance = rideErrorGuidance(RideErrorKind.RATE_LIMIT, RATE_LIMIT_CODE, zh)

        assertTrue(guidance.contains("1 分钟"))
        assertTrue(guidance.contains("不是没有订单"))
    }

    @Test
    fun noOrderAndParamGuidanceTellTheNextStep() {
        assertTrue(rideErrorGuidance(RideErrorKind.NO_ORDER, -32011, zh).contains("历史订单在滴滴 App"))
        assertTrue(rideErrorGuidance(RideErrorKind.PARAM, -32010, zh).contains("经纬度"))
        assertTrue(rideErrorGuidance(RideErrorKind.AUTH, -32002, zh).contains("滴滴 Key"))
        assertTrue(rideErrorGuidance(RideErrorKind.UPSTREAM, -32040, zh).contains("已经取消"))
    }

    @Test
    fun failureResultKeepsRawAndGuidanceWithoutCard() {
        val raw = """{"error":{"code":-32011,"message":"订单不存在"}}"""

        val result = rideFailureResult(DidiException("订单不存在", -32011, raw = raw), null, zh)

        assertTrue(result.isError)
        assertEquals("", result.text)
        assertEquals(raw, result.details?.get(RIDE_RAW_DETAIL_KEY)?.jsonPrimitive?.content)
        assertTrue(result.details?.get(RIDE_GUIDANCE_DETAIL_KEY)?.jsonPrimitive?.content.orEmpty().contains("历史订单"))
        assertFalse(result.details?.containsKey(RIDE_CARD_DETAIL_KEY) == true)
    }

    @Test
    fun failureWithoutRawKeepsMessageAndAddsGuidance() {
        val result = rideFailureResult(DidiException("网络连接失败，请稍后重试"), null, zh)

        assertTrue(result.isError)
        assertEquals("网络连接失败，请稍后重试", result.text)
        assertTrue(result.details?.get(RIDE_GUIDANCE_DETAIL_KEY)?.jsonPrimitive?.content.orEmpty().contains("网络"))
        assertFalse(result.details?.containsKey(RIDE_RAW_DETAIL_KEY) == true)
    }
}
