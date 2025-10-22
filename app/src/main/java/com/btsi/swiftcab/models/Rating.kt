
package com.btsi.swiftcab.models

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
