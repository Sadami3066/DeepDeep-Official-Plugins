package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.PluginHost

internal open class HotelToolException(message: String, val raw: String? = null) : Exception(message)

internal class HotelPriceChangedException(
    val confirmed: Double,
    val current: Double,
    val currency: String?,
) : HotelToolException("price changed")

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
        distanceInMeter: Int? = null,
        requiredTags: List<String> = emptyList(),
        preferredBrands: List<String> = emptyList(),
        maxPricePerNight: Double? = null,
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
                distanceInMeter = distanceInMeter,
                requiredTags = requiredTags,
                preferredBrands = preferredBrands,
                maxPricePerNight = maxPricePerNight,
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
        cancelPolicy: String? = null,
        mealType: String? = null,
        childCount: Int? = null,
        childAges: String? = null,
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
                cancelPolicy = cancelPolicy,
                mealType = mealType,
                childCount = childCount,
                childAges = childAges,
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
        guests: List<HotelGuest> = emptyList(),
        customerRequest: String? = null,
        expectedTotal: Double? = null,
        expectedCurrency: String? = null,
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
        val price = parseHotelPriceConfirmation(priceOutput)
            ?: throw HotelToolException(
                parseHotelOrderMessage(priceOutput)?.let {
                    host.t("锁价失败：$it", "Price confirmation failed: $it")
                } ?: host.t("价格确认没有返回预订参考号，请稍后再试。", "Price confirmation didn't return a reference. Try again."),
                priceOutput,
            )
        val lockedTotal = price.totalPrice
        if (expectedTotal != null && lockedTotal != null && kotlin.math.abs(expectedTotal - lockedTotal) > 0.01) {
            throw HotelPriceChangedException(
                confirmed = expectedTotal,
                current = lockedTotal,
                currency = price.currency ?: expectedCurrency,
            )
        }
        val bookOutput = exec(
            HotelCli.bookCommand(
                referenceNo = price.referenceNo,
                firstName = firstName,
                lastName = lastName,
                email = email,
                guests = guests,
                customerRequest = customerRequest,
            ),
        )
        val parsed = parseHotelBooking(bookOutput)
            ?: throw HotelToolException(
                parseHotelOrderMessage(bookOutput)?.let {
                    host.t("下单失败：$it", "Booking failed: $it")
                } ?: host.t("订单没有创建成功，请稍后再试。", "The order wasn't created. Try again."),
                combineRaw(priceOutput, bookOutput),
            )
        val lockedText = lockedTotal?.let { money(it, price.currency ?: parsed.currency) }
        val complete = if (lockedText != null) {
            parsed.copy(
                totalPrice = lockedText,
                totalDerived = false,
                currency = price.currency ?: parsed.currency,
            )
        } else {
            parsed
        }
        return HotelOutput(complete, combineRaw(priceOutput, bookOutput))
    }

    suspend fun orders(
        status: String?,
        startDate: String? = null,
        endDate: String? = null,
    ): HotelOutput<HotelOrdersDetails> {
        ensureReady()
        val output = exec(HotelCli.ordersCommand(status, startDate, endDate))
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

    suspend fun tags(): HotelOutput<HotelSearchTags> {
        ensureReady()
        val output = exec(HotelCli.tagsCommand())
        val parsed = parseHotelSearchTags(output)
            ?: throw HotelToolException(
                host.t("没有拿到酒店筛选标签，请稍后再试。", "No hotel search tags came back. Try again."),
                output,
            )
        return HotelOutput(parsed, output)
    }

    suspend fun cancelQuote(orderNo: String): HotelOutput<HotelCancelQuote> {
        ensureReady()
        if (orderNo.isBlank()) {
            throw HotelToolException(host.t("请告诉我要取消哪个订单号。", "Tell me which order number to cancel."))
        }
        val output = exec(HotelCli.preCancelCommand(orderNo))
        val parsed = parseHotelCancelQuote(output, orderNo)
            ?: throw HotelToolException(
                parseHotelOrderMessage(output)?.let {
                    host.t("取消预检失败：$it", "Cancellation check failed: $it")
                } ?: host.t("取消预检没有返回可用信息，请稍后再试。", "The cancellation check returned no usable info. Try again."),
                output,
            )
        return HotelOutput(parsed, output)
    }

    suspend fun cancelConfirm(
        orderNo: String,
        bookingId: String,
        confirmId: String,
        description: String?,
    ): HotelOutput<HotelCancelResult> {
        ensureReady()
        val result = runCatching { exec(HotelCli.confirmCancelCommand(orderNo, bookingId, confirmId, description)) }
            .getOrElse { error ->
                val raw = (error as? HotelToolException)?.raw
                val reason = raw?.let(::parseHotelOrderMessage)?.takeIf { it.isNotBlank() }
                throw HotelToolException(
                    if (reason != null) host.t("取消失败：$reason", "Cancellation failed: $reason")
                    else host.t("取消失败，请稍后再试。", "Cancellation failed. Try again."),
                    raw,
                )
            }
        val parsed = parseHotelCancelResult(result, orderNo, null)
            ?: throw HotelToolException(
                parseHotelOrderMessage(result)?.let {
                    host.t("取消失败：$it", "Cancellation failed: $it")
                } ?: host.t("取消结果无法识别，请稍后再试。", "The cancellation result was unreadable. Try again."),
                result,
            )
        return HotelOutput(parsed, result)
    }

    private suspend fun ensureReady() {
        val now = System.currentTimeMillis()
        if (HotelReadyCache.fresh(now) && HotelReadyCache.ready()) return
        val linux = linuxOrNull() ?: throw HotelToolException(host.text(ERROR_LINUX))
        val version = runCatching { linux.exec(HotelCli.versionCommand(), timeoutMs = 20_000) }.getOrNull()
        if (version == null || HotelCli.parseVersion(version.stdout) == null) {
            throw HotelToolException(host.t("酒店服务还没有安装，先去插件页安装。", "Hotel service isn't installed. Install it from the plugin page."))
        }
        val status = runCatching { linux.exec(HotelCli.statusCommand(), timeoutMs = 20_000) }.getOrNull()
        if (status == null || !HotelCli.parseStatus(status.stdout).loggedIn) {
            throw HotelToolException(host.t("需要先在插件页登录 RollingGo。", "Sign in to RollingGo on the plugin page first."))
        }
        HotelReadyCache.mark(now, true)
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

internal object HotelReadyCache {
    @Volatile
    private var checkedAt: Long = 0L

    @Volatile
    private var ready: Boolean = false

    fun fresh(now: Long): Boolean = now - checkedAt in 1..60_000L

    fun ready(): Boolean = ready

    fun mark(now: Long, value: Boolean) {
        checkedAt = now
        ready = value
    }

    fun reset() {
        checkedAt = 0L
        ready = false
    }
}
