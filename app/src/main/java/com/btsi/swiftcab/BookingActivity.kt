package com.btsi.swiftcab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import java.util.*

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var bookingRequestsRef: DatabaseReference
    private lateinit var functions: FirebaseFunctions

    // Views
    private lateinit var pickupLocationEditText: TextView
    private lateinit var destinationEditText: TextView
    private lateinit var buttonConfirmBooking: Button
    private lateinit var buttonCancelRide: Button
    private lateinit var buttonCancelWhileFinding: Button // New Cancel Button
    private lateinit var buttonUseCurrentLocationPickup: ImageView
    private lateinit var buttonUseCurrentLocationDestination: ImageView
    private lateinit var infoCardView: CardView
    private lateinit var bookingStatusCardView: CardView
    private lateinit var findingDriverOverlayLayout: View
    private lateinit var textViewInFindingOverlay: TextView
    private lateinit var textViewBookingStatusHeader: TextView
    private lateinit var textViewDriverNameStatus: TextView
    private lateinit var textViewVehicleDetailsStatus: TextView
    private lateinit var textViewTripStatusMessage: TextView


    // State
    private var pickupLocationLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null
    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var currentBookingId: String? = null
    private var bookingStatusListener: ValueEventListener? = null

    // Timeout for finding a driver
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    companion object {
        private const val TAG = "BookingActivity"
        private const val DEFAULT_ZOOM = 15f

        // Booking Statuses
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_ON_TRIP = "ON_TRIP"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED_BY_PASSENGER = "CANCELLED_BY_PASSENGER"
        const val STATUS_CANCELLED_BY_DRIVER = "CANCELLED_BY_DRIVER"
        const val STATUS_NO_DRIVERS_FOUND = "NO_DRIVERS_FOUND"

        // Philippines LatLng Bounds for Places API
        private val PHILIPPINES_BOUNDS = RectangularBounds.newInstance(
            LatLng(4.314, 116.399), // Southwest corner
            LatLng(21.133, 126.652)  // Northeast corner
        )
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            enableMyLocationOnMap()
        } else {
            Toast.makeText(this, "Location permission is required to use the map.", Toast.LENGTH_LONG).show()
            setupMapWithDefaultLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking)


        firebaseAuth = FirebaseAuth.getInstance()
        if (firebaseAuth.currentUser == null) {
            Log.e(TAG, "User is not logged in. Redirecting to LoginActivity.")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        functions = Firebase.functions
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())
        bookingRequestsRef = FirebaseDatabase.getInstance().getReference("booking_requests")

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }

        // Initialize Views
        pickupLocationEditText = findViewById(R.id.pickupLocationEditText)
        destinationEditText = findViewById(R.id.destinationEditText)
        buttonConfirmBooking = findViewById(R.id.buttonConfirmBooking)
        buttonCancelRide = findViewById(R.id.buttonCancelRideRider)
        buttonCancelWhileFinding = findViewById(R.id.buttonCancelWhileFinding)
        buttonUseCurrentLocationPickup = findViewById(R.id.buttonCurrentLocationPickup)
        buttonUseCurrentLocationDestination = findViewById(R.id.buttonCurrentLocationDropoff)
        infoCardView = findViewById(R.id.infoCardView)
        bookingStatusCardView = findViewById(R.id.bookingStatusCardView)
        findingDriverOverlayLayout = findViewById(R.id.findingDriverLayout)
        textViewInFindingOverlay = findViewById(R.id.textViewFindingDriver)
        textViewBookingStatusHeader = findViewById(R.id.textViewBookingStatusHeader)
        textViewDriverNameStatus = findViewById(R.id.textViewDriverNameStatus)
        textViewVehicleDetailsStatus = findViewById(R.id.textViewVehicleDetailsStatus)
        textViewTripStatusMessage = findViewById(R.id.textViewTripStatusMessage)

        // Set up Map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragmentContainerView) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupAutocompleteFragments()
        setupClickListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDriverSearchTimeout()
        removeBookingStatusListener()
    }

    override fun onBackPressed() {
        if (currentBookingId != null) {
            // If there's an active booking, offer to cancel it.
            cancelBookingRequest()
        } else {
            // If no active booking, perform the default back action.
            super.onBackPressed()
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        resetToInitialState()
        checkLocationPermissionAndSetupMap()
        mMap.setOnMapClickListener { latLng ->
            // Simple logic: first tap sets pickup, second sets destination
            if (pickupLocationLatLng == null) {
                addOrUpdateMarker(latLng, "Pickup Location", true)
            } else if (destinationLatLng == null) {
                addOrUpdateMarker(latLng, "Destination", false)
            }
        }
    }

    private fun checkLocationPermissionAndSetupMap() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocationOnMap()
        } else {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableMyLocationOnMap() {
        mMap.isMyLocationEnabled = true
        fusedLocationProviderClient.lastLocation.addOnSuccessListener(this) { location ->
            if (location != null) {
                val currentLatLng = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, DEFAULT_ZOOM))
            } else {
                Log.d(TAG, "Last location is null")
                setupMapWithDefaultLocation()
            }
        }
    }

    private fun setupMapWithDefaultLocation() {
        // Default to a central point in the Philippines if location is unavailable
        val defaultLocation = LatLng(12.8797, 121.7740) // Center of Philippines
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 6f))
    }

    private fun setupAutocompleteFragments() {
        val pickupAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment
        pickupAutocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
        pickupAutocompleteFragment.setCountries("PH") // Restrict to Philippines
        pickupAutocompleteFragment.setLocationBias(PHILIPPINES_BOUNDS)
        pickupAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.i(TAG, "Pickup Place: ${place.name}, ${place.latLng}")
                addOrUpdateMarker(place.latLng!!, "Pickup Location", true)
            }

            override fun onError(status: com.google.android.gms.common.api.Status) {
                Log.e(TAG, "An error occurred with pickup autocomplete: $status")
            }
        })

        val destinationAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
        destinationAutocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
        destinationAutocompleteFragment.setCountries("PH") // Restrict to Philippines
        destinationAutocompleteFragment.setLocationBias(PHILIPPINES_BOUNDS)
        destinationAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.i(TAG, "Destination Place: ${place.name}, ${place.latLng}")
                addOrUpdateMarker(place.latLng!!, "Destination", false)
            }

            override fun onError(status: com.google.android.gms.common.api.Status) {
                Log.e(TAG, "An error occurred with destination autocomplete: $status")
            }
        })
    }

    /**
     * Helper function to convert a vector drawable to a BitmapDescriptor.
     */
    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
        return ContextCompat.getDrawable(context, vectorResId)?.run {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    private fun addOrUpdateMarker(latLng: LatLng, title: String, isPickup: Boolean) {
        val startIcon = bitmapDescriptorFromVector(this, R.drawable.ic_maps_marker_start)
        val endIcon = bitmapDescriptorFromVector(this, R.drawable.ic_maps_marker_end)

        if (isPickup) {
            pickupMarker?.remove()
            pickupMarker = mMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(title)
                    .icon(startIcon)
            )
            pickupLocationLatLng = latLng
            pickupLocationEditText.text = getAddressFromLatLng(latLng)
        } else {
            destinationMarker?.remove()
            destinationMarker = mMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(title)
                    .icon(endIcon)
            )
            destinationLatLng = latLng
            destinationEditText.text = getAddressFromLatLng(latLng)
        }
        zoomToFitMarkers()
    }

    private fun setupClickListeners() {
        buttonConfirmBooking.setOnClickListener {
            createBookingRequest()
        }

        buttonCancelRide.setOnClickListener {
            cancelBookingRequest()
        }

        buttonCancelWhileFinding.setOnClickListener {
            cancelBookingRequest()
        }

        buttonUseCurrentLocationPickup.setOnClickListener {
            getCurrentLocationAndSet(isPickup = true)
        }

        buttonUseCurrentLocationDestination.setOnClickListener {
            getCurrentLocationAndSet(isPickup = false)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun getCurrentLocationAndSet(isPickup: Boolean) {
        if (!mMap.isMyLocationEnabled) {
            Toast.makeText(this, "Please enable location services.", Toast.LENGTH_SHORT).show()
            return
        }
        fusedLocationProviderClient.lastLocation.addOnSuccessListener(this) { location ->
            if (location != null) {
                val currentLatLng = LatLng(location.latitude, location.longitude)
                val title = if (isPickup) "Pickup Location" else "Destination"
                addOrUpdateMarker(currentLatLng, title, isPickup)
            } else {
                Toast.makeText(this, "Could not get current location. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createBookingRequest() {
        val riderId = firebaseAuth.currentUser?.uid
        if (riderId == null) {
            Toast.makeText(this, "You must be logged in to book a ride.", Toast.LENGTH_SHORT).show()
            return
        }

        if (pickupLocationLatLng == null || destinationLatLng == null) {
            Toast.makeText(this, "Please select both pickup and destination locations.", Toast.LENGTH_SHORT).show()
            return
        }

        val bookingId = bookingRequestsRef.push().key
        if (bookingId == null) {
            Toast.makeText(this, "Could not create booking. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val bookingRequest = BookingRequest(
            bookingId = bookingId,
            riderId = riderId,
            riderName = firebaseAuth.currentUser?.displayName ?: "Unknown Rider",
            pickupLatitude = pickupLocationLatLng!!.latitude,
            pickupLongitude = pickupLocationLatLng!!.longitude,
            pickupAddress = pickupLocationEditText.text.toString(),
            destinationLatitude = destinationLatLng!!.latitude,
            destinationLongitude = destinationLatLng!!.longitude,
            destinationAddress = destinationEditText.text.toString(),
            status = STATUS_PENDING,
            timestamp = System.currentTimeMillis()
        )

        bookingRequestsRef.child(bookingId).setValue(bookingRequest)
            .addOnSuccessListener {
                Log.d(TAG, "Booking request created successfully with ID: $bookingId")
                currentBookingId = bookingId
                listenForBookingStatus(bookingId)
                showFindingDriverLayout("Finding a driver...")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create booking request", e)
                Toast.makeText(this, "Booking failed. Please check your connection.", Toast.LENGTH_SHORT).show()
                resetToInitialState()
            }
    }

    private fun listenForBookingStatus(bookingId: String) {
        removeBookingStatusListener() // Ensure no old listeners are attached
        val reference = bookingRequestsRef.child(bookingId)

        bookingStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.w(TAG, "Booking ID $bookingId no longer exists.")
                    Toast.makeText(this@BookingActivity, "Booking has been resolved or cancelled.", Toast.LENGTH_SHORT).show()
                    resetToInitialState()
                    return
                }

                val booking = snapshot.getValue(BookingRequest::class.java)
                booking?.let {
                    Log.d(TAG, "Booking status updated: ${it.status}")
                    when (it.status) {
                        STATUS_ACCEPTED -> {
                            stopDriverSearchTimeout()
                            val driverName = it.driverName ?: "Your Driver"
                            val vehicleDetails = it.driverVehicleDetails ?: "Vehicle details not available"
                            showBookingStatusCard(
                                header = "Driver is on the way!",
                                driverName = "Driver: $driverName",
                                vehicleDetails = "Vehicle: $vehicleDetails",
                                message = "Your driver is heading to the pickup location."
                            )
                        }
                        STATUS_ON_TRIP -> {
                           showBookingStatusCard(
                               header = "Trip in Progress",
                               driverName = "Driver: ${it.driverName ?: "Your Driver"}",
                               vehicleDetails = "Vehicle: ${it.driverVehicleDetails ?: "Details unavailable"}",
                               message = "You are on your way to the destination."
                           )
                        }
                        STATUS_COMPLETED -> {
                            Toast.makeText(this@BookingActivity, "Trip completed. Thank you for riding with SwiftCab!", Toast.LENGTH_LONG).show()
                            resetToInitialState()
                        }
                        STATUS_CANCELLED_BY_DRIVER, STATUS_CANCELLED_BY_PASSENGER -> {
                            Toast.makeText(this@BookingActivity, "Booking has been cancelled.", Toast.LENGTH_LONG).show()
                            resetToInitialState()
                        }
                        STATUS_NO_DRIVERS_FOUND -> {
                            Toast.makeText(this@BookingActivity, "We couldn't find a driver for you at the moment. Please try again later.", Toast.LENGTH_LONG).show()
                            resetToInitialState()
                        }
                        STATUS_PENDING -> {
                            // Still searching, no UI change needed here as the overlay is already showing.
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Database error listening to booking status: ${error.message}")
                Toast.makeText(this@BookingActivity, "Lost connection to booking service.", Toast.LENGTH_SHORT).show()
                resetToInitialState()
            }
        }
        reference.addValueEventListener(bookingStatusListener!!)
    }

    private fun cancelBookingRequest() {
        if (currentBookingId == null) {
            Log.w(TAG, "Attempted to cancel a null booking ID.")
            resetToInitialState() // Reset UI just in case
            return
        }

        stopDriverSearchTimeout()

        val data = hashMapOf("bookingId" to currentBookingId)

        functions
            .getHttpsCallable("cancelBooking")
            .call(data)
            .addOnSuccessListener { result ->
                Log.d(TAG, "Successfully called cancelBooking function.")
                // The listener for STATUS_CANCELLED_BY_PASSENGER will handle the UI reset.
                Toast.makeText(this, "Booking cancelled.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to call cancelBooking function", e)
                // Even if the function fails, try to reset the state for the user.
                Toast.makeText(this, "Cancellation failed. Please check your connection.", Toast.LENGTH_SHORT).show()
                resetToInitialState()
            }
    }

    private fun resetToInitialState() {
        if (::mMap.isInitialized) {
            mMap.clear()
        }
        pickupMarker = null
        destinationMarker = null
        pickupLocationLatLng = null
        destinationLatLng = null
        pickupLocationEditText.text = ""
        destinationEditText.text = ""
        infoCardView.visibility = View.VISIBLE
        buttonConfirmBooking.visibility = View.VISIBLE
        bookingStatusCardView.visibility = View.GONE
        findingDriverOverlayLayout.visibility = View.GONE
        buttonCancelRide.visibility = View.GONE
        buttonCancelWhileFinding.visibility = View.GONE

        removeBookingStatusListener()
        stopDriverSearchTimeout()
        currentBookingId = null
    }

    private fun showFindingDriverLayout(message: String) {
        infoCardView.visibility = View.GONE
        bookingStatusCardView.visibility = View.GONE
        findingDriverOverlayLayout.visibility = View.VISIBLE
        buttonConfirmBooking.visibility = View.GONE
        buttonCancelRide.visibility = View.GONE // Hide the card's cancel button
        buttonCancelWhileFinding.visibility = View.VISIBLE // Show the overlay's cancel button
        textViewInFindingOverlay.text = message
        startDriverSearchTimeout()
    }

    private fun showBookingStatusCard(header: String, driverName: String, vehicleDetails: String, message: String) {
        infoCardView.visibility = View.GONE
        findingDriverOverlayLayout.visibility = View.GONE
        buttonCancelWhileFinding.visibility = View.GONE
        bookingStatusCardView.visibility = View.VISIBLE
        textViewBookingStatusHeader.text = header
        textViewDriverNameStatus.text = driverName
        textViewVehicleDetailsStatus.text = vehicleDetails
        textViewTripStatusMessage.text = message

        if (header.contains("Trip in Progress") || header.contains("Completed")) {
            buttonCancelRide.visibility = View.GONE
        } else {
            buttonCancelRide.visibility = View.VISIBLE
        }
    }

    private fun startDriverSearchTimeout() {
        stopDriverSearchTimeout()
        timeoutRunnable = Runnable {
            Log.d(TAG, "Driver search timed out.")
            if (currentBookingId != null) {
                bookingRequestsRef.child(currentBookingId!!).child("status").setValue(STATUS_NO_DRIVERS_FOUND)
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable!!, 60000) // 60-second timeout
    }

    private fun stopDriverSearchTimeout() {
        timeoutRunnable?.let {
            timeoutHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }

    private fun removeBookingStatusListener() {
        if (bookingStatusListener != null && currentBookingId != null) {
            bookingRequestsRef.child(currentBookingId!!).removeEventListener(bookingStatusListener!!)
            bookingStatusListener = null
        }
    }

    private fun getAddressFromLatLng(latLng: LatLng): String {
        return try {
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                addresses[0]?.getAddressLine(0) ?: "Unknown Location"
            } else {
                "Unknown Location"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address from LatLng", e)
            "Could not get address"
        }
    }

    private fun zoomToFitMarkers() {
        val builder = LatLngBounds.Builder()
        pickupMarker?.let { builder.include(it.position) }
        destinationMarker?.let { builder.include(it.position) }

        if (pickupMarker != null || destinationMarker != null) {
            val bounds = builder.build()
            val padding = 150 // offset from edges of the map in pixels
            val cu = CameraUpdateFactory.newLatLngBounds(bounds, padding)
            try {
                mMap.animateCamera(cu)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error animating camera to fit markers", e)
                findViewById<View>(R.id.mapFragmentContainerView).post {
                    try {
                        mMap.animateCamera(cu)
                    } catch (e2: IllegalStateException) {
                        Log.e(TAG, "Error animating camera on second attempt", e2)
                    }
                }
            }
        }
    }
}
