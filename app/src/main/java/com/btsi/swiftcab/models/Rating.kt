
package com.btsi.swiftcab.models

/**
 * Represents a rating left by a user for a trip, including optional comments,
 * anonymity flag, and timestamp. Used for both rider and driver ratings.
 */
data class Rating(
    val bookingId: String = "",
    val raterId: String = "",
    val ratedId: String = "",
    val rating: Float = 0.0f,
    val comments: String = "",
    val raterName: String = "",
    val anonymous: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

