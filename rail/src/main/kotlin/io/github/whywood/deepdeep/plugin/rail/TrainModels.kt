package io.github.whywood.deepdeep.plugin.rail

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
)

@Serializable
internal data class SeatAvailability(
    val name: String,
    val count: String,
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
)

@Serializable
internal data class RecentRoute(
    val from: String,
    val to: String,
)
