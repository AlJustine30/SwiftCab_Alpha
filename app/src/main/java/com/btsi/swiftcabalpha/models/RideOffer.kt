package com.btsi.swiftcabalpha.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class RideOffer(
    val bookingId: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val pickupAddress: String? = null,
    val pickupLat: Double? = null,
    val pickupLng: Double? = null,
    val destinationAddress: String? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val fareEstimate: String? = null, // Can be Double if it's always numeric
    val status: String? = null,      // e.g., "NEW_RIDE_OFFER"
    val offerSentAt: Long? = null    // Timestamp from Firebase ServerValue.TIMESTAMP
) {
    // Firebase Realtime Database requires a no-argument constructor for deserialization
    constructor() : this(null, null, null, null, null, null, null, null, null, null, null, null)
}
