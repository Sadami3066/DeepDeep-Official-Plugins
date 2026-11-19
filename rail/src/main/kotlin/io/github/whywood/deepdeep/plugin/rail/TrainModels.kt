package io.github.whywood.superpi.plugin.rail

import kotlinx.serialization.Serializable

@Serializable
internal data class TrainSearchDetails(
    val from: String,
    val to: String,
    val fromCode: String = "",
    val toCode: String = "",
    val date: String,
    val trains: List<TrainOption>,
    val fetchedAt: Long,
    val bookingUrl: String = "",
)

@Serializable
internal data class TrainOption(
    val code: String,
    val fromStation: String,
    val toStation: String,
    val depart: String,
    val arrive: String,
    val duration: String,
    val seats: List<SeatAvailability>,
    val fromCode: String = "",
    val toCode: String = "",
    val fromStationNo: String = "",
    val toStationNo: String = "",
    val trainNo: String = "",
    val seatTypes: String = "",
    val date: String = "",
)

internal enum class SeatStatus {
    AVAILABLE,
    SOLD_OUT,
    NOT_ON_SALE,
    NOT_RETURNED,
    NOT_OFFERED,
}

@Serializable
internal data class SeatAvailability(
    val name: String,
    val count: String = "",
    val status: SeatStatus = SeatStatus.AVAILABLE,
    val code: String = "",
)

@Serializable
internal data class SeatPrice(
    val name: String,
    val priceText: String,
)

@Serializable
internal data class TrainPriceDetails(
    val code: String,
    val from: String,
    val to: String,
    val fromCode: String = "",
    val toCode: String = "",
    val date: String,
    val prices: List<SeatPrice>,
    val fetchedAt: Long,
    val bookingUrl: String = "",
    val fromStation: String = "",
    val toStation: String = "",
    val fromStationNo: String = "",
    val toStationNo: String = "",
    val trainNo: String = "",
)

@Serializable
internal data class RecentRoute(
    val from: String,
    val to: String,
)

internal val TrainPriceDetails.fromPlace: String get() = fromStation.ifBlank { from }

internal val TrainPriceDetails.toPlace: String get() = toStation.ifBlank { to }

internal data class TrainGroup(
    val code: String,
    val trains: List<TrainOption>,
) {
    val depart: String get() = trains.firstOrNull()?.depart.orEmpty()

    val durationLabel: String
        get() {
            val durations = trains.map { it.duration }.filter { it.isNotBlank() }.sortedBy(::minutesOf)
            return when {
                durations.isEmpty() -> ""
                durations.first() == durations.last() -> durations.first()
                else -> "${durations.first()}–${durations.last()}"
            }
        }

    val notOfferedSeats: List<SeatAvailability>
        get() = trains.firstOrNull()?.seats.orEmpty().filter { seat ->
            trains.all { train -> train.seatNamed(seat.name)?.status == SeatStatus.NOT_OFFERED }
        }
}

internal fun TrainGroup.primary(destination: String): TrainOption {
    val exact = destination.trim().takeIf { it.isNotEmpty() }
        ?.let { name -> trains.firstOrNull { it.toStation == name } }
    return exact
        ?: trains.maxByOrNull { clockMinutes(it.arrive) ?: -1 }
        ?: trains.first()
}

internal fun groupTrains(trains: List<TrainOption>): List<TrainGroup> =
    trains.groupBy { it.code }.map { (code, rows) -> TrainGroup(code, rows) }

internal fun TrainOption.seatNamed(name: String): SeatAvailability? = seats.firstOrNull { it.name == name }

private fun minutesOf(duration: String): Int = clockMinutes(duration) ?: Int.MAX_VALUE
