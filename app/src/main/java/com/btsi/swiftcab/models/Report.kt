package com.btsi.swiftcab.models

import com.google.firebase.Timestamp

data class Report(
    val bookingId: String = "",
    val reporterId: String = "",
    val riderId: String = "",
    val driverId: String = "",
    val message: String = "",
    val category: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    // New: resolution status and metadata
    val status: String = "open",           // "open" or "resolved"
    val resolvedAt: Timestamp? = null,      // when resolved (Firestore Timestamp)
    val resolvedBy: String? = null          // admin user id who resolved
)
