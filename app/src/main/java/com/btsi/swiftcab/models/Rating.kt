
package com.btsi.swiftcab.models

data class Rating(
    val bookingId: String = "",
    val raterId: String = "",
    val ratedId: String = "",
    val rating: Float = 0.0f,
    val comments: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
