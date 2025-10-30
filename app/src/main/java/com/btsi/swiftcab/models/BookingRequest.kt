package com.btsi.swiftcab.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class BookingRequest(
    var bookingId: String? = null,
    val riderId: String? = null,
    val riderName: String? = null,
    val riderPhone: String? = null,
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
    val driverPhone: String? = null,
    val driverVehicleDetails: String? = null,
    val cancellationReason: String? = null, // Added to handle cancellation reasons from the backend
    val driverLocation: HashMap<String, Double>? = null,
    var riderRated: Boolean = false,
    var driverRated: Boolean = false,
    var riderRating: Float? = null,
    // Fare-related fields
    var estimatedFare: Double? = null,
    var finalFare: Double? = null,
    var distanceKm: Double? = null,
    var fareBase: Double? = null,
    var perKmRate: Double? = null,
    var perMinuteRate: Double? = null,
    var tripStartedAt: Long? = null,
    var tripEndedAt: Long? = null,
    var durationMinutes: Int? = null,
    var paymentConfirmed: Boolean? = null,
    // Loyalty / discount info
    var appliedDiscountPercent: Int? = null
)


