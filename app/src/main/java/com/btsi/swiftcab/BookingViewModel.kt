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
import org.json.JSONObject
import java.net.URL
// (Reverted) No FieldValue import needed
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class BookingUiState {
    object Initial : BookingUiState()
    data class FindingDriver(val message: String, val expiresAt: Long? = null) : BookingUiState()
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
        val driverLocation: LatLng?,
        val tripStartedAt: Long?,
        val perMinuteRate: Double?,
        val fareBase: Double?
    ) : BookingUiState()

    data class AwaitingPayment(
        val bookingId: String,
        val driverId: String,
        val finalFare: Double?,
        val durationMinutes: Int?,
        val fareBase: Double?,
        val perKmRate: Double?,
        val perMinuteRate: Double?,
        val distanceKm: Double?,
        val paymentConfirmed: Boolean = false
    ) : BookingUiState()

    data class TripCompleted(
        val bookingId: String,
        val driverId: String,
        val finalFare: Double?,
        val durationMinutes: Int?,
        val fareBase: Double?,
        val perKmRate: Double?,
        val perMinuteRate: Double?,
        val distanceKm: Double?
    ) : BookingUiState()
    data class Canceled(val message: String) : BookingUiState()
    data class Error(val message: String) : BookingUiState()
}


class BookingViewModel(
    private val auth: FirebaseAuth,
    private val database: FirebaseDatabase,
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore, // For archiving
    private val googleMapsKey: String
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

    private val BASE_FARE = 50.0
    private val PER_KM_RATE = 13.5
    private val PER_MIN_RATE = 2.0

    /**
     * Computes great‑circle distance between two coordinates using haversine.
     */
    private fun calculateDistanceKm(a: LatLng, b: LatLng): Double {
        val R = 6371.0 // km
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val aa = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa))
        return R * c
    }

    /**
     * Creates a new booking request, consuming discount if applied, or resumes
     * an existing active booking for the rider.
     */
    fun createBooking(
        pickupLatLng: LatLng,
        destinationLatLng: LatLng,
        pickupAddress: String,
        destinationAddress: String,
        applyDiscount: Boolean,
        availableDiscountPercent: Int,
        precomputedDistanceKm: Double? = null,
        precomputedEstimatedFare: Double? = null
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

                        var distanceKm = 0.0
                        var estimatedFare = 0.0
                        val hasPrecomputed = (precomputedDistanceKm != null && precomputedDistanceKm > 0.0) && (precomputedEstimatedFare != null && precomputedEstimatedFare > 0.0)
                        if (hasPrecomputed) {
                            distanceKm = precomputedDistanceKm!!
                            estimatedFare = precomputedEstimatedFare!!
                        } else {
                            try {
                                val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${pickupLatLng.latitude},${pickupLatLng.longitude}&destination=${destinationLatLng.latitude},${destinationLatLng.longitude}&mode=driving&key=$googleMapsKey"
                                val result = URL(url).readText()
                                val jsonObject = JSONObject(result)
                                val routes = jsonObject.getJSONArray("routes")
                                if (routes.length() > 0) {
                                    val legs = routes.getJSONObject(0).getJSONArray("legs")
                                    if (legs.length() > 0) {
                                        val meters = legs.getJSONObject(0).getJSONObject("distance").getInt("value")
                                        distanceKm = meters / 1000.0
                                    }
                                }
                                estimatedFare = BASE_FARE + PER_KM_RATE * distanceKm
                            } catch (_: Exception) {
                                distanceKm = calculateDistanceKm(pickupLatLng, destinationLatLng)
                                estimatedFare = BASE_FARE + PER_KM_RATE * distanceKm
                            }
                            val appliedDiscountInternal = if (applyDiscount && availableDiscountPercent > 0) availableDiscountPercent else 0
                            if (appliedDiscountInternal > 0) {
                                val discountFactor = 1.0 - (appliedDiscountInternal / 100.0)
                                estimatedFare *= discountFactor
                            }
                        }
                        val appliedDiscount = if (applyDiscount && availableDiscountPercent > 0) availableDiscountPercent else 0

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
                            cancellationReason = null,
                            estimatedFare = estimatedFare,
                            distanceKm = distanceKm,
                            fareBase = BASE_FARE,
                            perKmRate = PER_KM_RATE,
                            perMinuteRate = PER_MIN_RATE,
                            appliedDiscountPercent = appliedDiscount
                        )
                        bookingRequestsRef.child(bookingId).setValue(bookingRequest)
                            .addOnSuccessListener {
                                // Consume the pending discount only if rider chose to use it
                                if (appliedDiscount > 0) {
                                    firestore.collection("users").document(riderId)
                                        .update(mapOf("nextBookingDiscountPercent" to 0))
                                }
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

    /**
     * Requests cancellation of the current active booking via Cloud Function.
     */
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

    /**
     * Prepares an archive record for booking history; kept client‑side for parity
     * but actual archiving is handled by a Cloud Function.
     */
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
                "driverRated" to false,
                // Added fare and payment details so amount is visible in history
                "finalFare" to (booking.finalFare ?: booking.estimatedFare ?: 0.0),
                "durationMinutes" to (booking.durationMinutes ?: 0),
                "fareBase" to (booking.fareBase ?: 0.0),
                "perKmRate" to (booking.perKmRate ?: 0.0),
                "perMinuteRate" to (booking.perMinuteRate ?: 0.0),
                "distanceKm" to (booking.distanceKm ?: 0.0),
                "paymentConfirmed" to (booking.paymentConfirmed ?: true)
            )
            // Client should not write to bookinghistory directly; rules forbid it.
            // Archiving is handled by Cloud Function on status=COMPLETED.
            Log.d(TAG, "Archive skipped on client; handled server-side.")
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving booking", e)
        }
    }

    /**
     * Subscribes to realtime booking status updates and maps them to UI state.
     */
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
                        _uiState.postValue(
                            BookingUiState.TripCompleted(
                                bookingId = booking.bookingId!!,
                                driverId = driverIdFromBooking ?: "",
                                finalFare = booking.finalFare,
                                durationMinutes = booking.durationMinutes,
                                fareBase = booking.fareBase,
                                perKmRate = booking.perKmRate,
                                perMinuteRate = booking.perMinuteRate,
                                distanceKm = booking.distanceKm
                            )
                        )
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
                    "SEARCHING" -> {
                        val expiresAt = snapshot.child("expiresAt").getValue(Long::class.java)
                        BookingUiState.FindingDriver("Finding a driver...", expiresAt)
                    }
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
                                driverLocation = if(driverLat != null && driverLng != null) LatLng(driverLat, driverLng) else null,
                                tripStartedAt = booking.tripStartedAt,
                                perMinuteRate = booking.perMinuteRate,
                                fareBase = booking.fareBase
                            )
                        } else {
                            BookingUiState.Error("Incomplete booking data from server.")
                        }
                    }
                    "AWAITING_PAYMENT" -> {
                        BookingUiState.AwaitingPayment(
                            bookingId = booking.bookingId!!,
                            driverId = booking.driverId ?: "",
                            finalFare = booking.finalFare,
                            durationMinutes = booking.durationMinutes,
                            fareBase = booking.fareBase,
                            perKmRate = booking.perKmRate,
                            perMinuteRate = booking.perMinuteRate,
                            distanceKm = booking.distanceKm
                        )
                    }
                    // NEW: handle completed to show summary and rating dialog
                    "COMPLETED" -> {
                        BookingUiState.TripCompleted(
                            bookingId = booking.bookingId!!,
                            driverId = booking.driverId ?: "",
                            finalFare = booking.finalFare,
                            durationMinutes = booking.durationMinutes,
                            fareBase = booking.fareBase,
                            perKmRate = booking.perKmRate,
                            perMinuteRate = booking.perMinuteRate,
                            distanceKm = booking.distanceKm
                        )
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

    /**
     * Starts polling the driver’s location and updates UI state.
     */
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

    /**
     * Stops driver location polling and clears tracking state.
     */
    private fun stopDriverLocationTracking() {
        if (driverLocationTracker != null) {
            Log.d(TAG, "Stopping to track driver: $trackedDriverId")
            driverLocationTracker?.cancel()
            driverLocationTracker = null
            trackedDriverId = null
        }
    }

    /**
     * Produces a new UI state with updated driver location when applicable.
     */
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

    /**
     * Clears listeners and resets UI state to Initial.
     */
    fun clearBookingState() {
        removeBookingStatusListener()
        stopDriverLocationTracking()
        currentBookingId = null
        _uiState.postValue(BookingUiState.Initial)
    }

    /**
     * Lifecycle callback: ensure listeners and trackers are removed.
     */
    override fun onCleared() {
        super.onCleared()
        removeBookingStatusListener()
        stopDriverLocationTracking()
    }

    /**
     * Safely removes the current booking status listener if present.
     */
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

    /**
     * Attempts to resume an active booking for the current rider.
     */
    fun resumeActiveBookingIfAny() {
        val riderId = auth.currentUser?.uid ?: return
        val terminalStatuses = setOf("COMPLETED", "CANCELED", "NO_DRIVERS", "ERROR")
        bookingRequestsRef.orderByChild("riderId").equalTo(riderId).get()
            .addOnSuccessListener { snapshot ->
                var existingActiveBookingId: String? = null
                snapshot.children.forEach { child ->
                    val req = child.getValue(BookingRequest::class.java)
                    val status = req?.status
                    if (status != null && status !in terminalStatuses) {
                        existingActiveBookingId = req?.bookingId ?: child.key
                    }
                }
                existingActiveBookingId?.let { id ->
                    _uiState.postValue(BookingUiState.FindingDriver("Resuming your active booking..."))
                    listenForBookingStatus(id)
                }
            }
            .addOnFailureListener {
                // Best-effort; ignore failures
            }
    }
}

// Rename duplicate legacy class to avoid redeclaration
class BookingViewModelLegacy(
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

    private val BASE_FARE = 50.0
    private val PER_KM_RATE = 13.5
    private val PER_MIN_RATE = 2.0

    private fun calculateDistanceKm(a: LatLng, b: LatLng): Double {
        val R = 6371.0 // km
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val aa = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa))
        return R * c
    }

    fun createBooking(
        pickupLatLng: LatLng,
        destinationLatLng: LatLng,
        pickupAddress: String,
        destinationAddress: String,
        applyDiscount: Boolean,
        availableDiscountPercent: Int
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
                    _uiState.postValue(BookingUiState.FindingDriver("Resuming your active booking..."))
                    listenForBookingStatus(existingActiveBookingId!!)
                    return@addOnSuccessListener
                }
            }
            .addOnFailureListener {
                // Ignore silently; resume is best-effort
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
                "driverRated" to false,
                // Added fare and payment details so amount is visible in history
                "finalFare" to (booking.finalFare ?: booking.estimatedFare ?: 0.0),
                "durationMinutes" to (booking.durationMinutes ?: 0),
                "fareBase" to (booking.fareBase ?: 0.0),
                "perKmRate" to (booking.perKmRate ?: 0.0),
                "perMinuteRate" to (booking.perMinuteRate ?: 0.0),
                "distanceKm" to (booking.distanceKm ?: 0.0),
                "paymentConfirmed" to (booking.paymentConfirmed ?: true)
            )
            // Client should not write to bookinghistory directly; rules forbid it.
            // Archiving is handled by Cloud Function on status=COMPLETED.
            Log.d(TAG, "Archive skipped on client; handled server-side.")
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
                        _uiState.postValue(
                            BookingUiState.TripCompleted(
                                bookingId = booking.bookingId!!,
                                driverId = driverIdFromBooking ?: "",
                                finalFare = booking.finalFare,
                                durationMinutes = booking.durationMinutes,
                                fareBase = booking.fareBase,
                                perKmRate = booking.perKmRate,
                                perMinuteRate = booking.perMinuteRate,
                                distanceKm = booking.distanceKm
                            )
                        )
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
                    "SEARCHING" -> {
                        val expiresAt = snapshot.child("expiresAt").getValue(Long::class.java)
                        BookingUiState.FindingDriver("Finding a driver...", expiresAt)
                    }
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
                                driverLocation = if(driverLat != null && driverLng != null) LatLng(driverLat, driverLng) else null,
                                tripStartedAt = booking.tripStartedAt,
                                perMinuteRate = booking.perMinuteRate,
                                fareBase = booking.fareBase
                            )
                        } else {
                            BookingUiState.Error("Incomplete booking data from server.")
                        }
                    }
                    "AWAITING_PAYMENT" -> {
                        BookingUiState.AwaitingPayment(
                            bookingId = booking.bookingId!!,
                            driverId = booking.driverId ?: "",
                            finalFare = booking.finalFare,
                            durationMinutes = booking.durationMinutes,
                            fareBase = booking.fareBase,
                            perKmRate = booking.perKmRate,
                            perMinuteRate = booking.perMinuteRate,
                            distanceKm = booking.distanceKm
                        )
                    }
                    // NEW: legacy mapping for completed
                    "COMPLETED" -> {
                        BookingUiState.TripCompleted(
                            bookingId = booking.bookingId!!,
                            driverId = booking.driverId ?: "",
                            finalFare = booking.finalFare,
                            durationMinutes = booking.durationMinutes,
                            fareBase = booking.fareBase,
                            perKmRate = booking.perKmRate,
                            perMinuteRate = booking.perMinuteRate,
                            distanceKm = booking.distanceKm
                        )
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
