package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginRunSignal
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideOrdersTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val zh: RideTranslate = { text, _ -> text }

    private val confirmCalls = mutableListOf<String>()
    private val cliCalls = mutableListOf<Pair<String, JsonObject>>()

    private fun toolCall(args: String, sessionId: String = "session-1"): PluginToolCall = object : PluginToolCall {
        override val callId: String = "call-1"
        override val args: JsonObject = json.parseToJsonElement(args) as JsonObject
        override val signal: PluginRunSignal? = null
        override val sessionId: String = sessionId

        override fun partial(result: PluginToolResult) = Unit
    }

    private fun stateWithEstimate(): RideToolState {
        val state = RideToolState()
        state.rememberEstimate(
            "session-1",
            RideEstimate(
                fromName = "公司",
                toName = "机场",
                items = listOf(
                    RideEstimateItem(
                        productName = "快车",
                        priceText = "14",
                        priceDiscounted = "12",
                        productCategory = "1",
                        eta = "3 分钟",
                    ),
                ),
                traceId = "trace-1",
            ),
        )
        return state
    }

    private suspend fun create(
        state: RideToolState,
        args: String,
        confirmAnswer: Boolean,
        option: String = "确认",
    ): PluginToolResult = createRideOrder(
        translate = zh,
        confirm = {
            confirmCalls += option
            confirmAnswer
        },
        callTool = { tool, callArgs ->
            cliCalls += tool to callArgs
            json.parseToJsonElement(
                """{"orderId":"order-1","statusCode":0,"from":{"name":"公司"},"to":{"name":"机场"},"phoneNumberSuffix":"1234"}""",
            )
        },
        currentKey = { null },
        warn = { _, _ -> },
        toolState = state,
        call = toolCall(args),
    )

    private suspend fun cancel(
        state: RideToolState,
        args: String,
        confirmAnswer: Boolean,
    ): PluginToolResult = cancelRideOrder(
        translate = zh,
        confirm = {
            confirmCalls += "取消"
            confirmAnswer
        },
        callTool = { tool, callArgs ->
            cliCalls += tool to callArgs
            json.parseToJsonElement("""{"success":true}""")
        },
        currentKey = { null },
        warn = { _, _ -> },
        toolState = state,
        call = toolCall(args),
    )

    @Test
    fun createOrderWithoutConfirmFlagNeverCallsCli() = runTest {
        confirmCalls.clear()
        cliCalls.clear()

        val result = create(stateWithEstimate(), """{"product_name":"快车"}""", confirmAnswer = true)

        assertTrue(result.isError)
        assertTrue(cliCalls.isEmpty())
        assertTrue(confirmCalls.isEmpty())
    }

    @Test
    fun createOrderRejectedInNativeConfirmNeverCallsCli() = runTest {
        confirmCalls.clear()
        cliCalls.clear()

        val result = create(stateWithEstimate(), """{"product_name":"快车","confirm":true}""", confirmAnswer = false)

        assertTrue(result.isError)
        assertTrue(cliCalls.isEmpty())
        assertEquals(listOf("确认"), confirmCalls)
        assertTrue(result.text.orEmpty().contains("已取消叫车"))
    }

    @Test
    fun confirmedCreateOrderCallsCliExactlyOnce() = runTest {
        confirmCalls.clear()
        cliCalls.clear()

        val result = create(stateWithEstimate(), """{"product_name":"快车","confirm":true}""", confirmAnswer = true)

        assertFalse(result.isError)
        assertEquals(1, cliCalls.size)
        assertEquals("taxi_create_order", cliCalls.single().first)
        assertEquals("1", cliCalls.single().second["product_category"]?.jsonPrimitive?.contentOrNull)
        assertEquals("trace-1", cliCalls.single().second["estimate_trace_id"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result.text.orEmpty().contains("订单已创建"))
        assertTrue(result.text.orEmpty().contains("order-1"))
    }

    @Test
    fun cancelOrderWithoutConfirmFlagNeverCallsCli() = runTest {
        confirmCalls.clear()
        cliCalls.clear()

        val result = cancel(RideToolState(), """{"order_id":"order-1"}""", confirmAnswer = true)

        assertTrue(result.isError)
        assertTrue(cliCalls.isEmpty())
        assertTrue(confirmCalls.isEmpty())
    }

    @Test
    fun cancelOrderRejectedInNativeConfirmNeverCallsCli() = runTest {
        confirmCalls.clear()
        cliCalls.clear()

        val result = cancel(
            RideToolState().also { it.rememberOrder("session-1", "order-1") },
            """{"confirm":true}""",
            confirmAnswer = false,
        )

        assertTrue(result.isError)
        assertTrue(cliCalls.isEmpty())
        assertEquals(listOf("取消"), confirmCalls)
        assertTrue(result.text.orEmpty().contains("已保留订单"))
    }

    @Test
    fun confirmedCancelOrderCallsCliWithOrderId() = runTest {
        confirmCalls.clear()
        cliCalls.clear()

        val result = cancel(
            RideToolState().also { it.rememberOrder("session-1", "order-1") },
            """{"confirm":true}""",
            confirmAnswer = true,
        )

        assertFalse(result.isError)
        assertEquals(1, cliCalls.size)
        assertEquals("taxi_cancel_order", cliCalls.single().first)
        assertEquals("order-1", cliCalls.single().second["order_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun estimateWithoutTraceIdIsRejectedBeforeCli() = runTest {
        confirmCalls.clear()
        cliCalls.clear()
        val state = RideToolState()
        state.rememberEstimate(
            "session-1",
            RideEstimate(
                fromName = "公司",
                toName = "机场",
                items = listOf(RideEstimateItem("快车", "14", null, "1")),
                traceId = null,
            ),
        )

        val result = create(state, """{"product_name":"快车","confirm":true}""", confirmAnswer = true)

        assertTrue(result.isError)
        assertTrue(cliCalls.isEmpty())
        assertTrue(confirmCalls.isEmpty())
    }
}
