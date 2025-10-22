package com.btsi.swiftcab

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class BookingUiState {
    object Initial : BookingUiState()
    data class FindingDriver(val message: String) : BookingUiState()
    data class DriverOnTheWay(
        val driverId: String,
        val driverName: String,
        val driverPhone: String?,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng,
        val driverLocation: LatLng?
    ) : BookingUiState()

    data class DriverArrived(
        val driverId: String,
        val driverName: String,
        val driverPhone: String?,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng
    ) : BookingUiState()

    data class TripInProgress(
        val driverId: String,
        val driverName: String,
        val driverPhone: String?,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng,
        val driverLocation: LatLng?
    ) : BookingUiState()

    data class TripCompleted(val bookingId: String, val driverId: String) : BookingUiState()
    data class Canceled(val message: String) : BookingUiState()
    data class Error(val message: String) : BookingUiState()
}


class BookingViewModel(
    private val auth: FirebaseAuth,
    private val database: FirebaseDatabase,
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore // For archiving
) : ViewModel() {

    private val bookingRequestsRef: DatabaseReference = database.getReference("bookingRequests")
    private val driversRef: DatabaseReference = database.getReference("drivers")

    private var currentBookingId: String? = null
    private var bookingStatusListener: ValueEventListener? = null
    private var driverLocationTracker: Job? = null
    private var trackedDriverId: String? = null

    private val _uiState = MutableLiveData<BookingUiState>()
    val uiState: LiveData<BookingUiState> = _uiState

    companion object {
        private const val TAG = "BookingViewModel"
    }

    fun createBooking(
        pickupLatLng: LatLng,
        destinationLatLng: LatLng,
        pickupAddress: String,
        destinationAddress: String
    ) {
        val riderId = auth.currentUser?.uid
        if (riderId == null) {
            _uiState.postValue(BookingUiState.Error("User not logged in."))
            return
        }

        // Before creating a new booking, check if the rider already has an active one
        val activeStatuses = setOf(
            "SEARCHING",
            "ACCEPTED",
            "EN_ROUTE_TO_PICKUP",
            "ARRIVED_AT_PICKUP",
            "EN_ROUTE_TO_DROPOFF"
        )
        bookingRequestsRef.orderByChild("riderId").equalTo(riderId).get()
            .addOnSuccessListener { snapshot ->
                var existingActiveBookingId: String? = null
                snapshot.children.forEach { child ->
                    val req = child.getValue(BookingRequest::class.java)
                    val status = req?.status
                    if (status != null && status in activeStatuses) {
                        existingActiveBookingId = req.bookingId ?: child.key
                    }
                }

                if (existingActiveBookingId != null) {
                    Log.d(TAG, "Active booking exists for rider: $existingActiveBookingId")
                    listenForBookingStatus(existingActiveBookingId!!)
                    _uiState.postValue(BookingUiState.FindingDriver("Resuming your active booking..."))
                    return@addOnSuccessListener
                }

                // No active booking found; proceed to create a new one
                val bookingId = bookingRequestsRef.push().key
                if (bookingId == null) {
                    _uiState.postValue(BookingUiState.Error("Could not generate booking ID."))
                    return@addOnSuccessListener
                }

                firestore.collection("users").document(riderId).get()
                    .addOnSuccessListener { doc ->
                        val riderPhone = doc.getString("phone")
                        val riderNameFromFirestore = doc.getString("name")
                        val riderName = auth.currentUser?.displayName?.takeIf { it.isNotBlank() } ?: riderNameFromFirestore ?: "Unknown Rider"
                        val bookingRequest = BookingRequest(
                            bookingId = bookingId,
                            riderId = riderId,
                            riderName = riderName,
                            riderPhone = riderPhone,
                            pickupLatitude = pickupLatLng.latitude,
                            pickupLongitude = pickupLatLng.longitude,
                            pickupAddress = pickupAddress,
                            destinationLatitude = destinationLatLng.latitude,
                            destinationLongitude = destinationLatLng.longitude,
                            destinationAddress = destinationAddress,
                            status = "SEARCHING",
                            cancellationReason = null
                        )
                        bookingRequestsRef.child(bookingId).setValue(bookingRequest)
                            .addOnSuccessListener {
                                listenForBookingStatus(bookingId)
                            }
                            .addOnFailureListener { e ->
                                _uiState.postValue(BookingUiState.Error("Failed to create booking: ${e.message}"))
                            }
                    }
                    .addOnFailureListener { e ->
                        _uiState.postValue(BookingUiState.Error("Failed to fetch profile: ${e.message}"))
                    }
            }
            .addOnFailureListener { e ->
                _uiState.postValue(BookingUiState.Error("Failed to check existing bookings: ${e.message}"))
            }
    }

    fun cancelBooking() {
        currentBookingId?.let { bookingId ->
            functions.getHttpsCallable("cancelBooking")
                .call(mapOf("bookingId" to bookingId))
                .addOnSuccessListener {
                    Log.d(TAG, "Booking cancelled successfully.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to cancel booking", e)
                    _uiState.postValue(BookingUiState.Error("Failed to cancel booking: ${e.message}"))
                }
        } ?: run {
            _uiState.postValue(BookingUiState.Error("No active booking to cancel."))
        }
    }

    private fun saveCompletedBookingToHistory(booking: BookingRequest, riderId: String, driverId: String?) {
        try {
            val data = hashMapOf(
                "bookingId" to (booking.bookingId ?: ""),
                "riderId" to riderId,
                "riderName" to (booking.riderName ?: ""),
                "riderPhone" to (booking.riderPhone ?: ""),
                "driverId" to (driverId ?: (booking.driverId ?: "")),
                "driverName" to (booking.driverName ?: ""),
                "driverPhone" to (booking.driverPhone ?: ""),
                "driverVehicleDetails" to (booking.driverVehicleDetails ?: ""),
                "pickupAddress" to (booking.pickupAddress ?: ""),
                "destinationAddress" to (booking.destinationAddress ?: ""),
                "pickupLatitude" to (booking.pickupLatitude ?: 0.0),
                "pickupLongitude" to (booking.pickupLongitude ?: 0.0),
                "destinationLatitude" to (booking.destinationLatitude ?: 0.0),
                "destinationLongitude" to (booking.destinationLongitude ?: 0.0),
                "status" to "COMPLETED",
                "timestamp" to (booking.timestamp ?: System.currentTimeMillis()),
                "riderRated" to false,
                "driverRated" to false
            )
            val docId = booking.bookingId ?: return
            firestore.collection("bookinghistory").document(docId)
                .set(data)
                .addOnSuccessListener { Log.d(TAG, "Booking archived to history: $docId") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to archive booking: $docId", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving booking", e)
        }
    }

    private fun listenForBookingStatus(bookingId: String) {
        removeBookingStatusListener()
        currentBookingId = bookingId
        val reference = bookingRequestsRef.child(bookingId)
        _uiState.postValue(BookingUiState.FindingDriver("Finding a driver..."))

        bookingStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val booking = snapshot.getValue(BookingRequest::class.java)
                if (booking == null || booking.bookingId == null) {
                    _uiState.postValue(BookingUiState.Canceled("Booking resolved."))
                    removeBookingStatusListener()
                    stopDriverLocationTracking()
                    return
                }

                val isTerminalState = booking.status in listOf("COMPLETED", "CANCELED", "NO_DRIVERS", "ERROR")

                if (isTerminalState) {
                    if (booking.status == "COMPLETED") {
                        val riderId = auth.currentUser?.uid
                        val driverIdFromBooking = booking.driverId
                        if (riderId != null && driverIdFromBooking != null) {
                            saveCompletedBookingToHistory(booking, riderId, driverIdFromBooking)
                        }
                        _uiState.postValue(BookingUiState.TripCompleted(booking.bookingId!!, driverIdFromBooking ?: ""))
                    } else {
                        val reason = booking.cancellationReason ?: booking.status
                        val message = when (reason) {
                            "NO_DRIVERS" -> "Sorry, no drivers were found near you."
                            "user_canceled" -> "You cancelled the booking."
                            else -> "Your booking was cancelled."
                        }
                        _uiState.postValue(BookingUiState.Canceled(message))
                    }
                    stopDriverLocationTracking()
                    removeBookingStatusListener()
                    return
                }

                val pLat = booking.pickupLatitude
                val pLng = booking.pickupLongitude
                val dLat = booking.destinationLatitude
                val dLng = booking.destinationLongitude
                val driverLat = booking.driverLocation?.get("latitude") as? Double
                val driverLng = booking.driverLocation?.get("longitude") as? Double
                val locationDataAvailable = pLat != null && pLng != null && dLat != null && dLng != null

                val newState = when (booking.status) {
                    "SEARCHING" -> BookingUiState.FindingDriver("Finding a driver...")
                    "ACCEPTED", "EN_ROUTE_TO_PICKUP" -> {
                        if (locationDataAvailable) {
                            BookingUiState.DriverOnTheWay(
                                driverId = booking.driverId ?: "",
                                driverName = booking.driverName ?: "Your Driver",
                                driverPhone = booking.driverPhone,
                                vehicleDetails = booking.driverVehicleDetails ?: "-",
                                message = "Your driver is on the way.",
                                pickupLocation = LatLng(pLat!!, pLng!!),
                                dropOffLocation = LatLng(dLat!!, dLng!!),
                                driverLocation = if(driverLat != null && driverLng != null) LatLng(driverLat, driverLng) else null
                            )
                        } else {
                            BookingUiState.Error("Incomplete booking data from server.")
                        }
                    }
                    "ARRIVED_AT_PICKUP" -> {
                        if (locationDataAvailable) {
                            BookingUiState.DriverArrived(
                                driverId = booking.driverId ?: "",
                                driverName = booking.driverName ?: "Your Driver",
                                driverPhone = booking.driverPhone,
                                vehicleDetails = booking.driverVehicleDetails ?: "-",
                                message = "Your driver has arrived.",
                                pickupLocation = LatLng(pLat!!, pLng!!),
                                dropOffLocation = LatLng(dLat!!, dLng!!)
                            )
                        } else {
                            BookingUiState.Error("Incomplete booking data from server.")
                        }
                    }
                    "EN_ROUTE_TO_DROPOFF" -> {
                        if (locationDataAvailable) {
                            BookingUiState.TripInProgress(
                                driverId = booking.driverId ?: "",
                                driverName = booking.driverName ?: "Your Driver",
                                driverPhone = booking.driverPhone,
                                vehicleDetails = booking.driverVehicleDetails ?: "-",
                                message = "Trip in progress.",
                                pickupLocation = LatLng(pLat!!, pLng!!),
                                dropOffLocation = LatLng(dLat!!, dLng!!),
                                driverLocation = if(driverLat != null && driverLng != null) LatLng(driverLat, driverLng) else null
                            )
                        } else {
                            BookingUiState.Error("Incomplete booking data from server.")
                        }
                    }
                    else -> null
                }
                newState?.let { _uiState.postValue(it) }

                val driverId = booking.driverId
                val shouldTrack = booking.status in listOf("ACCEPTED", "EN_ROUTE_TO_PICKUP", "ARRIVED_AT_PICKUP", "EN_ROUTE_TO_DROPOFF")

                if (driverId != null && shouldTrack) {
                    if (driverId != trackedDriverId) {
                        startDriverLocationTracking(driverId)
                        trackedDriverId = driverId
                    }
                } else {
                    if (trackedDriverId != null) {
                        stopDriverLocationTracking()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                _uiState.postValue(BookingUiState.Error(error.message))
                stopDriverLocationTracking()
            }
        }
        reference.addValueEventListener(bookingStatusListener!!)
    }

    private fun startDriverLocationTracking(driverId: String) {
        stopDriverLocationTracking()
        Log.d(TAG, "Starting to track driver: $driverId")
        driverLocationTracker = viewModelScope.launch {
            val driverLocationRef = driversRef.child(driverId).child("location")
            while (isActive) {
                driverLocationRef.get().addOnSuccessListener { dataSnapshot ->
                    if (!isActive) return@addOnSuccessListener
                    val locationMap = dataSnapshot.value as? Map<String, Double>
                    val lat = locationMap?.get("latitude")
                    val lng = locationMap?.get("longitude")

                    if (lat != null && lng != null) {
                        updateStateWithDriverLocation(LatLng(lat, lng))
                    } else {
                        Log.w(TAG, "Driver location format is incorrect or null for driver: $driverId")
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get driver location for driver: $driverId", e)
                }
                delay(10000)
            }
        }
    }

    private fun stopDriverLocationTracking() {
        if (driverLocationTracker != null) {
            Log.d(TAG, "Stopping to track driver: $trackedDriverId")
            driverLocationTracker?.cancel()
            driverLocationTracker = null
            trackedDriverId = null
        }
    }

    private fun updateStateWithDriverLocation(driverLocation: LatLng) {
        val currentState = _uiState.value
        val newState = when (currentState) {
            is BookingUiState.DriverOnTheWay -> currentState.copy(driverLocation = driverLocation)
            is BookingUiState.TripInProgress -> currentState.copy(driverLocation = driverLocation)
            else -> null
        }
        newState?.let {
            if (it != currentState) {
                _uiState.postValue(it)
            }
        }
    }

    fun clearBookingState() {
        removeBookingStatusListener()
        stopDriverLocationTracking()
        currentBookingId = null
        _uiState.postValue(BookingUiState.Initial)
    }

    override fun onCleared() {
        super.onCleared()
        removeBookingStatusListener()
        stopDriverLocationTracking()
    }

    private fun removeBookingStatusListener() {
        bookingStatusListener?.let { listener ->
            currentBookingId?.let { bookingId ->
                try {
                    bookingRequestsRef.child(bookingId).removeEventListener(listener)
                    bookingStatusListener = null
                } catch (e: Exception) {
                    Log.w(TAG, "Listener removal failed, maybe the path was already gone.", e)
                }
            }
        }
    }
}