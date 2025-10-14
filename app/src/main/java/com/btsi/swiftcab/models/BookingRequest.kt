package com.btsi.swiftcab.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class BookingRequest(
    val bookingId: String? = null,
    val riderId: String? = null,
    val riderName: String? = null,
    val pickupLatitude: Double? = null,
    val pickupLongitude: Double? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val pickupAddress: String? = null,
    val destinationAddress: String? = null,
    var status: String? = null,
    val timestamp: Long? = null,
    val driverId: String? = null,
    val driverName: String? = null,
    val driverVehicleDetails: String? = null,
    val cancellationReason: String? = null, // Added to handle cancellation reasons from the backend
    val driverLocation: HashMap<String, Double>? = null
)
