package com.btsi.swiftcab

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.functions.FirebaseFunctions

sealed class BookingUiState {
    object Initial : BookingUiState()
    data class FindingDriver(val message: String) : BookingUiState()
    data class DriverOnTheWay(
        val driverName: String,
        val vehicleDetails: String,
        val message: String,
        val pickupLocation: LatLng,
        val dropOffLocation: LatLng,
        val driverLocation: LatLng? // New field for live driver location
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
        val driverLocation: LatLng? // Also track during trip
    ) : BookingUiState()

    object TripCompleted : BookingUiState()
    data class Canceled(val message: String) : BookingUiState()
    data class Error(val message: String) : BookingUiState()
}

class BookingViewModel(
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions
) : ViewModel() {

    private val bookingRequestsRef: DatabaseReference = database.getReference("bookingRequests")

    private var currentBookingId: String? = null
    private var bookingStatusListener: ValueEventListener? = null

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

    private fun listenForBookingStatus(bookingId: String) {
        removeBookingStatusListener()
        currentBookingId = bookingId
        val reference = bookingRequestsRef.child(bookingId)
        _uiState.postValue(BookingUiState.FindingDriver("Finding a driver..."))

        bookingStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val booking = snapshot.getValue(BookingRequest::class.java)
                if (booking == null) {
                    _uiState.postValue(BookingUiState.Canceled("Booking resolved."))
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
                    "COMPLETED" -> BookingUiState.TripCompleted
                    "CANCELED", "NO_DRIVERS", "ERROR" -> {
                        val reason = booking.cancellationReason ?: booking.status
                        val message = when (reason) {
                            "NO_DRIVERS" -> "Sorry, no drivers were found near you."
                            "user_canceled" -> "You cancelled the booking."
                            else -> "Your booking was cancelled."
                        }
                        BookingUiState.Canceled(message)
                    }
                    else -> null
                }
                newState?.let { _uiState.postValue(it) }
            }

            override fun onCancelled(error: DatabaseError) {
                _uiState.postValue(BookingUiState.Error(error.message))
            }
        }
        reference.addValueEventListener(bookingStatusListener!!)
    }

    fun clearBookingState() {
        removeBookingStatusListener()
        currentBookingId = null
        _uiState.postValue(BookingUiState.Initial)
    }

    override fun onCleared() {
        super.onCleared()
        removeBookingStatusListener()
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
