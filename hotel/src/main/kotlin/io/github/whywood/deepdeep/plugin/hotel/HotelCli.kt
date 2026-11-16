package io.github.whywood.deepdeep.plugin.hotel

internal object HotelCli {
    const val BINARY = "rgh"
    const val CONFIG_DIR = "/root/.hotel-cli"
    private const val ENV = "HOME=/root"

    fun versionCommand(): String = "$ENV $BINARY --version 2>&1"

    fun statusCommand(): String = "$ENV $BINARY status 2>&1"

    fun loginCommand(): String = "$ENV $BINARY login 2>&1"

    fun logoutCommand(): String = "$ENV $BINARY logout 2>&1"

    fun installCommand(): String = "$ENV npm install -g @rollinggo/hotel@latest 2>&1"

    fun uninstallCommand(): String = "$ENV npm uninstall -g @rollinggo/hotel >/dev/null 2>&1; rm -rf $CONFIG_DIR; true"

    fun searchCommand(
        query: String,
        place: String,
        placeType: String,
        checkIn: String?,
        nights: Int,
        adults: Int,
        size: Int,
        starRatings: String?,
    ): String = buildList {
        add(ENV)
        add(BINARY)
        add("search-hotels")
        add("--origin-query")
        add(quote(query))
        add("--place")
        add(quote(place))
        add("--place-type")
        add(quote(placeType))
        checkIn?.takeIf(String::isNotBlank)?.let {
            add("--check-in-date")
            add(quote(it))
        }
        add("--stay-nights")
        add(nights.toString())
        add("--adult-count")
        add(adults.toString())
        add("--size")
        add(size.toString())
        starRatings?.takeIf(String::isNotBlank)?.let {
            add("--star-ratings")
            add(quote(it))
        }
        add("2>&1")
    }.joinToString(" ")

    fun detailCommand(
        hotelId: Long?,
        name: String?,
        checkIn: String?,
        checkOut: String?,
        rooms: Int,
        adults: Int,
    ): String = buildList {
        add(ENV)
        add(BINARY)
        add("hotel-detail")
        hotelId?.let {
            add("--hotel-id")
            add(it.toString())
        }
        name?.takeIf(String::isNotBlank)?.let {
            add("--name")
            add(quote(it))
        }
        checkIn?.takeIf(String::isNotBlank)?.let {
            add("--check-in-date")
            add(quote(it))
        }
        checkOut?.takeIf(String::isNotBlank)?.let {
            add("--check-out-date")
            add(quote(it))
        }
        add("--room-count")
        add(rooms.toString())
        add("--adult-count")
        add(adults.toString())
        add("2>&1")
    }.joinToString(" ")

    fun priceConfirmCommand(
        hotelId: Long,
        ratePlanId: String,
        rooms: Int,
        checkIn: String,
        checkOut: String,
        adults: Int,
    ): String = buildList {
        add(ENV)
        add(BINARY)
        add("price-confirm")
        add("--hotel-id")
        add(hotelId.toString())
        add("--rate-plan-id")
        add(quote(ratePlanId))
        add("--rooms")
        add(rooms.toString())
        add("--check-in-date")
        add(quote(checkIn))
        add("--check-out-date")
        add(quote(checkOut))
        add("--adults")
        add(adults.toString())
        add("2>&1")
    }.joinToString(" ")

    fun bookCommand(
        referenceNo: String,
        firstName: String,
        lastName: String,
        email: String,
    ): String = buildList {
        add(ENV)
        add(BINARY)
        add("book")
        add("--reference-no")
        add(quote(referenceNo))
        add("--first-name")
        add(quote(firstName))
        add("--last-name")
        add(quote(lastName))
        add("--email")
        add(quote(email))
        add("2>&1")
    }.joinToString(" ")

    fun ordersCommand(status: String?): String = buildList {
        add(ENV)
        add(BINARY)
        add("orders")
        status?.takeIf(String::isNotBlank)?.let {
            add("--status")
            add(quote(it))
        }
        add("2>&1")
    }.joinToString(" ")

    fun orderDetailCommand(orderNo: String): String = buildList {
        add(ENV)
        add(BINARY)
        add("order-detail")
        add(quote(orderNo))
        add("2>&1")
    }.joinToString(" ")

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun parseVersion(output: String): String? =
        Regex("""(?m)^\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$""").find(output)?.groupValues?.get(1)
            ?: Regex("""([0-9]+\.[0-9]+\.[0-9]+)""").find(output)?.groupValues?.get(1)

    data class Status(val loggedIn: Boolean, val account: String?)

    fun parseStatus(output: String): Status {
        val loggedIn = output.lineSequence().any { it.trim() == "已登录" }
        val account = output.lineSequence()
            .firstOrNull { it.trim().startsWith("用户:") || it.trim().startsWith("用户：") }
            ?.substringAfter(':')
            ?.substringAfter('：')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return Status(loggedIn = loggedIn, account = account)
    }

    fun parseLoginUrl(output: String): String? =
        Regex("""https://[^\s"'<>\\]+""").findAll(output)
            .map { it.value.trimEnd('.', ',', ')', ']') }
            .firstOrNull { it.contains("rollinggo") || it.contains("api.") }
}
