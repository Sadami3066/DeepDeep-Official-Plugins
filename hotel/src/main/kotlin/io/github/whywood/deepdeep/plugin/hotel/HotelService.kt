package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.PluginHost

internal class HotelToolException(message: String, val raw: String? = null) : Exception(message)

internal data class HotelOutput<T>(val details: T, val raw: String)

internal class HotelService(private val host: PluginHost) {
    suspend fun search(
        query: String,
        place: String,
        placeType: String,
        checkIn: String?,
        nights: Int,
        adults: Int,
        size: Int,
        starRatings: String?,
    ): HotelOutput<HotelSearchDetails> {
        ensureReady()
        val output = exec(
            HotelCli.searchCommand(
                query = query,
                place = place,
                placeType = placeType,
                checkIn = checkIn,
                nights = nights,
                adults = adults,
                size = size,
                starRatings = starRatings,
            ),
        )
        val parsed = parseHotelSearch(output, query, place, placeType, checkIn, nights)
            ?: throw HotelToolException(
                host.t("酒店搜索没有返回可用结果，请稍后再试。", "The hotel search returned no usable results. Try again."),
                output,
            )
        return HotelOutput(parsed, output)
    }

    suspend fun detail(
        hotelId: Long?,
        name: String?,
        checkIn: String?,
        checkOut: String?,
        rooms: Int,
        adults: Int,
    ): HotelOutput<HotelDetailDetails> {
        ensureReady()
        if (hotelId == null && name.isNullOrBlank()) {
            throw HotelToolException(host.t("请告诉我要查看哪家酒店。", "Tell me which hotel to look up."))
        }
        val output = exec(
            HotelCli.detailCommand(
                hotelId = hotelId,
                name = name,
                checkIn = checkIn,
                checkOut = checkOut,
                rooms = rooms,
                adults = adults,
            ),
        )
        val parsed = parseHotelDetail(output, name.orEmpty())
            ?: throw HotelToolException(
                host.t("没有查到这家酒店的房型，请稍后再试。", "No room types came back for this hotel. Try again."),
                output,
            )
        if (parsed.rooms.isEmpty() && !parsed.errorMessage.isNullOrBlank()) {
            throw HotelToolException(
                host.t("房型查询失败：${parsed.errorMessage}", "Room lookup failed: ${parsed.errorMessage}"),
                output,
            )
        }
        return HotelOutput(parsed, output)
    }

    suspend fun book(
        hotelId: Long,
        ratePlanId: String,
        checkIn: String,
        checkOut: String,
        rooms: Int,
        adults: Int,
        firstName: String,
        lastName: String,
        email: String,
    ): HotelOutput<HotelBookingDetails> {
        ensureReady()
        val priceOutput = exec(
            HotelCli.priceConfirmCommand(
                hotelId = hotelId,
                ratePlanId = ratePlanId,
                rooms = rooms,
                checkIn = checkIn,
                checkOut = checkOut,
                adults = adults,
            ),
        )
        val referenceNo = parseHotelPriceConfirmation(priceOutput)
            ?: throw HotelToolException(
                parseHotelOrderMessage(priceOutput)?.let {
                    host.t("锁价失败：$it", "Price confirmation failed: $it")
                } ?: host.t("价格确认没有返回预订参考号，请稍后再试。", "Price confirmation didn't return a reference. Try again."),
                priceOutput,
            )
        val bookOutput = exec(
            HotelCli.bookCommand(
                referenceNo = referenceNo.referenceNo,
                firstName = firstName,
                lastName = lastName,
                email = email,
            ),
        )
        val parsed = parseHotelBooking(bookOutput)
            ?: throw HotelToolException(
                parseHotelOrderMessage(bookOutput)?.let {
                    host.t("下单失败：$it", "Booking failed: $it")
                } ?: host.t("订单没有创建成功，请稍后再试。", "The order wasn't created. Try again."),
                combineRaw(priceOutput, bookOutput),
            )
        return HotelOutput(parsed, combineRaw(priceOutput, bookOutput))
    }

    suspend fun orders(status: String?): HotelOutput<HotelOrdersDetails> {
        ensureReady()
        val output = exec(HotelCli.ordersCommand(status))
        val parsed = parseHotelOrders(output)
            ?: run {
                val detail = parseHotelOrderMessage(output)?.takeIf { it.isNotBlank() }
                val reason = detail ?: host.t("RollingGo 返回的数据无法识别", "RollingGo returned unreadable data")
                throw HotelToolException(host.t("订单查询失败：$reason", "Order lookup failed: $reason"), output)
            }
        return HotelOutput(parsed, output)
    }

    suspend fun orderDetail(orderNo: String): HotelOutput<HotelOrderDetail> {
        ensureReady()
        if (orderNo.isBlank()) {
            throw HotelToolException(host.t("请告诉我要查看哪个订单号。", "Tell me which order number to look up."))
        }
        val output = exec(HotelCli.orderDetailCommand(orderNo))
        val parsed = parseHotelOrderDetail(output, orderNo)
            ?: run {
                val detail = parseHotelOrderMessage(output)?.takeIf { it.isNotBlank() }
                val reason = detail ?: host.t("RollingGo 返回的数据无法识别", "RollingGo returned unreadable data")
                throw HotelToolException(host.t("订单详情查询失败：$reason", "Order detail lookup failed: $reason"), output)
            }
        return HotelOutput(parsed, output)
    }

    private suspend fun ensureReady() {
        val linux = linuxOrNull() ?: throw HotelToolException(host.text(ERROR_LINUX))
        val version = runCatching { linux.exec(HotelCli.versionCommand(), timeoutMs = 20_000) }.getOrNull()
        if (version == null || HotelCli.parseVersion(version.stdout) == null) {
            throw HotelToolException(host.t("酒店服务还没有安装，先去插件页安装。", "Hotel service isn't installed. Install it from the plugin page."))
        }
        val status = runCatching { linux.exec(HotelCli.statusCommand(), timeoutMs = 20_000) }.getOrNull()
        if (status == null || !HotelCli.parseStatus(status.stdout).loggedIn) {
            throw HotelToolException(host.t("需要先在插件页登录 RollingGo。", "Sign in to RollingGo on the plugin page first."))
        }
    }

    private suspend fun exec(command: String): String {
        val linux = linuxOrNull() ?: throw HotelToolException(host.text(ERROR_LINUX))
        val result = runCatching { linux.exec(command, timeoutMs = 60_000) }
            .getOrElse { throw HotelToolException(host.t("酒店服务没有响应，请稍后再试。", "The hotel service didn't respond. Try again.")) }
        if (result.exitCode != 0) {
            host.logs().warn("hotel command failed: ${result.stdout.takeLast(800)}")
            throw HotelToolException(errorMessage(result.stdout), result.stdout)
        }
        return result.stdout
    }

    private fun errorMessage(output: String): String = when {
        output.contains("未登录") -> host.t("需要先在插件页登录 RollingGo。", "Sign in to RollingGo on the plugin page first.")
        output.contains("not found") || output.contains("未安装") ->
            host.t("酒店服务还没有安装，先去插件页安装。", "Hotel service isn't installed. Install it from the plugin page.")
        else -> host.t("酒店服务暂时没有完成查询，请稍后再试。", "The hotel service couldn't finish the lookup. Try again.")
    }

    private fun combineRaw(vararg outputs: String): String =
        outputs.map(String::trimEnd).filter(String::isNotBlank).joinToString("\n")

    private fun linuxOrNull(): LinuxService? =
        runCatching { host.linux() }.getOrNull()?.takeIf { it.available }
}
