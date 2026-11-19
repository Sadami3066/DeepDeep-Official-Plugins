package io.github.whywood.superpi.plugin.hotel

internal object HotelCli {
    const val BINARY = "rgh"
    const val CONFIG_DIR = "/root/.hotel-cli"
    private const val ENV = "HOME=/root DOTENV_CONFIG_QUIET=true"

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
        distanceInMeter: Int? = null,
        requiredTags: List<String> = emptyList(),
        preferredBrands: List<String> = emptyList(),
        maxPricePerNight: Double? = null,
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
        distanceInMeter?.takeIf { it > 0 }?.let {
            add("--distance-in-meter")
            add(it.toString())
        }
        requiredTags.map(String::trim).filter(String::isNotEmpty).forEach {
            add("--required-tag")
            add(quote(it))
        }
        preferredBrands.map(String::trim).filter(String::isNotEmpty).forEach {
            add("--preferred-brand")
            add(quote(it))
        }
        maxPricePerNight?.takeIf { it > 0 }?.let {
            add("--max-price-per-night")
            add(moneyArg(it))
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
        cancelPolicy: String? = null,
        mealType: String? = null,
        childCount: Int? = null,
        childAges: String? = null,
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
        cancelPolicy?.takeIf(String::isNotBlank)?.let {
            add("--cancel-policy")
            add(quote(it))
        }
        mealType?.takeIf(String::isNotBlank)?.let {
            add("--meal-type")
            add(quote(it))
        }
        childCount?.takeIf { it > 0 }?.let {
            add("--child-count")
            add(it.toString())
        }
        childAges?.takeIf(String::isNotBlank)?.let {
            add("--child-age")
            add(quote(it))
        }
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
        guests: List<HotelGuest> = emptyList(),
        customerRequest: String? = null,
    ): String = buildList {
        add(ENV)
        add(BINARY)
        add("book")
        add("--reference-no")
        add(quote(referenceNo))
        firstName.takeIf(String::isNotBlank)?.let {
            add("--first-name")
            add(quote(it))
        }
        lastName.takeIf(String::isNotBlank)?.let {
            add("--last-name")
            add(quote(it))
        }
        add("--email")
        add(quote(email))
        guests.forEach { guest ->
            add("--guest")
            add(quote("${guest.roomNum},${guest.firstName},${guest.lastName},${guest.isAdult}"))
        }
        customerRequest?.takeIf(String::isNotBlank)?.let {
            add("--customer-request")
            add(quote(it))
        }
        add("2>&1")
    }.joinToString(" ")

    fun ordersCommand(status: String?, startDate: String? = null, endDate: String? = null): String = buildList {
        add(ENV)
        add(BINARY)
        add("orders")
        status?.takeIf(String::isNotBlank)?.let {
            add("--status")
            add(quote(it))
        }
        startDate?.takeIf(String::isNotBlank)?.let {
            add("--start-date")
            add(quote(it))
        }
        endDate?.takeIf(String::isNotBlank)?.let {
            add("--end-date")
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

    fun preCancelCommand(orderNo: String): String =
        "$ENV $BINARY pre-cancel --order-no ${quote(orderNo)} 2>&1"

    fun confirmCancelCommand(
        orderNo: String,
        bookingId: String,
        confirmId: String,
        description: String?,
    ): String = buildList {
        add(ENV)
        add(BINARY)
        add("confirm-cancel")
        add("--order-no")
        add(quote(orderNo))
        add("--booking-id")
        add(quote(bookingId))
        add("--confirm-id")
        add(quote(confirmId))
        description?.takeIf(String::isNotBlank)?.let {
            add("--description")
            add(quote(it))
        }
        add("2>&1")
    }.joinToString(" ")

    fun tagsCommand(): String = "$ENV $BINARY hotel-tags 2>&1"

    fun updateCommand(): String = "$ENV $BINARY update 2>&1"

    fun latestVersionCommand(): String = "$ENV npm view @rollinggo/hotel version 2>&1"

    fun parseLatestVersion(output: String): String? =
        Regex("""(?m)^\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$""").find(output)?.groupValues?.get(1)
            ?: Regex("""([0-9]+\.[0-9]+\.[0-9]+)""").find(output)?.groupValues?.get(1)

    fun versionNewer(latest: String?, installed: String?): Boolean {
        val left = parseParts(latest) ?: return false
        val right = parseParts(installed) ?: return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseParts(version: String?): List<Int>? {
        val value = version?.trim()?.removePrefix("v")?.takeIf(String::isNotEmpty) ?: return null
        val core = value.substringBefore('-').substringBefore('+')
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun moneyArg(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

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
