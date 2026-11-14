package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.PluginHost

internal class HotelToolException(message: String) : Exception(message)

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
    ): HotelSearchDetails {
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
        return parseHotelSearch(output, query, place, placeType, checkIn, nights)
            ?: throw HotelToolException(host.t("酒店搜索没有返回可用结果，请稍后再试。", "The hotel search returned no usable results. Try again."))
    }

    suspend fun detail(
        hotelId: Long?,
        name: String?,
        checkIn: String?,
        checkOut: String?,
        rooms: Int,
        adults: Int,
    ): HotelDetailDetails {
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
        return parseHotelDetail(output, name.orEmpty())
            ?: throw HotelToolException(host.t("没有查到这家酒店的房型，请稍后再试。", "No room types came back for this hotel. Try again."))
    }

    suspend fun orders(status: String?): HotelOrdersDetails {
        ensureReady()
        val output = exec(HotelCli.ordersCommand(status))
        return parseHotelOrders(output)
            ?: throw HotelToolException(host.t("没有查到订单，请稍后再试。", "Couldn't load your orders. Try again."))
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
            throw HotelToolException(errorMessage(result.stdout))
        }
        return result.stdout
    }

    private fun errorMessage(output: String): String = when {
        output.contains("未登录") -> host.t("需要先在插件页登录 RollingGo。", "Sign in to RollingGo on the plugin page first.")
        output.contains("not found") || output.contains("未安装") ->
            host.t("酒店服务还没有安装，先去插件页安装。", "Hotel service isn't installed. Install it from the plugin page.")
        else -> host.t("酒店服务暂时没有完成查询，请稍后再试。", "The hotel service couldn't finish the lookup. Try again.")
    }

    private fun linuxOrNull(): LinuxService? =
        runCatching { host.linux() }.getOrNull()?.takeIf { it.available }
}
