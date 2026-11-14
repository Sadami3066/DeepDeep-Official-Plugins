package io.github.whywood.deepdeep.plugin.rail

import kotlinx.serialization.Serializable

@Serializable
internal data class TrainSearchDetails(
    val from: String,
    val to: String,
    val date: String,
    val trains: List<TrainOption>,
    val fetchedAt: Long,
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
)

@Serializable
internal data class SeatAvailability(
    val name: String,
    val count: String,
)

@Serializable
internal data class TicketResponse(val data: TicketData? = null)

@Serializable
internal data class TicketData(val result: List<String> = emptyList())
