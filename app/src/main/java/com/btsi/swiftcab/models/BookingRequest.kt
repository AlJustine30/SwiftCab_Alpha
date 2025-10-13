package com.btsi.swiftcab.models

import com.google.android.gms.maps.model.LatLng

/**
 * Represents a single ride booking in the system.
 * This class is used by both Rider and Driver apps, so all fields that are not present
 * at creation time must be nullable with a default value of null.
 * This prevents Firebase deserialization from failing during transactions.
 */
data class BookingRequest(
    val bookingId: String = "",
    val riderId: String = "",
    var riderName: String? = null, // Corrected: Made nullable. Set by rider app.
    val pickupLatitude: Double? = null,
    val pickupLongitude: Double? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val pickupAddress: String = "",
    val destinationAddress: String = "",
    var status: String = "PENDING",
    var driverId: String? = null, // Set by driver app during acceptance.
    var driverName: String? = null, // Set by driver app during acceptance.
    var driverVehicleDetails: String? = null, // Can be set by driver app.
    val timestamp: Long = 0L,
    var lastUpdateTime: Long? = null // Set on any status update.
) {
    // A no-argument constructor is required by Firebase for deserialization.
    constructor() : this(
        bookingId = "",
        riderId = "",
        riderName = null,
        pickupLatitude = null,
        pickupLongitude = null,
        destinationLatitude = null,
        destinationLongitude = null,
        pickupAddress = "",
        destinationAddress = "",
        status = "PENDING",
        driverId = null,
        driverName = null,
        driverVehicleDetails = null,
        timestamp = 0L,
        lastUpdateTime = null
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
