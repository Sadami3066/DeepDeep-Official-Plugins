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
    val nextPageToken: String? = null,
    val filter: List<String> = emptyList(),
    val noMatchingRates: Boolean = false,
)

@Serializable
internal data class HotelSummary(
    val name: String,
    val nameEn: String? = null,
    val starRating: String? = null,
    val priceText: String? = null,
    val address: String? = null,
    val tags: List<String> = emptyList(),
    val amenities: List<String> = emptyList(),
    val bookingUrl: String? = null,
    val distance: String? = null,
    val distanceMeters: Int? = null,
    val hotelId: Long? = null,
)

@Serializable
internal data class HotelDetailDetails(
    val name: String,
    val nameEn: String? = null,
    val starRating: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val rooms: List<HotelRoomPlan> = emptyList(),
    val roomGroups: List<HotelRoomGroup> = emptyList(),
    val bookingUrl: String? = null,
    val fetchedAt: Long,
    val hotelId: Long? = null,
    val roomCount: Int = 1,
    val adultCount: Int = 2,
    val errorMessage: String? = null,
    val message: String? = null,
    val filter: List<String> = emptyList(),
    val noMatchingRates: Boolean = false,
)

@Serializable
internal data class HotelRoomPlan(
    val name: String,
    val ratePlanName: String? = null,
    val bed: String? = null,
    val priceText: String? = null,
    val avgPriceText: String? = null,
    val cancellation: String? = null,
    val cancellations: List<String> = emptyList(),
    val ratePlanId: String? = null,
    val totalPrice: Double? = null,
    val averagePrice: Double? = null,
    val currency: String? = null,
    val meal: String? = null,
    val mealAmount: Int? = null,
    val cancelable: Boolean? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val inventoryCount: Int? = null,
    val onRequest: Boolean? = null,
    val roomSize: String? = null,
    val floor: String? = null,
    val maxOccupancy: Int? = null,
    val hasWindow: Boolean? = null,
    val hasWifi: Boolean? = null,
    val images: List<String> = emptyList(),
    val totalDerived: Boolean = false,
)

@Serializable
internal data class HotelRoomGroup(
    val name: String,
    val bed: String? = null,
    val lowestPriceText: String? = null,
    val planCount: Int = 0,
    val cheapestCancellation: String? = null,
    val plans: List<HotelRoomPlan> = emptyList(),
)

@Serializable
internal data class HotelOrdersDetails(
    val message: String? = null,
    val orders: List<HotelOrderSummary> = emptyList(),
    val fetchedAt: Long,
)

@Serializable
internal data class HotelOrderSummary(
    val hotelName: String,
    val orderNo: String? = null,
    val subOrderNo: String? = null,
    val referenceNo: String? = null,
    val hotelAddress: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val nights: Int? = null,
    val roomCount: Int? = null,
    val status: String? = null,
    val paymentStatus: String? = null,
    val roomName: String? = null,
    val totalPrice: String? = null,
    val currency: String? = null,
    val meal: String? = null,
    val cancelPolicy: String? = null,
    val orderDate: String? = null,
    val orderDetailUrl: String? = null,
    val contactName: String? = null,
    val contactEmail: String? = null,
    val paymentUrl: String? = null,
)

@Serializable
internal data class HotelOrderDetail(
    val orderNo: String,
    val referenceNo: String? = null,
    val hotelConfirmationNo: String? = null,
    val orderStatus: String? = null,
    val orderDate: String? = null,
    val orderDetailUrl: String? = null,
    val hotelName: String? = null,
    val hotelImageUrl: String? = null,
    val hotelPhone: String? = null,
    val platformSupportPhone: String? = null,
    val hotelAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val roomName: String? = null,
    val bedType: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    val roomCount: Int? = null,
    val nights: Int? = null,
    val meal: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val guestNames: List<String> = emptyList(),
    val totalPrice: String? = null,
    val currency: String? = null,
    val paymentType: String? = null,
    val paymentUrl: String? = null,
    val paymentDeadline: String? = null,
    val isCancelable: Boolean? = null,
    val freeCancelDeadline: String? = null,
    val cancelPolicy: String? = null,
    val message: String? = null,
    val fetchedAt: Long,
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
    val ratePlanName: String? = null,
    val bed: String? = null,
    val meal: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val nights: Int? = null,
    val roomCount: Int? = null,
    val adultCount: Int? = null,
    val totalPrice: String? = null,
    val totalDerived: Boolean = false,
    val currency: String? = null,
    val cancellation: String? = null,
    val contactName: String? = null,
    val contactEmail: String? = null,
    val paymentType: String? = null,
    val paymentUrl: String? = null,
    val message: String? = null,
    val fetchedAt: Long,
)

internal sealed interface HotelPlanSelection {
    data class Selected(val plan: HotelRoomPlan) : HotelPlanSelection

    data class Multiple(val plans: List<HotelRoomPlan>) : HotelPlanSelection

    data class MissingRoom(val roomName: String) : HotelPlanSelection

    data class MissingRatePlan(val ratePlanId: String) : HotelPlanSelection
}
