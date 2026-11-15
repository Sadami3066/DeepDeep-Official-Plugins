package io.github.whywood.deepdeep.plugin.hotel

import kotlinx.serialization.Serializable

@Serializable
internal data class HotelSearchDetails(
    val query: String,
    val place: String,
    val placeType: String,
    val checkIn: String? = null,
    val nights: Int = 1,
    val hotels: List<HotelSummary> = emptyList(),
    val fetchedAt: Long,
)

@Serializable
internal data class HotelSummary(
    val name: String,
    val starRating: String? = null,
    val priceText: String? = null,
    val address: String? = null,
    val tags: List<String> = emptyList(),
    val bookingUrl: String? = null,
    val distance: String? = null,
    val hotelId: Long? = null,
)

@Serializable
internal data class HotelDetailDetails(
    val name: String,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val rooms: List<HotelRoomPlan> = emptyList(),
    val bookingUrl: String? = null,
    val fetchedAt: Long,
    val hotelId: Long? = null,
    val roomCount: Int = 1,
    val adultCount: Int = 2,
)

@Serializable
internal data class HotelRoomPlan(
    val name: String,
    val bed: String? = null,
    val priceText: String? = null,
    val cancellation: String? = null,
    val ratePlanId: String? = null,
    val totalPrice: Double? = null,
    val currency: String? = null,
)

@Serializable
internal data class HotelOrdersDetails(
    val orders: List<HotelOrderSummary> = emptyList(),
    val fetchedAt: Long,
)

@Serializable
internal data class HotelOrderSummary(
    val hotelName: String,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val status: String? = null,
    val roomName: String? = null,
    val totalPrice: String? = null,
    val paymentUrl: String? = null,
)

internal data class HotelPriceConfirmation(
    val referenceNo: String,
    val totalPrice: Double? = null,
    val currency: String? = null,
)

@Serializable
internal data class HotelBookingDetails(
    val orderNo: String,
    val hotelName: String,
    val roomName: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val totalPrice: String? = null,
    val paymentType: String? = null,
    val paymentUrl: String? = null,
    val message: String? = null,
    val fetchedAt: Long,
)
