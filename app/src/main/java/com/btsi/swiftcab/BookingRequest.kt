package com.btsi.swiftcab

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class BookingRequest(
    val bookingId: String? = null,
    val riderId: String? = null,
    var driverId: String? = null,
    val pickupAddress: String? = null,
    val destinationAddress: String? = null,
    val pickupLat: Double? = null,
    val pickupLng: Double? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val riderName: String? = null,
    val riderFcmToken: String? = null,
    var status: String? = null,
    val timestamp: Long? = null
)
