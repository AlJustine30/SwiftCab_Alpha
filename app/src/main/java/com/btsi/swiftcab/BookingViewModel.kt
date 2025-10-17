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
        val driverName: String,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng,
        val driverLocation: LatLng?
    ) : BookingUiState()

    data class DriverArrived(
        val driverName: String,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng
    ) : BookingUiState()

    data class TripInProgress(
        val driverName: String,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng,
        val driverLocation: LatLng?
    ) : BookingUiState()

    object TripCompleted : BookingUiState()
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

        val bookingId = bookingRequestsRef.push().key
        if (bookingId == null) {
            _uiState.postValue(BookingUiState.Error("Could not generate booking ID."))
            return
        }

        val bookingRequest = BookingRequest(
            bookingId = bookingId,
            riderId = riderId,
            riderName = auth.currentUser?.displayName ?: "Unknown Rider",
            pickupLatitude = pickupLatLng.latitude,
            pickupLongitude = pickupLatLng.longitude,
            pickupAddress = pickupAddress,
            destinationLatitude = destinationLatLng.latitude,
            destinationLongitude = destinationLatLng.longitude,
            destinationAddress = destinationAddress,
            status = "SEARCHING",
            timestamp = System.currentTimeMillis()
        )

        bookingRequestsRef.child(bookingId).setValue(bookingRequest).addOnSuccessListener {
            currentBookingId = bookingId
            listenForBookingStatus(bookingId)
        }.addOnFailureListener { e ->
            _uiState.postValue(BookingUiState.Error("Booking failed: ${e.message}"))
        }
    }

    fun cancelBooking() {
        val bookingId = currentBookingId
        if (bookingId == null) {
            _uiState.postValue(BookingUiState.Error("No active booking to cancel."))
            return
        }

        functions.getHttpsCallable("cancelBooking").call(hashMapOf("bookingId" to bookingId)).addOnSuccessListener {
            Log.d(TAG, "cancelBooking function called successfully.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to call cancelBooking", e)
            _uiState.postValue(BookingUiState.Error("Cancellation failed. Check connection."))
        }
    }

    private fun saveCompletedBookingToHistory(booking: BookingRequest, riderId: String, driverId: String?) {
        val bookingId = booking.bookingId
        if (bookingId == null) {
            Log.e(TAG, "Cannot save booking to history, bookingId is null")
            return
        }

        val bookingToSave = booking.copy(riderId = riderId, driverId = driverId)

        firestore.collection("bookinghistory").document(bookingId)
            .set(bookingToSave)
            .addOnSuccessListener {
                Log.d(TAG, "Booking $bookingId successfully saved to history.")
                bookingRequestsRef.child(bookingId).removeValue()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save booking $bookingId to history.", e)
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

                // --- Definitive Fix: Handle terminal states first, using the booking snapshot as the source of truth --- 

                val isTerminalState = booking.status in listOf("COMPLETED", "CANCELED", "NO_DRIVERS", "ERROR")

                if (isTerminalState) {
                    if (booking.status == "COMPLETED") {
                        val riderId = auth.currentUser?.uid
                        val driverIdFromBooking = booking.driverId // Use the ID from the snapshot, not a state variable

                        Log.d(TAG, "Trip completed. Saving history. Rider ID: $riderId, Driver ID from snapshot: $driverIdFromBooking")

                        if (riderId != null && driverIdFromBooking != null) {
                            saveCompletedBookingToHistory(booking, riderId, driverIdFromBooking)
                        } else {
                            Log.e(TAG, "Could not save history for booking ${booking.bookingId}. RiderID or DriverID from snapshot was null.")
                        }
                        _uiState.postValue(BookingUiState.TripCompleted)
                    } else {
                        // Handle Canceled, No Drivers, etc.
                        val reason = booking.cancellationReason ?: booking.status
                        val message = when (reason) {
                            "NO_DRIVERS" -> "Sorry, no drivers were found near you."
                            "user_canceled" -> "You cancelled the booking."
                            else -> "Your booking was cancelled."
                        }
                        _uiState.postValue(BookingUiState.Canceled(message))
                    }

                    // Clean up for all terminal states
                    stopDriverLocationTracking()
                    removeBookingStatusListener()
                    return // Stop all further processing
                }

                // --- Handle active, ongoing trip states ---

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
                                driverName = booking.driverName ?: "Your Driver",
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
                                driverName = booking.driverName ?: "Your Driver",
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
                                driverName = booking.driverName ?: "Your Driver",
                                vehicleDetails = booking.driverVehicleDetails ?: "-",
                                message = "Your trip is in progress.",
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

                // --- Manage driver location tracking for active states ---
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
        stopDriverLocationTracking() // Stop any previous tracking
        Log.d(TAG, "Starting to track driver: $driverId")
        driverLocationTracker = viewModelScope.launch {
            val driverLocationRef = driversRef.child(driverId).child("location")
            while (isActive) {
                driverLocationRef.get().addOnSuccessListener { dataSnapshot ->
                    if (!isActive) return@addOnSuccessListener // Coroutine might have been cancelled
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
                delay(10000) // Poll every 10 seconds
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
            else -> null // Don't update if in a different state (e.g., Arrived)
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
