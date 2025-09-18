package com.btsi.swiftcabalpha.models

import com.google.android.gms.maps.model.LatLng // Using Google Maps LatLng directly

// Data class for booking requests
data class BookingRequest(
    val bookingId: String = "",
    val riderId: String = "",
    val riderName: String = "", // Good to have for the driver
    val pickupLatitude: Double? = null,
    val pickupLongitude: Double? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val pickupAddress: String = "",
    val destinationAddress: String = "",
    var status: String = "PENDING", // PENDING, ACCEPTED, DECLINED, DRIVER_ARRIVED, ON_TRIP, COMPLETED, CANCELLED_RIDER, CANCELLED_DRIVER
    var driverId: String? = null,
    var driverName: String? = null,
    var driverVehicleDetails: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var lastUpdateTime: Long = System.currentTimeMillis()
) {
    // No-argument constructor for Firebase
    constructor() : this(
        "", "", "", null, null, null, null,
        "", "", "PENDING", null, null, null,
        System.currentTimeMillis(), System.currentTimeMillis()
    )
}

// Helper to convert Google Maps LatLng to a format storable in Firebase RTDB (individual doubles)
fun bookingLatLngFromGoogleLatLng(googleLatLng: LatLng?): Pair<Double?, Double?>? {
    return googleLatLng?.let { Pair(it.latitude, it.longitude) }
}

// Helper to convert stored latitude/longitude back to Google Maps LatLng
fun googleLatLngFromBookingLatLng(latitude: Double?, longitude: Double?): LatLng? {
    return if (latitude != null && longitude != null) {
        LatLng(latitude, longitude)
    } else {
        null
    }
}
