package com.btsi.swiftcabalpha

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.btsi.swiftcabalpha.models.BookingRequest // Corrected import
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.IOException
import java.util.Locale

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private companion object {
        private const val TAG = "BookingActivity"
        private const val DEFAULT_ZOOM = 15f
        private val PHILIPPINES_BOUNDS = RectangularBounds.newInstance(
            LatLng(4.0, 116.0), // SW corner
            LatLng(21.0, 127.0)  // NE corner
        )
        // Booking Status Constants
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_ACCEPTED = "ACCEPTED"
        private const val STATUS_ON_TRIP = "ON_TRIP"
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val STATUS_CANCELLED_BY_RIDER = "CANCELLED_BY_RIDER"
        private const val STATUS_CANCELLED_BY_DRIVER = "CANCELLED_BY_DRIVER"
        private const val STATUS_NO_DRIVERS_FOUND = "NO_DRIVERS_FOUND"
    }

    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder

    private lateinit var pickupLocationEditText: EditText
    private lateinit var destinationEditText: EditText

    private lateinit var buttonConfirmBooking: Button
    private lateinit var buttonCurrentLocationPickup: ImageView
    private lateinit var buttonCurrentLocationDropoff: ImageView
    private lateinit var backButton: ImageView
    private lateinit var buttonSelectPickupMode: Button
    private lateinit var buttonSelectDropoffMode: Button

    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var pickupLocationLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null

    private var isPickupMode = true // true for pickup, false for dropoff

    private lateinit var progressBarWait: ProgressBar
    private lateinit var noDriversLayout: LinearLayout
    private lateinit var buttonShowOnMap: Button
    private lateinit var textViewNoDriversMessage: TextView
    private lateinit var mainBookingLayout: androidx.constraintlayout.widget.ConstraintLayout

    private lateinit var findingDriverOverlayLayout: LinearLayout
    private lateinit var progressBarInFindingOverlay: ProgressBar
    private lateinit var textViewInFindingOverlay: TextView

    // UI Elements for Booking Status Card
    private lateinit var infoCardView: MaterialCardView // The original input card
    private lateinit var bookingStatusCardView: MaterialCardView
    private lateinit var textViewBookingStatusHeader: TextView
    private lateinit var textViewDriverNameStatus: TextView
    private lateinit var textViewVehicleDetailsStatus: TextView
    private lateinit var textViewTripStatusMessage: TextView
    private lateinit var buttonCancelRideRider: Button

    // Firebase
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var bookingRequestsRef: DatabaseReference
    private var currentBookingId: String? = null
    private var bookingStatusListener: ValueEventListener? = null


    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
                    permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                Log.d(TAG, "Location permission granted after request.")
                enableMyLocationOnMap()
            }
            else -> {
                Log.d(TAG, "Location permission denied after request.")
                Toast.makeText(this, "Location permission denied. Map functionality limited.", Toast.LENGTH_LONG).show()
                setupMapWithDefaultLocation()
            }
        }
    }

    private fun getBitmapDescriptorFromVector(context: Context, @DrawableRes vectorResId: Int): BitmapDescriptor? {
        return ContextCompat.getDrawable(context, vectorResId)?.let { vectorDrawable ->
            vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
            val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            vectorDrawable.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key), Locale.getDefault())
        }

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        bookingRequestsRef = database.getReference("booking_requests")

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragmentContainerView) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Initialize UI Views
        infoCardView = findViewById(R.id.infoCardView)
        bookingStatusCardView = findViewById(R.id.bookingStatusCardView)
        textViewBookingStatusHeader = findViewById(R.id.textViewBookingStatusHeader)
        textViewDriverNameStatus = findViewById(R.id.textViewDriverNameStatus)
        textViewVehicleDetailsStatus = findViewById(R.id.textViewVehicleDetailsStatus)
        textViewTripStatusMessage = findViewById(R.id.textViewTripStatusMessage)
        buttonCancelRideRider = findViewById(R.id.buttonCancelRideRider)

        findingDriverOverlayLayout = findViewById(R.id.findingDriverLayout)
        progressBarInFindingOverlay = findViewById(R.id.progressBarFindingDriver)
        textViewInFindingOverlay = findViewById(R.id.textViewFindingDriver)

        pickupLocationEditText = findViewById(R.id.pickupLocationEditText)
        destinationEditText = findViewById(R.id.destinationEditText)
        buttonConfirmBooking = findViewById(R.id.buttonConfirmBooking)
        buttonCurrentLocationPickup = findViewById(R.id.buttonCurrentLocationPickup)
        buttonCurrentLocationDropoff = findViewById(R.id.buttonCurrentLocationDropoff)
        backButton = findViewById(R.id.back_button_booking)

        buttonSelectPickupMode = findViewById(R.id.buttonSelectPickupMode)
        buttonSelectDropoffMode = findViewById(R.id.buttonSelectDropoffMode)

        progressBarWait = findViewById(R.id.progressBarWait)
        noDriversLayout = findViewById(R.id.noDriversLayout)
        buttonShowOnMap = findViewById(R.id.buttonShowOnMap) // Ensure this ID exists if used
        textViewNoDriversMessage = findViewById(R.id.textViewNoDriversMessage)
        mainBookingLayout = findViewById(R.id.mainBookingLayout)

        setupUI()
        checkLocationPermissionAndSetupMap()
    }

    @SuppressLint("PotentialBehaviorOverride")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        Log.d(TAG, "Map is ready.")

        mMap?.uiSettings?.isZoomControlsEnabled = true
        mMap?.uiSettings?.isMyLocationButtonEnabled = false // Assuming custom button for this

        mMap?.setOnMapClickListener { latLng ->
            val title = if (isPickupMode) "Tapped Pickup Location" else "Tapped Destination Location"
            updateMapLocation(latLng, title, isPickupMode)
        }
        mMap?.setOnMarkerClickListener { marker ->
            true
        }
        enableMyLocationOnMap()
    }

    private fun setupUI() {
        setupAutocompleteFragments()

        pickupLocationEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) isPickupMode = true
        }
        destinationEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) isPickupMode = false
        }

        buttonCurrentLocationPickup.setOnClickListener {
            getCurrentLocationAndSet(true)
        }
        buttonCurrentLocationDropoff.setOnClickListener {
            getCurrentLocationAndSet(false)
        }

        buttonSelectPickupMode.setOnClickListener {
            isPickupMode = true
            val pickupAutocompleteFragment =
                supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment
            pickupAutocompleteFragment.view?.findViewById<EditText>(com.google.android.libraries.places.R.id.places_autocomplete_search_input)?.requestFocus()
            Toast.makeText(this, "Tap on map to set PICKUP location or search above.", Toast.LENGTH_SHORT).show()
        }

        buttonSelectDropoffMode.setOnClickListener {
            isPickupMode = false
            val destinationAutocompleteFragment =
                supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
            destinationAutocompleteFragment.view?.findViewById<EditText>(com.google.android.libraries.places.R.id.places_autocomplete_search_input)?.requestFocus()
            Toast.makeText(this, "Tap on map to set DESTINATION location or search above.", Toast.LENGTH_SHORT).show()
        }

        buttonConfirmBooking.setOnClickListener {
            Log.d(TAG, "buttonConfirmBooking clicked")
            val riderId = firebaseAuth.currentUser?.uid
            if (riderId == null) {
                Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Confirm booking attempt but user not logged in.")
                return@setOnClickListener
            }

            if (pickupLocationLatLng != null && destinationLatLng != null &&
                pickupLocationEditText.text.isNotBlank() && destinationEditText.text.isNotBlank()
            ) {
                val newBookingId = bookingRequestsRef.push().key
                if (newBookingId == null) {
                    Toast.makeText(this, "Could not create booking request. Please try again.", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Failed to generate bookingId from Firebase.")
                    return@setOnClickListener
                }

                currentBookingId = newBookingId

                val bookingRequest = BookingRequest(
                    bookingId = newBookingId,
                    riderId = riderId,
                    pickupLatitude = pickupLocationLatLng!!.latitude,
                    pickupLongitude = pickupLocationLatLng!!.longitude,
                    pickupAddress = pickupLocationEditText.text.toString(),
                    destinationLatitude = destinationLatLng!!.latitude,
                    destinationLongitude = destinationLatLng!!.longitude,
                    destinationAddress = destinationEditText.text.toString(),
                    status = STATUS_PENDING,
                    timestamp = System.currentTimeMillis()
                )

                Log.d(TAG, "Attempting to submit booking: $bookingRequest")
                infoCardView.visibility = View.GONE
                findingDriverOverlayLayout.visibility = View.VISIBLE
                textViewInFindingOverlay.text = getString(R.string.submitting_request)
                progressBarInFindingOverlay.visibility = View.VISIBLE
                noDriversLayout.visibility = View.GONE

                bookingRequestsRef.child(newBookingId).setValue(bookingRequest)
                    .addOnSuccessListener {
                        Log.d(TAG, "Booking request submitted successfully. ID: $newBookingId")
                        textViewInFindingOverlay.text = getString(R.string.finding_driver_message)
                        listenForBookingUpdates(newBookingId)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to submit booking request", e)
                        findingDriverOverlayLayout.visibility = View.GONE
                        infoCardView.visibility = View.VISIBLE
                        Toast.makeText(this@BookingActivity, "Booking failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }

            } else {
                Toast.makeText(this@BookingActivity, "Please select both pickup and destination locations.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Confirm booking attempt with missing location data. Pickup LatLng is null: ${pickupLocationLatLng == null}, Dest LatLng is null: ${destinationLatLng == null}, Pickup text blank: ${pickupLocationEditText.text.isBlank()}, Dest text blank: ${destinationEditText.text.isBlank()}")
            }
        }

        backButton.setOnClickListener {
            if (currentBookingId != null &&
                (findingDriverOverlayLayout.visibility == View.VISIBLE || bookingStatusCardView.visibility == View.VISIBLE)) {
                // Allow cancellation if looking for driver or if ride is confirmed but not started (depends on buttonCancelRideRider logic)
                // For now, simple back press might just cancel if in PENDING state during search
                if (textViewInFindingOverlay.text.toString() == getString(R.string.finding_driver_message) ||
                    textViewInFindingOverlay.text.toString() == getString(R.string.still_searching_driver)) {
                    cancelBookingRequest(STATUS_CANCELLED_BY_RIDER)
                }
            }
            finish()
        }
        buttonCancelRideRider.setOnClickListener {
            // Implement cancellation logic if ride is ACCEPTED or ON_TRIP (if allowed)
            // For now, let's assume it cancels if ride is accepted but not yet ON_TRIP
            if (currentBookingId != null && bookingStatusCardView.visibility == View.VISIBLE) {
                 cancelBookingRequest(STATUS_CANCELLED_BY_RIDER) // Or a specific status like CANCELLED_AFTER_ACCEPTANCE
            }
        }

        // Initial UI states
        progressBarWait.visibility = View.GONE
        noDriversLayout.visibility = View.GONE
        findingDriverOverlayLayout.visibility = View.GONE
        bookingStatusCardView.visibility = View.GONE
        infoCardView.visibility = View.VISIBLE
    }

    private fun listenForBookingUpdates(bookingId: String) {
        removeBookingStatusListener() // Ensure no old listeners are active

        Log.d(TAG, "Attaching listener for booking ID: $bookingId")
        bookingStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.w(TAG, "Booking data no longer exists for ID: $bookingId. It might have been cancelled or completed uncleanly.")
                    if (isFinishing || isDestroyed) return
                    Toast.makeText(this@BookingActivity, "Booking not found. It might have been cancelled.", Toast.LENGTH_LONG).show()
                    resetToInitialBookingState()
                    return
                }

                val bookingRequest = snapshot.getValue(BookingRequest::class.java)
                if (bookingRequest == null) {
                    Log.e(TAG, "Failed to parse booking request from snapshot for ID: $bookingId")
                    return
                }

                Log.d(TAG, "Booking update for $bookingId: Status - ${bookingRequest.status}, Driver - ${bookingRequest.driverId}")

                currentBookingId = bookingRequest.bookingId

                if (isFinishing || isDestroyed) {
                    Log.d(TAG, "Activity is finishing, not updating UI for booking status: ${bookingRequest.status}")
                    return
                }

                when (bookingRequest.status) {
                    STATUS_PENDING -> {
                        infoCardView.visibility = View.GONE
                        bookingStatusCardView.visibility = View.GONE
                        findingDriverOverlayLayout.visibility = View.VISIBLE
                        textViewInFindingOverlay.text = getString(R.string.still_searching_driver)
                        progressBarInFindingOverlay.visibility = View.VISIBLE
                        noDriversLayout.visibility = View.GONE
                    }
                    STATUS_ACCEPTED -> {
                        findingDriverOverlayLayout.visibility = View.GONE
                        infoCardView.visibility = View.GONE
                        noDriversLayout.visibility = View.GONE
                        bookingStatusCardView.visibility = View.VISIBLE

                        textViewBookingStatusHeader.text = getString(R.string.status_header_ride_confirmed)
                        textViewDriverNameStatus.text = getString(R.string.driver_info_prefix, bookingRequest.driverName ?: "N/A")
                        textViewVehicleDetailsStatus.text = getString(R.string.vehicle_info_prefix, bookingRequest.driverVehicleDetails ?: "N/A")
                        textViewTripStatusMessage.text = getString(R.string.status_message_driver_en_route)
                        buttonCancelRideRider.visibility = View.VISIBLE // Allow cancellation at this stage
                        Log.d(TAG, "Driver ${bookingRequest.driverId} accepted. Name: ${bookingRequest.driverName}, Vehicle: ${bookingRequest.driverVehicleDetails}")
                    }
                    STATUS_ON_TRIP -> {
                        findingDriverOverlayLayout.visibility = View.GONE
                        infoCardView.visibility = View.GONE
                        noDriversLayout.visibility = View.GONE
                        bookingStatusCardView.visibility = View.VISIBLE

                        textViewBookingStatusHeader.text = getString(R.string.status_header_trip_in_progress)
                        textViewDriverNameStatus.text = getString(R.string.driver_info_prefix, bookingRequest.driverName ?: "N/A")
                        textViewVehicleDetailsStatus.text = getString(R.string.vehicle_info_prefix, bookingRequest.driverVehicleDetails ?: "N/A")
                        textViewTripStatusMessage.text = getString(R.string.status_message_trip_en_route)
                        buttonCancelRideRider.visibility = View.GONE // Typically cannot cancel once trip starts
                        Log.d(TAG, "Trip is ON_TRIP with driver ${bookingRequest.driverId}")
                    }
                    STATUS_NO_DRIVERS_FOUND -> {
                        findingDriverOverlayLayout.visibility = View.GONE
                        bookingStatusCardView.visibility = View.GONE
                        infoCardView.visibility = View.GONE // Keep inputs hidden, show noDriversLayout
                        noDriversLayout.visibility = View.VISIBLE
                        textViewNoDriversMessage.text = getString(R.string.no_drivers_found_message)
                        resetToInitialBookingState() // Resets currentBookingId and listener
                    }
                    STATUS_CANCELLED_BY_RIDER, STATUS_CANCELLED_BY_DRIVER -> {
                        findingDriverOverlayLayout.visibility = View.GONE
                        bookingStatusCardView.visibility = View.GONE
                        infoCardView.visibility = View.VISIBLE // Allow re-booking
                        val message = if (bookingRequest.status == STATUS_CANCELLED_BY_RIDER) getString(R.string.booking_cancelled_by_rider_message) else getString(R.string.driver_cancelled_booking)
                        Toast.makeText(this@BookingActivity, message, Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Booking $bookingId was cancelled. Status: ${bookingRequest.status}")
                        resetToInitialBookingState()
                    }
                    STATUS_COMPLETED -> {
                        findingDriverOverlayLayout.visibility = View.GONE
                        bookingStatusCardView.visibility = View.GONE
                        infoCardView.visibility = View.VISIBLE // Allow new booking
                        Toast.makeText(this@BookingActivity, getString(R.string.booking_completed_message), Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Booking $bookingId was completed.")
                        archiveBookingToHistory(bookingRequest)
                        resetToInitialBookingState()
                    }
                    else -> {
                        Log.w(TAG, "Unhandled booking status: ${bookingRequest.status}")
                        Toast.makeText(this@BookingActivity, getString(R.string.unknown_booking_status_message), Toast.LENGTH_SHORT).show()
                        // Potentially reset or show a generic error UI
                        findingDriverOverlayLayout.visibility = View.GONE
                        bookingStatusCardView.visibility = View.GONE
                        infoCardView.visibility = View.VISIBLE
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase booking listener was cancelled for ID $bookingId", error.toException())
                 if (isFinishing || isDestroyed) return
                if (error.code != DatabaseError.PERMISSION_DENIED || currentBookingId != null) {
                    Toast.makeText(this@BookingActivity, "Error listening for booking updates.", Toast.LENGTH_SHORT).show()
                }
                 // Reset to a state where user can try again or exit
                findingDriverOverlayLayout.visibility = View.GONE
                bookingStatusCardView.visibility = View.GONE
                infoCardView.visibility = View.VISIBLE 
                noDriversLayout.visibility = View.GONE
            }
        }
        bookingRequestsRef.child(bookingId).addValueEventListener(bookingStatusListener!!)
    }

    private fun removeBookingStatusListener() {
        if (bookingStatusListener != null && currentBookingId != null) {
            Log.d(TAG, "Removing listener for booking ID: $currentBookingId")
            bookingRequestsRef.child(currentBookingId!!).removeEventListener(bookingStatusListener!!)
            bookingStatusListener = null
        }
    }

    private fun cancelBookingRequest(newStatus: String) {
        if (currentBookingId != null) {
            Log.d(TAG, "Attempting to cancel booking request: $currentBookingId with status $newStatus by rider action")
            bookingRequestsRef.child(currentBookingId!!)
                .child("status").setValue(newStatus)
                .addOnSuccessListener {
                    Log.d(TAG, "Booking $currentBookingId marked as $newStatus in Firebase.")
                    // UI update will be handled by the listener. If listener is somehow detached, Toast here.
                    if (bookingStatusListener == null) {
                         Toast.makeText(this@BookingActivity, "Booking Cancelled", Toast.LENGTH_LONG).show()
                         resetToInitialBookingState()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to mark booking $currentBookingId as $newStatus.", e)
                    Toast.makeText(this, "Failed to cancel booking. Please check connection.", Toast.LENGTH_SHORT).show()
                }
            // Do not immediately call resetToInitialBookingState() here, let the listener handle UI transition
        } else {
            Log.w(TAG, "cancelBookingRequest called but no currentBookingId")
        }
    }
    private fun resetToInitialBookingState() {
        Log.d(TAG, "Resetting to initial booking state.")
        removeBookingStatusListener()
        currentBookingId = null

        // Hide overlays and status card
        findingDriverOverlayLayout.visibility = View.GONE
        bookingStatusCardView.visibility = View.GONE
        noDriversLayout.visibility = View.GONE

        // Show booking input form
        infoCardView.visibility = View.VISIBLE

        // Clear map markers and input fields
        pickupMarker?.remove()
        destinationMarker?.remove()
        pickupMarker = null
        destinationMarker = null
        pickupLocationLatLng = null
        destinationLatLng = null
        pickupLocationEditText.setText("")
        destinationEditText.setText("")

        // Reset autocomplete fragments if they exist
        try {
            val pickupAutocompleteFragment = supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment?
            pickupAutocompleteFragment?.setText("")
            val destinationAutocompleteFragment = supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment?
            destinationAutocompleteFragment?.setText("")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting autocomplete fragments", e)
        }

        isPickupMode = true // Reset focus to pickup
        pickupLocationEditText.requestFocus()
    }

    private fun archiveBookingToHistory(booking: BookingRequest) {
        val riderId = firebaseAuth.currentUser?.uid
        if (riderId == null) {
            Log.e(TAG, "Cannot archive booking, riderId is null.")
            return
        }
        if (booking.bookingId.isBlank()) {
            Log.e(TAG, "Cannot archive booking, bookingId is blank.")
            return
        }

        Log.d(TAG, "Archiving booking ${booking.bookingId} to history for rider $riderId")
        Toast.makeText(this, getString(R.string.archiving_booking_to_history), Toast.LENGTH_SHORT).show()

        val historyRef = database.getReference("Users/Riders/$riderId/booking_history/${booking.bookingId}")
        historyRef.setValue(booking)
            .addOnSuccessListener {
                Log.d(TAG, "Booking ${booking.bookingId} successfully archived to history.")
                Toast.makeText(this, getString(R.string.booking_archived_successfully), Toast.LENGTH_SHORT).show()
                // Optionally, delete from main booking_requests after successful archival
                // For now, we will not delete to keep it simple and allow review if needed.
                // bookingRequestsRef.child(booking.bookingId).removeValue()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to archive booking ${booking.bookingId} to history.", e)
                Toast.makeText(this, getString(R.string.failed_to_archive_booking), Toast.LENGTH_LONG).show()
            }
    }


    override fun onDestroy() {
        super.onDestroy()
        removeBookingStatusListener()
    }


    private fun setupAutocompleteFragments() {
        val pickupAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment
        val destinationAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment

        pickupAutocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
        pickupAutocompleteFragment.setHint("Enter Pickup Location")

        pickupAutocompleteFragment.setCountry("PH")
        pickupAutocompleteFragment.setLocationBias(PHILIPPINES_BOUNDS)
        pickupAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.d(TAG, "Pickup Place: ${place.name}, ${place.latLng}, Address: ${place.address}")
                place.latLng?.let {
                    updateMapLocation(it, place.name ?: "Selected Pickup", true)
                    isPickupMode = false
                    destinationEditText.requestFocus()
                    val destFrag = supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
                    destFrag.setText("")
                    destinationMarker?.remove()
                    destinationMarker = null
                    destinationLatLng = null
                    destinationEditText.setText("")
                } ?: run {
                    Log.e(TAG, "Selected pickup place has null LatLng!")
                    Toast.makeText(this@BookingActivity, "Could not get location for selected pickup place.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(status: Status) {
                Log.e(TAG, "An error occurred with pickup autocomplete: $status")
                Toast.makeText(this@BookingActivity, "Error loading pickup suggestions: ${status.statusMessage}", Toast.LENGTH_LONG).show()
            }
        })

        destinationAutocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
        destinationAutocompleteFragment.setHint("Enter Destination")

        destinationAutocompleteFragment.setCountry("PH")
        destinationAutocompleteFragment.setLocationBias(PHILIPPINES_BOUNDS)
        destinationAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.d(TAG, "Destination Place: ${place.name}, ${place.latLng}, Address: ${place.address}")
                place.latLng?.let {
                    updateMapLocation(it, place.name ?: "Selected Destination", false)
                } ?: run {
                    Log.e(TAG, "Selected destination place has null LatLng!")
                    Toast.makeText(this@BookingActivity, "Could not get location for selected destination place.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(status: Status) {
                Log.e(TAG, "An error occurred with destination autocomplete: $status")
                Toast.makeText(this@BookingActivity, "Error loading destination suggestions: ${status.statusMessage}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun checkLocationPermissionAndSetupMap() {
        Log.d(TAG, "Checking location permissions.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Location permission already granted.")
            enableMyLocationOnMap()
        } else {
            Log.d(TAG, "Location permission not granted. Requesting...")
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationOnMap() {
        if (mMap == null) {
            Log.d(TAG, "Map not initialized yet. Cannot enable MyLocation layer.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Enabling MyLocation layer and attempting to move camera.")
            mMap?.isMyLocationEnabled = true
            fusedLocationProviderClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    val currentLatLng = LatLng(it.latitude, it.longitude)
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, DEFAULT_ZOOM))
                    Log.d(TAG, "Moved camera to current location: lat/lng: (${it.latitude},${it.longitude})")
                } ?: run {
                    Log.d(TAG, "Last known location is null. Using default map setup.")
                    setupMapWithDefaultLocation()
                }
            }.addOnFailureListener {
                Log.e(TAG, "Failed to get current location for initial camera.", it)
                setupMapWithDefaultLocation()
            }
        } else {
            Log.d(TAG, "Location permission still not granted. Cannot enable MyLocation layer.")
            setupMapWithDefaultLocation()
        }
    }

    private fun setupMapWithDefaultLocation() {
        if (mMap == null) {
            Log.d(TAG, "Map not initialized yet for default location.")
            return
        }
        Log.d(TAG, "Setting up map with default location (Manila).")
        val manila = LatLng(14.5995, 120.9842)
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(manila, DEFAULT_ZOOM))
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocationAndSet(isForPickup: Boolean) {
        if (mMap == null) {
            Toast.makeText(this, "Map not ready.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationProviderClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                val currentLatLng = LatLng(it.latitude, it.longitude)
                val title = if (isForPickup) "Current Pickup Location" else "Current Destination Location"
                updateMapLocation(currentLatLng, title, isForPickup)

                 if (isForPickup) {
                    isPickupMode = false
                    destinationEditText.requestFocus()
                    val destinationAutocompleteFragment =
                        supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
                    destinationAutocompleteFragment.setText("")
                    destinationMarker?.remove()
                    destinationMarker = null
                    destinationLatLng = null
                    destinationEditText.setText("")
                }
            } ?: Toast.makeText(this, "Could not get current location. Ensure GPS is on.", Toast.LENGTH_LONG).show()
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get current location for setAsLocation", e)
            Toast.makeText(this, "Failed to get current location.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun updateMapLocation(latLng: LatLng, title: String, isPickup: Boolean) {
        val markerIconDrawableId = if (isPickup) R.drawable.ic_maps_marker_start else R.drawable.ic_maps_marker_end
        val customMarkerIcon = getBitmapDescriptorFromVector(this, markerIconDrawableId)

        if (customMarkerIcon == null) {
            Log.e(TAG, "Failed to create custom marker icon for $title. Using default marker.")
        }
        val markerOptions = MarkerOptions().position(latLng).title(title)
        customMarkerIcon?.let { markerOptions.icon(it) }

        val addressText = getAddressFromLatLng(latLng) ?: title

        if (isPickup) {
            pickupMarker?.remove()
            pickupMarker = mMap?.addMarker(markerOptions)
            pickupLocationLatLng = latLng
            pickupLocationEditText.setText(addressText)
            Log.d(TAG, "Updated pickup: $addressText ($latLng)")
        } else {
            destinationMarker?.remove()
            destinationMarker = mMap?.addMarker(markerOptions)
            destinationLatLng = latLng
            destinationEditText.setText(addressText)
            Log.d(TAG, "Updated destination: $addressText ($latLng)")
        }
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
    }

    private fun getAddressFromLatLng(latLng: LatLng): String? {
        if (!Geocoder.isPresent()) {
            Log.e(TAG, "Geocoder not available on this device.")
            Toast.makeText(this, "Geocoder service not available.", Toast.LENGTH_SHORT).show()
            return "Lat: ${latLng.latitude}, Lng: ${latLng.longitude}"
        }
        return try {
            val addresses: List<Address>? = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                addresses[0].getAddressLine(0) ?: "Unknown address at this location"
            } else {
                "Address not found for this location"
            }
        } catch (e: IOException) {
            Log.e(TAG, "Geocoder IOException for LatLng $latLng", e)
            "Could not fetch address (network error)"
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Geocoder IllegalArgumentException for LatLng $latLng", e)
            "Could not fetch address (invalid coordinates)"
        }
    }
}
